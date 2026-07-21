package kr.co.cudo.authoring.batch.controller;

import kr.co.cudo.authoring.auth.JwtTestSupport;
import kr.co.cudo.authoring.batch.orchestrator.BatchOrchestrator;
import kr.co.cudo.authoring.batch.orchestrator.BatchStage;
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
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * B3 — POST /v1/videos/{rawSn}/batch/retry E2E (MockMvc 통합).
 *
 * <p>인가(REVIEWER 전용, WORKER 403), 상태 검증(FAILED 아님 409), 성공(200) 을 검증한다.
 * 재기동 파이프라인({@link BatchOrchestrator#process}) 은 mock 으로 대체해 성공 응답만 확정한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class BatchReprocessControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private VideoRepository videoRepository;

    @MockBean private BatchOrchestrator orchestrator;

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;

    private String reviewerToken;
    private String workerToken;

    @BeforeEach
    void setup() {
        reviewerToken = JwtTestSupport.token(secret, "1", "REVIEWER", "INTERNAL", issuer, 60);
        workerToken = JwtTestSupport.token(secret, "100", "WORKER", "INTERNAL", issuer, 60);
    }

    private Long saveVideo(boolean failed) {
        LsDataRaw raw = LsDataRaw.createFromIngest(
                "CLIP-BR-" + System.nanoTime(), "CCTV-BR", "EVT", "11680",
                LsDataRaw.PRVC_TYPE_PRVC, "/var/raw/clip.mp4", LocalDateTime.now(), 30);
        if (failed) {
            raw.markBatchFailed();
        }
        return videoRepository.save(raw).getRawSn();
    }

    @Test
    @DisplayName("배치재처리API_WORKER는_403")
    void workerForbidden() throws Exception {
        Long rawSn = saveVideo(true);

        mockMvc.perform(post("/v1/videos/" + rawSn + "/batch/retry")
                        .header("Authorization", "Bearer " + workerToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("배치재처리API_인증없음_401")
    void unauthenticated() throws Exception {
        Long rawSn = saveVideo(true);

        mockMvc.perform(post("/v1/videos/" + rawSn + "/batch/retry"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("배치재처리API_FAILED가_아니면_409")
    void notFailedConflict() throws Exception {
        Long rawSn = saveVideo(false); // PENDING 상태

        mockMvc.perform(post("/v1/videos/" + rawSn + "/batch/retry")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CONFLICT"));
    }

    @Test
    @DisplayName("배치재처리API_존재하지않는_영상_404")
    void notFound() throws Exception {
        mockMvc.perform(post("/v1/videos/999999999/batch/retry")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("배치재처리API_rawSn_0이하_400")
    void invalidRawSnBadRequest() throws Exception {
        // @Min(1) 검증 — 0/음수 rawSn 은 400 (파이프라인 진입 전 차단).
        mockMvc.perform(post("/v1/videos/0/batch/retry")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/v1/videos/-5/batch/retry")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("배치재처리API_FAILED_영상_REVIEWER_성공_200")
    void reviewerSuccess() throws Exception {
        Long rawSn = saveVideo(true);
        when(orchestrator.process(anyLong())).thenReturn(BatchStage.COMPLETED);

        mockMvc.perform(post("/v1/videos/" + rawSn + "/batch/retry")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.rawSn").value(rawSn))
                .andExpect(jsonPath("$.data.stage").value("COMPLETED"));
    }
}
