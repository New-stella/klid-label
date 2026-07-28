package kr.co.cudo.authoring.label;

import kr.co.cudo.authoring.auth.JwtTestSupport;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.storage.StorageSubtreePolicy;
import kr.co.cudo.authoring.common.util.SeedImageGenerator;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * H4 — {@code STORAGE_RAW_PATH == STORAGE_DEIDENTIFIED_PATH} (운영 {@code /nas-storage} 형상)에서
 * {@code GET /v1/frames/{srcSn}/deid-image} 가 여전히 <b>비식별 프레임만</b> 서빙하는지 검증한다.
 *
 * <p>base 단일 검사(startsWith)만 하면 두 base 가 같은 순간 {@code frames/raw/**} 도 통과해
 * 원본 픽셀이 "비식별본"으로 새고(fail-open), 반대로 raw base 로만 검증하면 정상 비식별본이
 * 전부 403 이 된다(cudo_246 이슈 E). 서브트리 판정({@code StorageSubtreePolicy})에 위임했으므로
 * 두 경우 모두 올바르게 갈려야 한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@TestPropertySource(properties = {
        "authoring.storage.raw-path=./build/test-storage-shared",
        "authoring.storage.deidentified-path=./build/test-storage-shared"
})
@Sql(scripts = {"/db/test-data.sql", "/db/test-data-video.sql"},
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class FrameDeidImageSameBaseTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private VideoRepository rawRepository;
    @Autowired private LsDataSrcRepository srcRepository;

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;
    @Value("${authoring.storage.deidentified-path}") private String storageDeidPath;

    private String reviewerToken;
    private Long rawSn;
    private Path base;

    @BeforeEach
    void setup() {
        reviewerToken = JwtTestSupport.token(secret, "1", "REVIEWER", "INTERNAL", issuer, 60);

        LsDataRaw raw = LsDataRaw.createFromIngest(
                "CLIP-SAMEBASE-001", "CCTV-SB", "EVT_FALL", "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/var/raw/clip.mp4",
                LocalDateTime.now(), 30);
        rawSn = rawRepository.save(raw).getRawSn();
        base = Paths.get(storageDeidPath).toAbsolutePath().normalize();
    }

    @Test
    @DisplayName("H4_DeidImage_raw와_deid_base가_동일해도_비식별_프레임은_200")
    void sameBaseStillServesDeidFrame() throws Exception {
        String rel = StorageSubtreePolicy.deidFramesDir(rawSn) + "/frame_1.jpg";
        Path file = base.resolve(rel).normalize();
        Files.createDirectories(file.getParent());
        SeedImageGenerator.generate(file, "EVT_FALL", "CCTV-SB", 1, LocalDateTime.now());

        LsDataSrc src = srcRepository.save(
                LsDataSrc.create(rawSn, 1L, 1L, null, rel, LocalDateTime.now()));

        mockMvc.perform(get("/v1/frames/" + src.getSrcSn() + "/deid-image")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/jpeg"));
    }

    @Test
    @DisplayName("H4_DeidImage_raw와_deid_base가_동일해도_원본프레임_서브트리는_403")
    void sameBaseStillRejectsRawSubtree() throws Exception {
        String rel = StorageSubtreePolicy.SEG_FRAMES + "/" + StorageSubtreePolicy.SEG_RAW
                + "/" + rawSn + "/frame_2.jpg";
        Path file = base.resolve(rel).normalize();
        Files.createDirectories(file.getParent());
        SeedImageGenerator.generate(file, "EVT_FALL", "CCTV-SB", 2, LocalDateTime.now());

        LsDataSrc src = srcRepository.save(
                LsDataSrc.create(rawSn, 2L, 2L, null, rel, LocalDateTime.now()));

        mockMvc.perform(get("/v1/frames/" + src.getSrcSn() + "/deid-image")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isForbidden());
    }
}
