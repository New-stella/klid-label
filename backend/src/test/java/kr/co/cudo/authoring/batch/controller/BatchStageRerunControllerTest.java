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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * POST /v1/videos/{rawSn}/batch/stages/{stage}/rerun E2E (MockMvc 통합). [@design API-201]
 *
 * <p>인가(REVIEWER 전용) · 건너뛰기를 해제한 <b>작업 묶음</b>만 수락 · 완주 축 선점을 검증한다. 요청 본문이 없다 —
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

    /** 그 묶음을 실제로 건너뛰기를 해제한 상태로 만든다(스킵 → 해제). */
    private void clearSkipOf(Long rawSn, BatchStageBundle bundle) {
        batchStatusService.recordManualStageSkip(rawSn, bundle,
                ManualStageSkip.REASON_PREFIX + "벤더 장애", "1");
        batchStatusService.recordManualStageSkipCleared(rawSn, bundle,
                ManualStageSkip.MANUAL_CLEARED_REASON, "1");
    }

    /** 그 묶음을 <b>건너뛴 채로</b> 둔다 — 재수행이 이 상태를 직접 수락한다(ADR-050). */
    private void skipOf(Long rawSn, BatchStageBundle bundle) {
        batchStatusService.recordManualStageSkip(rawSn, bundle,
                ManualStageSkip.REASON_PREFIX + "벤더 장애", "1");
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
    @DisplayName("★묶음재수행API_해제된_묶음이_아니면_400")
    void notClearedBundleRejected() throws Exception {
        Long rawSn = saveCompletedVideo();
        // 시계열만 해제했는데 오토라벨을 요청한다 — 임의 묶음 지정 통로를 닫는 장치.
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
    @DisplayName("★묶음재수행API_해제된_묶음은_접수된다_200")
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

    // ── ★ADR-050 — 건너뛴 상태도 직접 수락한다(해제 2단계 폐지) ────────

    /**
     * ★★해제를 먼저 누르지 않아도 재수행이 수락된다. 나누어 두면 해제만 하고 재수행을 잊었을 때
     * 그 영상이 <b>건너뛰지도 수행하지도 않은</b> 상태로 남는다.
     */
    @Test
    @DisplayName("★★묶음재수행API_건너뛴_상태_그대로_재수행하면_200이고_해제_표식이_남는다")
    void acceptsSkippedBundleDirectly() throws Exception {
        Long rawSn = saveCompletedVideo();
        skipOf(rawSn, BatchStageBundle.VLM);

        mockMvc.perform(post("/v1/videos/" + rawSn + "/batch/stages/VLM/rerun")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accepted").value(true));

        // ★ 표식이 건너뜀인 채로 실행이 시작되면 오케스트레이터가 다시 건너뛴다 —
        //   해제 표식이 실제로 서 있어야 재수행이 무효가 되지 않는다.
        assertThat(batchStatusService.isBundleManuallySkipped(rawSn, BatchStageBundle.VLM)).isFalse();
        assertThat(batchStatusService.clearedBundles(rawSn)).containsExactly("VLM");
        assertThat(batchStatusService.latestManualSkipMarker(rawSn, BatchStageBundle.VLM))
                .hasValueSatisfying(l -> assertThat(l.getErrorMsg())
                        .isEqualTo(ManualStageSkip.RERUN_AUTO_CLEARED_REASON));
    }

    /**
     * ★재수행한 영상은 {@code skippedStages} 에서 빠지고 {@code clearedStages} 로 옮겨간다 —
     * 화면은 두 목록의 <b>합집합</b>으로 버튼을 띄우므로 <b>같은 영상을 다시 재수행할 수 있다</b>.
     */
    @Test
    @DisplayName("★★재수행하면_상세응답에서_skippedStages에서_빠지고_clearedStages로_옮겨간다")
    void rerunMovesBundleToClearedStages() throws Exception {
        Long rawSn = saveCompletedVideo();
        skipOf(rawSn, BatchStageBundle.VLM);

        mockMvc.perform(post("/v1/videos/" + rawSn + "/batch/stages/VLM/rerun")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk());

        mockMvc.perform(get("/v1/videos/" + rawSn)
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.skippedStages.length()").value(0))
                .andExpect(jsonPath("$.data.clearedStages.length()").value(1))
                .andExpect(jsonPath("$.data.clearedStages[0]").value("VLM"));
    }

    /**
     * ★★넓어진 것은 「해제됨」 → 「건너뛴 적이 있음」 하나뿐이다 — 표식이 <b>아예 없는</b> 묶음은
     * 종전대로 400 이다. 느슨해지면 요청이 앞 작업을 건너뛰도록 강제할 수 있다.
     */
    @Test
    @DisplayName("★★묶음재수행API_표식이_아예_없는_묶음은_여전히_400")
    void bundleWithoutMarkerStillRejected() throws Exception {
        Long rawSn = saveCompletedVideo();

        mockMvc.perform(post("/v1/videos/" + rawSn + "/batch/stages/AUTOLABEL/rerun")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));
    }
}
