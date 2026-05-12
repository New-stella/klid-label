package kr.co.cudo.authoring.video;

import kr.co.cudo.authoring.auth.JwtTestSupport;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.video.service.FrameImageService;
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
 * SCR-REVIEW-002 — {@code GET /v1/videos/{rawSn}/frames/{frameNo}/image} 보안/동작 검증.
 *
 * <p>검증 시나리오:
 * <ul>
 *   <li>정상 ANONY 영상 — 200 image/jpeg</li>
 *   <li>비식별 영상(PRVC) — deidFilePath 존재 시 deid 경로 사용</li>
 *   <li>비식별 영상(PRVC) — deidFilePath null 시 NOT_FOUND</li>
 *   <li>Path Traversal — 403</li>
 *   <li>비인증 — 401</li>
 *   <li>frameNo &lt; 0 — 400</li>
 *   <li>존재하지 않는 영상 — 404</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Sql(scripts = {"/db/test-data.sql", "/db/test-data-video.sql"},
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class FrameImageRawFrameNoTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private VideoRepository rawRepository;
    @Autowired private LsDataSrcRepository srcRepository;

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;
    @Value("${authoring.storage.raw-path:./storage/raw}") private String storageRawPath;

    private String reviewerToken;
    private String workerToken;
    private Long rawSnAnony;
    private Long rawSnPrvc;
    private Path baseDir;
    private Path framePathAnony;
    private Path framePathPrvcDeid;

    @BeforeEach
    void setup() throws IOException {
        reviewerToken = JwtTestSupport.token(secret, "1",   "REVIEWER", "INTERNAL", issuer, 60);
        workerToken   = JwtTestSupport.token(secret, "100", "WORKER",   "INTERNAL", issuer, 60);

        baseDir = Paths.get(storageRawPath).toAbsolutePath().normalize();

        // --- ANONY 영상 + 프레임 ---
        LsDataRaw rawAnony = LsDataRaw.createFromIngest(
                "CLIP-REVF-ANONY", "CCTV-X1", "EVT_FALL", "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/var/raw/clipA.mp4",
                LocalDateTime.now(), 30);
        rawAnony = rawRepository.save(rawAnony);
        rawSnAnony = rawAnony.getRawSn();

        String relA = "test-rev-img/" + rawSnAnony + "/frame_0.jpg";
        LsDataSrc srcA = LsDataSrc.create(rawSnAnony, 0, relA, LocalDateTime.now());
        srcRepository.save(srcA);

        framePathAnony = baseDir.resolve(relA).normalize();
        Files.createDirectories(framePathAnony.getParent());
        kr.co.cudo.authoring.common.util.SeedImageGenerator.generate(
                framePathAnony, "EVT_FALL", "CCTV-X1", 0, LocalDateTime.now());

        // --- PRVC 영상 (비식별 대상) + 프레임 (deidFilePath 부여) ---
        LsDataRaw rawPrvc = LsDataRaw.createFromIngest(
                "CLIP-REVF-PRVC", "CCTV-X2", "EVT_FALL", "11680",
                LsDataRaw.PRVC_TYPE_PRVC, "/var/raw/clipB.mp4",
                LocalDateTime.now(), 30);
        rawPrvc = rawRepository.save(rawPrvc);
        rawSnPrvc = rawPrvc.getRawSn();

        String relOriginal = "test-rev-img/" + rawSnPrvc + "/orig_0.jpg";
        String relDeid     = "test-rev-img/" + rawSnPrvc + "/deid_0.jpg";

        LsDataSrc srcB = LsDataSrc.create(rawSnPrvc, 0, relOriginal, LocalDateTime.now());
        srcB.attachDeidPath(relDeid);
        srcRepository.save(srcB);

        framePathPrvcDeid = baseDir.resolve(relDeid).normalize();
        Files.createDirectories(framePathPrvcDeid.getParent());
        kr.co.cudo.authoring.common.util.SeedImageGenerator.generate(
                framePathPrvcDeid, "EVT_FALL", "CCTV-X2", 0, LocalDateTime.now());

        // 원본 (ORIG) 도 만들어 두지만, PRVC 정책상 절대 서빙되어선 안 됨 — 검증용
        Path originalPath = baseDir.resolve(relOriginal).normalize();
        Files.createDirectories(originalPath.getParent());
        kr.co.cudo.authoring.common.util.SeedImageGenerator.generate(
                originalPath, "EVT_FALL", "CCTV-X2", 0, LocalDateTime.now());
    }

    @Test
    @DisplayName("프레임_이미지_서빙_정상_경로_200_image_jpeg_응답")
    void serve_anony_returns200Jpeg() throws Exception {
        mockMvc.perform(get("/v1/videos/" + rawSnAnony + "/frames/0/image")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/jpeg"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }

    @Test
    @DisplayName("비식별_영상은_deidFilePath_반환_원본_경로_미사용")
    void serve_prvc_usesDeidPath() throws Exception {
        long sizeOfDeid = Files.size(framePathPrvcDeid);

        var result = mockMvc.perform(get("/v1/videos/" + rawSnPrvc + "/frames/0/image")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/jpeg"))
                .andReturn();

        // 서빙된 byte 크기가 deid 파일 크기와 일치하는지 확인 — 원본이 아닌 deid 가 서빙됨을 간접 검증.
        long served = result.getResponse().getContentAsByteArray().length;
        assertThat(served).isEqualTo(sizeOfDeid);
    }

    @Test
    @DisplayName("비식별_영상에서_deidFilePath_없으면_NOT_FOUND")
    void serve_prvc_withoutDeidPath_returns404() throws Exception {
        // PRVC 영상이지만 deidFilePath 가 없는 케이스 — 신규 frame 등록
        LsDataSrc noDeid = LsDataSrc.create(rawSnPrvc, 7, "test-rev-img/orphan.jpg", LocalDateTime.now());
        srcRepository.save(noDeid);

        mockMvc.perform(get("/v1/videos/" + rawSnPrvc + "/frames/7/image")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("프레임_이미지_path_traversal_시도_403")
    void serve_pathTraversal_returns403() throws Exception {
        // FILE_PATH 가 traversal 시도 — ANONY 라 filePath 사용
        LsDataSrc evil = LsDataSrc.create(rawSnAnony, 88, "../../../../etc/passwd", LocalDateTime.now());
        srcRepository.save(evil);

        mockMvc.perform(get("/v1/videos/" + rawSnAnony + "/frames/88/image")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("미인증_요청은_401")
    void serve_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/v1/videos/" + rawSnAnony + "/frames/0/image"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("WORKER도_이미지_서빙_가능_isAuthenticated")
    void worker_canFetch() throws Exception {
        mockMvc.perform(get("/v1/videos/" + rawSnAnony + "/frames/0/image")
                        .header("Authorization", "Bearer " + workerToken))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/jpeg"));
    }

    @Test
    @DisplayName("videoId_미존재_시_404")
    void serve_videoNotFound_returns404() throws Exception {
        long nonexistent = 9_999_999_999L;
        mockMvc.perform(get("/v1/videos/" + nonexistent + "/frames/0/image")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("frameNo_미존재_시_404")
    void serve_frameNotFound_returns404() throws Exception {
        mockMvc.perform(get("/v1/videos/" + rawSnAnony + "/frames/9999/image")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("FrameImageService_resolveSafe_traversal_차단")
    void resolveSafeTraversalBlocked() {
        Path base = Paths.get("/var/storage").toAbsolutePath().normalize();
        org.junit.jupiter.api.Assertions.assertThrows(
                kr.co.cudo.authoring.common.exception.CustomException.class,
                () -> FrameImageService.resolveSafe(base, "../../etc/passwd"));
    }

    @Test
    @DisplayName("FrameImageService_resolveSafe_정상_경로_허용")
    void resolveSafeRelativeOk() {
        Path base = Paths.get("/var/storage").toAbsolutePath().normalize();
        Path resolved = FrameImageService.resolveSafe(base, "seed/9001/frame_0.jpg");
        assertThat(resolved.toString()).endsWith("seed/9001/frame_0.jpg");
        assertThat(resolved.startsWith(base)).isTrue();
    }
}
