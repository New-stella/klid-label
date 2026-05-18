package kr.co.cudo.authoring.label;

import kr.co.cudo.authoring.assignment.entity.LsTaskAssignment;
import kr.co.cudo.authoring.assignment.repository.LsTaskAssignmentRepository;
import kr.co.cudo.authoring.auth.JwtTestSupport;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.label.controller.FrameImageController;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
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

        // raw + frame 시드 (rawSn 9001 시뮬레이션 — 시드 충돌 방지 위해 별도)
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
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
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

    @Test
    @DisplayName("FrameImage_resolveSafe_baseDir_상대경로_정상_resolve")
    void resolveSafeRelativeOk() {
        Path base = Paths.get("/var/storage").toAbsolutePath().normalize();
        Path resolved = FrameImageController.resolveSafe(base, "seed/9001/frame_1.jpg");
        assertThat(resolved.toString()).endsWith("seed/9001/frame_1.jpg");
        assertThat(resolved.startsWith(base)).isTrue();
    }

    @Test
    @DisplayName("FrameImage_resolveSafe_PathTraversal_차단")
    void resolveSafeTraversalBlocked() {
        Path base = Paths.get("/var/storage").toAbsolutePath().normalize();
        org.junit.jupiter.api.Assertions.assertThrows(
                kr.co.cudo.authoring.common.exception.CustomException.class,
                () -> FrameImageController.resolveSafe(base, "../../etc/passwd"));
    }
}
