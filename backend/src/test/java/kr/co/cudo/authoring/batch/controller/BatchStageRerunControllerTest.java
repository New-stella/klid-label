package kr.co.cudo.authoring.batch.controller;

import kr.co.cudo.authoring.auth.JwtTestSupport;
import kr.co.cudo.authoring.batch.orchestrator.BatchOrchestrator;
import kr.co.cudo.authoring.batch.orchestrator.BatchStage;
import kr.co.cudo.authoring.batch.orchestrator.BatchStageBundle;
import kr.co.cudo.authoring.batch.status.BatchStatusService;
import kr.co.cudo.authoring.batch.status.ManualStageSkip;
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
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * POST /v1/videos/{rawSn}/batch/stages/{stage}/rerun E2E (MockMvc 통합). [@design API-201]
 *
 * <p>인가(REVIEWER 전용) · 되돌린 <b>작업 묶음</b>만 수락 · 완주 축 선점을 검증한다. 요청 본문이 없다 —
 * 묶음이 곧 범위라 고를 것이 없다(구 {@code scope} 본문 폐기).
 * 파이프라인({@link BatchOrchestrator})은 mock 으로 대체해 접수 응답만 확정한다(실행은 비동기다).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class BatchStageRerunControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private VideoRepository videoRepository;
    @Autowired private BatchStatusService batchStatusService;

    @MockBean private BatchOrchestrator orchestrator;

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;

    private String reviewerToken;
    private String workerToken;

    @BeforeEach
    void setup() {
        reviewerToken = JwtTestSupport.token(secret, "1", "REVIEWER", "INTERNAL", issuer, 60);
        workerToken = JwtTestSupport.token(secret, "100", "WORKER", "INTERNAL", issuer, 60);
        when(orchestrator.processBundleRerun(anyLong(), any(), any())).thenReturn(BatchStage.COMPLETED);
    }

    /** 완주(COMPLETED) 상태 영상 — 이 엔드포인트의 유일한 선점 출발점. */
    private Long saveCompletedVideo() {
        LsDataRaw raw = LsDataRaw.createFromIngest(
                "CLIP-RR-" + System.nanoTime(), "CCTV-RR", "EVT", "11680",
                LsDataRaw.PRVC_TYPE_PRVC, "/var/raw/clip.mp4", LocalDateTime.now(), 30);
        raw.markCompleted();
        return videoRepository.save(raw).getRawSn();
    }

    /** 그 묶음을 실제로 되돌린 상태로 만든다(스킵 → 해제). */
    private void clearSkipOf(Long rawSn, BatchStageBundle bundle) {
        batchStatusService.recordManualStageSkip(rawSn, bundle,
                ManualStageSkip.REASON_PREFIX + "벤더 장애", "1");
        batchStatusService.recordManualStageSkipCleared(rawSn, bundle,
                ManualStageSkip.CLEARED_REASON_PREFIX + "운영자 해제", "1");
    }

    @Test
    @DisplayName("묶음재수행API_WORKER는_403")
    void workerForbidden() throws Exception {
        Long rawSn = saveCompletedVideo();

        mockMvc.perform(post("/v1/videos/" + rawSn + "/batch/stages/VLM/rerun")
                        .header("Authorization", "Bearer " + workerToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("묶음재수행API_인증없음_401")
    void unauthenticated() throws Exception {
        Long rawSn = saveCompletedVideo();

        mockMvc.perform(post("/v1/videos/" + rawSn + "/batch/stages/VLM/rerun"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("묶음재수행API_존재하지_않는_영상은_404")
    void notFound() throws Exception {
        mockMvc.perform(post("/v1/videos/99999999/batch/stages/VLM/rerun")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("★★묶음재수행API_요청_본문이_없어도_접수된다_구_scope_필수_폐기")
    void noRequestBodyRequired() throws Exception {
        // 묶음이 곧 범위라 고를 것이 없다. 본문을 요구하면 FE 가 의미 없는 값을 실어야 한다.
        Long rawSn = saveCompletedVideo();
        clearSkipOf(rawSn, BatchStageBundle.VLM);

        mockMvc.perform(post("/v1/videos/" + rawSn + "/batch/stages/VLM/rerun")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("★묶음재수행API_되돌린_묶음이_아니면_400")
    void notClearedBundleRejected() throws Exception {
        Long rawSn = saveCompletedVideo();
        // 시계열만 되돌렸는데 오토라벨을 요청한다 — 임의 묶음 지정 통로를 닫는 장치.
        clearSkipOf(rawSn, BatchStageBundle.VLM);

        mockMvc.perform(post("/v1/videos/" + rawSn + "/batch/stages/AUTOLABEL/rerun")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("★★묶음재수행API_구_개별단계값_YOLO는_400이다_단위가_묶음으로_반전됐다")
    void legacyPerStageValueRejected() throws Exception {
        Long rawSn = saveCompletedVideo();
        clearSkipOf(rawSn, BatchStageBundle.AUTOLABEL);

        mockMvc.perform(post("/v1/videos/" + rawSn + "/batch/stages/YOLO/rerun")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("★묶음재수행API_되돌린_묶음은_접수된다_200")
    void reviewerAccepted() throws Exception {
        Long rawSn = saveCompletedVideo();
        clearSkipOf(rawSn, BatchStageBundle.AUTOLABEL);

        mockMvc.perform(post("/v1/videos/" + rawSn + "/batch/stages/AUTOLABEL/rerun")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.rawSn").value(rawSn))
                .andExpect(jsonPath("$.data.stage").value("AUTOLABEL"))
                .andExpect(jsonPath("$.data.accepted").value(true));
    }

    @Test
    @DisplayName("★묶음재수행API_완주_상태가_아니면_409_실패영상은_전체_재기동이_담당한다")
    void nonCompletedVideoRejected() throws Exception {
        LsDataRaw raw = LsDataRaw.createFromIngest(
                "CLIP-RR-F-" + System.nanoTime(), "CCTV-RR", "EVT", "11680",
                LsDataRaw.PRVC_TYPE_PRVC, "/var/raw/clip.mp4", LocalDateTime.now(), 30);
        raw.markBatchFailed();
        Long rawSn = videoRepository.save(raw).getRawSn();
        clearSkipOf(rawSn, BatchStageBundle.VLM);

        mockMvc.perform(post("/v1/videos/" + rawSn + "/batch/stages/VLM/rerun")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isConflict());
    }
}
