package kr.co.cudo.authoring.label;

import kr.co.cudo.authoring.assignment.entity.LsTaskAssignment;
import kr.co.cudo.authoring.assignment.repository.LsTaskAssignmentRepository;
import kr.co.cudo.authoring.auth.JwtTestSupport;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.client.AiServerClient;
import kr.co.cudo.authoring.common.client.dto.YoloResponse;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 3 — YOLO 오토라벨 수동 트리거 컨트롤러 통합 테스트.
 *
 * <p>보안 매트릭스:
 * <ul>
 *   <li>미인증 → 401.</li>
 *   <li>포털 채널 토큰(PORTAL_USER + CHANNEL_PORTAL) → 403 (역할 + 채널 이중 차단).</li>
 *   <li>타인 배정 WORKER → 403 (IDOR).</li>
 *   <li>본인 배정 WORKER → 200.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Transactional("controlTransactionManager")
class AutolabelControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private VideoRepository rawRepository;
    @Autowired private LsDataSrcRepository srcRepository;
    @Autowired private LsTaskAssignmentRepository authrtRepository;

    @MockBean private AiServerClient aiServerClient;

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;

    private static Path tmpRawDir;

    @DynamicPropertySource
    static void overrideStorageRawPath(DynamicPropertyRegistry registry) throws IOException {
        tmpRawDir = Files.createTempDirectory("autolabel-ctrl-raw-");
        registry.add("authoring.storage.raw-path", () -> tmpRawDir.toAbsolutePath().toString());
    }

    private String reviewerToken;
    private String workerAssignedToken;
    private String workerOtherToken;
    private String portalToken;

    private Long srcSn;
    private Long rawSn;

    @BeforeEach
    void setup() throws IOException {
        reviewerToken       = JwtTestSupport.token(secret, "1",   "REVIEWER",    "INTERNAL", issuer, 60);
        workerAssignedToken = JwtTestSupport.token(secret, "100", "WORKER",      "INTERNAL", issuer, 60);
        workerOtherToken    = JwtTestSupport.token(secret, "101", "WORKER",      "INTERNAL", issuer, 60);
        portalToken         = JwtTestSupport.token(secret, "200", "PORTAL_USER", "PORTAL",   issuer, 60);

        Files.write(tmpRawDir.resolve("0.jpg"), new byte[]{0x01, 0x02});

        LsDataRaw raw = LsDataRaw.createFromIngest(
                "CLIP-AL-001", "CCTV-001", "EVT-A", "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/var/raw/clip.mp4",
                LocalDateTime.now(), 30);
        raw = rawRepository.save(raw);
        rawSn = raw.getRawSn();

        srcSn = srcRepository.save(
                LsDataSrc.create(rawSn, 0, "0.jpg", LocalDateTime.now())).getSrcSn();

        // 100L WORKER 만 LABELER 배정.
        authrtRepository.save(LsTaskAssignment.createLabeler(rawSn, 100L, 1L));
    }

    @Test
    @DisplayName("오토라벨_미인증_401")
    void unauthenticated() throws Exception {
        mockMvc.perform(post("/v1/frames/" + srcSn + "/autolabel"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("오토라벨_포털_채널_토큰_403")
    void portalChannelForbidden() throws Exception {
        mockMvc.perform(post("/v1/frames/" + srcSn + "/autolabel")
                        .header("Authorization", "Bearer " + portalToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("오토라벨_타인배정_WORKER_403")
    void otherAssignmentForbidden() throws Exception {
        mockMvc.perform(post("/v1/frames/" + srcSn + "/autolabel")
                        .header("Authorization", "Bearer " + workerOtherToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("오토라벨_본인배정_WORKER_200_저장")
    void workerAssignedOk() throws Exception {
        when(aiServerClient.predictYoloTrack(any())).thenReturn(Mono.just(
                new YoloResponse(List.of(
                        new YoloResponse.Detection("person", List.of(10.0, 10.0, 40.0, 60.0), 0.9, 3)))));

        mockMvc.perform(post("/v1/frames/" + srcSn + "/autolabel")
                        .header("Authorization", "Bearer " + workerAssignedToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.savedCount").value(1))
                .andExpect(jsonPath("$.data.mock").doesNotExist());
    }

    @Test
    @DisplayName("오토라벨_REVIEWER_200")
    void reviewerOk() throws Exception {
        when(aiServerClient.predictYoloTrack(any())).thenReturn(Mono.just(
                new YoloResponse(List.of(
                        new YoloResponse.Detection("car", List.of(1.0, 2.0, 30.0, 40.0), 0.8, 1)))));

        mockMvc.perform(post("/v1/frames/" + srcSn + "/autolabel")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.savedCount").value(1));
    }
}
