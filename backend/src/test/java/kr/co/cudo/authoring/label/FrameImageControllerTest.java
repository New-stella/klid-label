package kr.co.cudo.authoring.label;

import kr.co.cudo.authoring.assignment.entity.LsTaskAssignment;
import kr.co.cudo.authoring.assignment.repository.LsTaskAssignmentRepository;
import kr.co.cudo.authoring.auth.JwtTestSupport;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.service.FrameImageService;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * FrameImageController — 이미지 서빙 보안/동작 검증.
 *
 * <p>검증 시나리오:
 * <ul>
 *   <li>비인증 요청 → 401</li>
 *   <li>WORKER 본인 배정 + 파일 존재 → 200 + image/jpeg</li>
 *   <li>WORKER 미배정 → 403 (CWE-639 IDOR)</li>
 *   <li>파일 부재 → 404 (내부 경로 노출 X)</li>
 *   <li>Path traversal 시도 (FILE_PATH = "../../etc/passwd") → 403</li>
 *   <li>허용 외 확장자 (.exe) → 403</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Sql(scripts = {"/db/test-data.sql", "/db/test-data-video.sql"},
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class FrameImageControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private VideoRepository rawRepository;
    @Autowired private LsDataSrcRepository srcRepository;
    @Autowired private LsTaskAssignmentRepository authrtRepository;

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;
    @Value("${authoring.storage.raw-path:./storage/raw}") private String storageRawPath;
    @Value("${authoring.storage.deidentified-path:./storage/deidentified}") private String storageDeidentifiedPath;

    private String reviewerToken;
    private String workerAssignedToken;
    private String workerNotAssignedToken;
    private Long srcSn;
    private Long rawSn;
    private Path baseDir;
    private Path framePath;

    @BeforeEach
    void setup() throws IOException {
        reviewerToken           = JwtTestSupport.token(secret, "1",   "REVIEWER", "INTERNAL", issuer, 60);
        workerAssignedToken     = JwtTestSupport.token(secret, "100", "WORKER",   "INTERNAL", issuer, 60);
        workerNotAssignedToken  = JwtTestSupport.token(secret, "101", "WORKER",   "INTERNAL", issuer, 60);

        // raw + frame 시드 (rawSn 9001 시뮬레이션 — 시드 충돌 방지 위해 별도).
        // ANONY + 비식별 경로 없음 = 레거시 프레임 형태이므로, 서빙 정책 정합 후에도 원본 폴백이 적용돼
        // 아래 200/403/404 기대값이 그대로 유지된다(R3 — 레거시 동작 보존).
        LsDataRaw raw = LsDataRaw.createFromIngest(
                "CLIP-IMG-001", "CCTV-IMG", "EVT_FALL", "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/var/raw/clip.mp4",
                LocalDateTime.now(), 30);
        raw = rawRepository.save(raw);
        rawSn = raw.getRawSn();

        // FILE_PATH 는 baseDir 기준 상대경로 (시드와 동일 정책)
        String relPath = "test-img/" + rawSn + "/frame_1.jpg";
        LsDataSrc src = LsDataSrc.create(rawSn, 1, relPath, LocalDateTime.now());
        src = srcRepository.save(src);
        srcSn = src.getSrcSn();

        // WORKER 100 만 배정
        authrtRepository.save(LsTaskAssignment.createLabeler(rawSn, 100L, 1L));

        // 실제 JPEG 파일 생성 — SeedImageGenerator 사용
        baseDir = Paths.get(storageRawPath).toAbsolutePath().normalize();
        framePath = baseDir.resolve(relPath).normalize();
        Files.createDirectories(framePath.getParent());
        kr.co.cudo.authoring.common.util.SeedImageGenerator.generate(
                framePath, "EVT_FALL", "CCTV-IMG", 1, LocalDateTime.now());
    }

    @Test
    @DisplayName("FrameImage_비인증_요청시_401")
    void unauthorizedRejected() throws Exception {
        mockMvc.perform(get("/v1/frames/" + srcSn + "/image"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("FrameImage_REVIEWER_요청시_200_image_jpeg")
    void reviewerCanFetch() throws Exception {
        mockMvc.perform(get("/v1/frames/" + srcSn + "/image")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/jpeg"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                // 신고 게이트가 매 요청 평가되도록 클라이언트 캐시 재사용 금지 (CWE-359/525) —
                // /deid-image · 영상 /stream 과 동일 정책.
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    @DisplayName("FrameImage_WORKER_본인_배정_프레임_200")
    void assignedWorkerCanFetch() throws Exception {
        mockMvc.perform(get("/v1/frames/" + srcSn + "/image")
                        .header("Authorization", "Bearer " + workerAssignedToken))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/jpeg"));
    }

    @Test
    @DisplayName("FrameImage_WORKER_미배정시_403_IDOR_방어")
    void notAssignedWorkerForbidden() throws Exception {
        mockMvc.perform(get("/v1/frames/" + srcSn + "/image")
                        .header("Authorization", "Bearer " + workerNotAssignedToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("FrameImage_파일_부재시_404")
    void fileMissingReturns404() throws Exception {
        // 파일 삭제
        Files.deleteIfExists(framePath);
        mockMvc.perform(get("/v1/frames/" + srcSn + "/image")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("FrameImage_프레임_미존재시_404")
    void unknownFrameReturns404() throws Exception {
        long nonexistentSrcSn = 999_999_999L;
        mockMvc.perform(get("/v1/frames/" + nonexistentSrcSn + "/image")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isNotFound());
    }

    /**
     * 이 스위트의 기본 시드는 <b>ANONY + 비식별 경로 null</b> 이라 원본 폴백 경로만 지나간다 —
     * 즉 "기본 서빙 = 비식별" 정책 전환을 하나도 통과하지 않는다. 그래서 <b>비식별 경로가 채워진</b>
     * 프레임을 이 스위트에서도 직접 시드해, 기본(raw 파라미터 미지정) 응답이 원본이 아니라
     * 비식별 파일임을 고정한다(정책이 되돌아가면 여기서도 깨진다).
     */
    @Test
    @DisplayName("FrameImage_비식별경로가_있으면_기본_요청은_비식별_프레임을_서빙한다")
    void deidPathServedByDefault() throws Exception {
        // given — 원본·비식별 두 벌이 모두 존재하는 프레임
        byte[] deidBytes = "DEID-FRAME-PIXELS".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        byte[] rawBytes = "RAW-ORIGINAL-FRAME-PIXELS-LONGER".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        String rawRel = "frames/raw/" + rawSn + "/fic-orig.jpg";
        String deidRel = "frames/deid/" + rawSn + "/fic-deid.jpg";
        Path rawFile = baseDir.resolve(rawRel).normalize();
        Path deidBase = Paths.get(storageDeidentifiedPath).toAbsolutePath().normalize();
        Path deidFile = deidBase.resolve(deidRel).normalize();
        Files.createDirectories(rawFile.getParent());
        Files.createDirectories(deidFile.getParent());
        Files.write(rawFile, rawBytes);
        Files.write(deidFile, deidBytes);
        LsDataSrc paired = srcRepository.save(
                LsDataSrc.create(rawSn, 7, null, rawRel, deidRel, LocalDateTime.now()));

        // when / then — 기본(raw 미지정)은 비식별본, REVIEWER 가 raw=true 를 명시할 때만 원본
        byte[] served = mockMvc.perform(get("/v1/frames/" + paired.getSrcSn() + "/image")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(served).isEqualTo(deidBytes);

        byte[] rawServed = mockMvc.perform(get("/v1/frames/" + paired.getSrcSn() + "/image")
                        .queryParam("raw", "true")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(rawServed).isEqualTo(rawBytes);

        // WORKER 는 raw=true 를 보내도 원본을 받지 못한다(강제 DEID)
        byte[] workerServed = mockMvc.perform(get("/v1/frames/" + paired.getSrcSn() + "/image")
                        .queryParam("raw", "true")
                        .header("Authorization", "Bearer " + workerAssignedToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(workerServed).isEqualTo(deidBytes);
    }

    @Test
    @DisplayName("FrameImage_PathTraversal_시도시_403")
    void pathTraversalBlocked() throws Exception {
        // FILE_PATH 를 traversal 시도 형태로 변조한 SRC 행 삽입
        LsDataSrc evil = LsDataSrc.create(rawSn, 99, "../../../../etc/passwd", LocalDateTime.now());
        evil = srcRepository.save(evil);

        mockMvc.perform(get("/v1/frames/" + evil.getSrcSn() + "/image")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("FrameImage_허용_외_확장자_요청시_403")
    void disallowedExtensionRejected() throws Exception {
        // baseDir 안의 .exe 파일 — MIME allowlist 차단 검증
        Path evilFile = baseDir.resolve("test-img/" + rawSn + "/exploit.exe");
        Files.createDirectories(evilFile.getParent());
        Files.writeString(evilFile, "fake");

        LsDataSrc badExt = LsDataSrc.create(rawSn, 88,
                "test-img/" + rawSn + "/exploit.exe", LocalDateTime.now());
        badExt = srcRepository.save(badExt);

        mockMvc.perform(get("/v1/frames/" + badExt.getSrcSn() + "/image")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isForbidden());
    }

    // 아래 두 테스트의 검증 대상(resolveSafe)은 컨트롤러가 아니라 FrameImageService 로 일원화되었다 —
    // 컨트롤러가 경로 판정을 자체 보유하던 것이 "srcSn 경로만 원본을 서빙"하던 결함의 원인이라, 판정
    // 코드를 서비스 단일 원천으로 옮겼다. 테스트 의미(경로 순회 차단)는 그대로 두고 참조만 옮긴다.
    @Test
    @DisplayName("FrameImage_resolveSafe_baseDir_상대경로_정상_resolve")
    void resolveSafeRelativeOk() {
        Path base = Paths.get("/var/storage").toAbsolutePath().normalize();
        Path resolved = FrameImageService.resolveSafe(base, "seed/9001/frame_1.jpg");
        assertThat(resolved.toString()).endsWith("seed/9001/frame_1.jpg");
        assertThat(resolved.startsWith(base)).isTrue();
    }

    @Test
    @DisplayName("FrameImage_resolveSafe_PathTraversal_차단")
    void resolveSafeTraversalBlocked() {
        Path base = Paths.get("/var/storage").toAbsolutePath().normalize();
        org.junit.jupiter.api.Assertions.assertThrows(
                kr.co.cudo.authoring.common.exception.CustomException.class,
                () -> FrameImageService.resolveSafe(base, "../../etc/passwd"));
    }
}
