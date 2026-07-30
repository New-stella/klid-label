package kr.co.cudo.authoring.batch.step;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.batch.status.BatchStatusService;
import kr.co.cudo.authoring.batch.status.VlmMarkingTxService;
import kr.co.cudo.authoring.common.client.dto.VlmTimeseriesResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Phase C-1 — VLM 논블로킹 제출 <b>완료 핸들러</b> 단위 테스트.
 *
 * <p>고정하는 계약:
 * <ul>
 *   <li>ACK 는 {@code LS_BATCH_PROC_LOG.RESP_PAYLOAD_CN} 에 request_id/status 로 기록된다.</li>
 *   <li>확정 실패는 <b>재개 사유 감사 행 + 마킹 보상 전이</b>만 남기고 <b>상태를 강등하지 않는다</b>.</li>
 *   <li>기록 자체가 실패해도 예외를 밖으로 던지지 않는다(비동기 — 받을 곳이 없다).</li>
 * </ul>
 */
class VlmSubmitOutcomeRecorderTest {

    private BatchStatusService batchStatusService;
    private VlmMarkingTxService markingTxService;
    private VlmSubmitOutcomeRecorder recorder;
    private kr.co.cudo.authoring.webhook.idempotency.WebhookIdempotencyLedger ledger;

    @BeforeEach
    void setUp() {
        batchStatusService = mock(BatchStatusService.class);
        markingTxService = mock(VlmMarkingTxService.class);
        ledger = new kr.co.cudo.authoring.webhook.idempotency.InMemoryWebhookIdempotencyLedger();
        recorder = new VlmSubmitOutcomeRecorder(batchStatusService, markingTxService, new ObjectMapper(), ledger);
    }

    @Test
    @DisplayName("ACK_수신시_request_id와_status가_LS_BATCH_PROC_LOG에_기록된다")
    void ackPersistedToBatchProcLog() {
        recorder.onAccepted(700L, "REQ-700", new VlmTimeseriesResponse("REQ-700", "accepted"));

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(batchStatusService).recordVlmTimeseriesResult(eq(700L), payload.capture());
        assertThat(payload.getValue()).contains("REQ-700").contains("accepted");
    }

    @Test
    @DisplayName("제출_확정실패시_재개사유_감사행과_마킹_보상전이만_수행하고_상태는_강등하지_않는다")
    void submitFailureRecordsReasonAndCompensatesMarking() {
        recorder.onSubmitFailed(701L, 91L, new IllegalStateException("boom"));

        verify(batchStatusService).recordVlmSkippedInNewTx(
                701L, VlmTimeseriesStep.SKIP_REASON_SUBMIT_FAILED);
        verify(markingTxService).markVlmFailedIfRequested(91L);
        // ★ 회귀 위험 2 — 지각 실패가 이미 완료된 파이프라인을 FAILED 로 역행시키면 안 된다.
        verify(batchStatusService, never()).markFailed(any(), any());
        verify(batchStatusService, never()).markStage(any(), any());
        verify(batchStatusService, never()).markCompleted(any());
    }

    @Test
    @DisplayName("감사행_기록이_실패해도_예외를_밖으로_던지지_않고_마킹_보상은_계속_수행한다")
    void recordingFailureIsSwallowed() {
        doThrow(new RuntimeException("db down"))
                .when(batchStatusService).recordVlmSkippedInNewTx(any(), any());

        assertThatCode(() -> recorder.onSubmitFailed(702L, 92L, new IllegalStateException("boom")))
                .doesNotThrowAnyException();
        verify(markingTxService).markVlmFailedIfRequested(92L);
    }

    @Test
    @DisplayName("마킹이_없는_영상_제출실패도_감사행은_남는다")
    void submitFailureWithoutMarking() {
        recorder.onSubmitFailed(703L, null, new IllegalStateException("boom"));

        verify(batchStatusService).recordVlmSkippedInNewTx(
                703L, VlmTimeseriesStep.SKIP_REASON_SUBMIT_FAILED);
        verify(markingTxService).markVlmFailedIfRequested(null);
    }
}
