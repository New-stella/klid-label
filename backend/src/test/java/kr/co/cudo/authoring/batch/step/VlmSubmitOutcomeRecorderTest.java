package kr.co.cudo.authoring.batch.step;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.batch.status.BatchStatusService;
import kr.co.cudo.authoring.batch.status.VlmMarkingTxService;
import kr.co.cudo.authoring.common.client.NonRetryableExternalException;
import kr.co.cudo.authoring.common.client.dto.VlmTimeseriesResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

import java.util.List;

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

    // ───────────────── 벤더 오류코드 40001 — 「미지원 event_type」 구분 ─────────────────

    /**
     * ★ 이 축의 단정은 <b>기록 문자열이 바뀌지 않는다</b>가 절반이다.
     *
     * <p>{@code LS_BATCH_PROC_LOG} 에 적재되는 사유는 재개 판정의 키이자 이미 적재된 과거 행과의
     * 대조 키다. 「더 정확하게」 다듬는 순간 그 행들의 재개 배선이 끊긴다.
     */
    @Test
    @DisplayName("★벤더코드_40001이어도_DB에_적재되는_재개사유_문자열은_바뀌지_않는다")
    void vendorCode40001DoesNotChangeResumeKey() {
        recorder.onSubmitFailed(710L, 92L, new NonRetryableExternalException(
                "시계열 분석 위탁 4xx 응답(status=400)", 400, 40001));

        verify(batchStatusService).recordVlmSkippedInNewTx(
                eq(710L), eq(VlmTimeseriesStep.SKIP_REASON_SUBMIT_FAILED));
        verify(markingTxService).markVlmFailedIfRequested(92L);
    }

    @Test
    @DisplayName("★벤더코드_40001은_미지원_event_type으로_구분해_기록한다")
    void vendorCode40001IsDistinguishedInRecord() {
        List<ILoggingEvent> logs = captureRecorderLogs(() -> recorder.onSubmitFailed(711L, null,
                new NonRetryableExternalException("4xx", 400, 40001)));

        assertThat(logs).anySatisfy(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.ERROR);
            assertThat(e.getFormattedMessage()).contains("undefined event_type").contains("40001");
        });
    }

    @Test
    @DisplayName("벤더코드가_없는_4xx는_종전_기록으로_떨어진다")
    void missingVendorCodeFallsBackToPreviousRecord() {
        List<ILoggingEvent> logs = captureRecorderLogs(() -> recorder.onSubmitFailed(712L, null,
                new NonRetryableExternalException("4xx", 400)));

        assertThat(logs).noneSatisfy(e ->
                assertThat(e.getFormattedMessage()).contains("undefined event_type"));
        verify(batchStatusService).recordVlmSkippedInNewTx(
                eq(712L), eq(VlmTimeseriesStep.SKIP_REASON_SUBMIT_FAILED));
    }

    @Test
    @DisplayName("벤더코드가_원인_사슬_안쪽에_있어도_찾아낸다_메시지를_파싱하지_않는다")
    void vendorCodeFoundThroughCauseChain() {
        List<ILoggingEvent> logs = captureRecorderLogs(() -> recorder.onSubmitFailed(713L, null,
                new RuntimeException("wrapped",
                        new NonRetryableExternalException("4xx", 400, 40001))));

        assertThat(logs).anySatisfy(e ->
                assertThat(e.getFormattedMessage()).contains("undefined event_type"));
    }

    /** 완료 핸들러가 남기는 로그만 수집한다. */
    private List<ILoggingEvent> captureRecorderLogs(Runnable action) {
        Logger logger = (Logger) LoggerFactory.getLogger(VlmSubmitOutcomeRecorder.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            action.run();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
        return appender.list;
    }
}
