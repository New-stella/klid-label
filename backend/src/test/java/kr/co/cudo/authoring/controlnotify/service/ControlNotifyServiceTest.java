package kr.co.cudo.authoring.controlnotify.service;

import kr.co.cudo.authoring.common.client.ControlNotifyClient;
import kr.co.cudo.authoring.controlnotify.event.ReviewApprovedEvent;
import kr.co.cudo.authoring.controlnotify.fallback.ControlNotifyFallbackService;
import kr.co.cudo.authoring.observability.metrics.ControlNotifyMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 3 -- ControlNotifyService 단위 테스트.
 */
class ControlNotifyServiceTest {

    private ControlNotifyClient client;
    private ControlNotifyFallbackService fallbackService;
    private ControlNotifyMetrics metrics;
    private ControlNotifyService svc;

    @BeforeEach
    void setUp() {
        client = mock(ControlNotifyClient.class);
        fallbackService = mock(ControlNotifyFallbackService.class);
        metrics = mock(ControlNotifyMetrics.class);
        svc = new ControlNotifyService(client, fallbackService, metrics);
    }

    @Test
    @DisplayName("TASK_COMPLETED_정상_전송시_Client_호출됨")
    void sendCompleted_success_callsClient() {
        // given
        ReviewApprovedEvent event = new ReviewApprovedEvent(100L, 1L, Instant.now());
        when(client.sendTaskCompleted(any())).thenReturn(Mono.empty());

        // when
        svc.sendCompleted(event);

        // then
        verify(client).sendTaskCompleted(any());
        verify(fallbackService, never()).enqueuePending(anyString(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("TASK_COMPLETED_실패시_폴백큐_적재됨")
    void sendCompleted_failure_enqueuesFallback() {
        // given
        ReviewApprovedEvent event = new ReviewApprovedEvent(100L, 1L, Instant.now());
        when(client.sendTaskCompleted(any())).thenReturn(Mono.error(new RuntimeException("connection refused")));

        // when
        svc.sendCompleted(event);

        // then
        verify(fallbackService).enqueuePending(anyString(), eq("TASK_COMPLETED"), eq(100L), anyString());
    }

    @Test
    @DisplayName("TASK_MODIFIED_정상_전송시_Client_호출됨")
    void sendModified_success_callsClient() {
        // given
        when(client.sendTaskModified(any())).thenReturn(Mono.empty());

        // when
        svc.sendModified(200L, List.of(10L, 20L), List.of("LABEL_ADDED", "META_UPDATED"));

        // then
        verify(client).sendTaskModified(any());
        verify(fallbackService, never()).enqueuePending(anyString(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("TASK_MODIFIED_실패시_폴백큐_적재됨")
    void sendModified_failure_enqueuesFallback() {
        // given
        when(client.sendTaskModified(any())).thenReturn(Mono.error(new RuntimeException("timeout")));

        // when
        svc.sendModified(200L, List.of(10L), List.of("LABEL_UPDATED"));

        // then
        verify(fallbackService).enqueuePending(anyString(), eq("TASK_MODIFIED"), eq(200L), anyString());
    }

    // --- 발송 결과 관찰 적재 (SEND_RSLT_CD=SUCCESS) ---

    @Test
    @DisplayName("즉시_통지_성공시_SEND_RSLT_SUCCESS_행이_적재된다")
    void sendCompleted_success_recordsImmediateSuccess() {
        // given
        ReviewApprovedEvent event = new ReviewApprovedEvent(100L, 1L, Instant.now());
        when(client.sendTaskCompleted(any())).thenReturn(Mono.empty());

        // when
        svc.sendCompleted(event);

        // then — 발송 성공 → SUCCEEDED/SUCCESS 관찰 행 적재, 폴백 큐(pending)는 미적재.
        verify(fallbackService).recordImmediateSuccess(anyString(), eq("TASK_COMPLETED"), eq(100L), anyString());
        verify(fallbackService, never()).enqueuePending(anyString(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("즉시_통지_실패시_PENDING_FAILED로_폴백큐_적재되고_성공행은_미적재")
    void sendCompleted_failure_noSuccessRecord() {
        // given
        ReviewApprovedEvent event = new ReviewApprovedEvent(100L, 1L, Instant.now());
        when(client.sendTaskCompleted(any())).thenReturn(Mono.error(new RuntimeException("connection refused")));

        // when
        svc.sendCompleted(event);

        // then
        verify(fallbackService).enqueuePending(anyString(), eq("TASK_COMPLETED"), eq(100L), anyString());
        verify(fallbackService, never()).recordImmediateSuccess(anyString(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("Modified_즉시_통지_성공시_SEND_RSLT_SUCCESS_행이_적재된다")
    void sendModified_success_recordsImmediateSuccess() {
        // given
        when(client.sendTaskModified(any())).thenReturn(Mono.empty());

        // when
        svc.sendModified(200L, List.of(10L), List.of("LABEL_ADDED"));

        // then
        verify(fallbackService).recordImmediateSuccess(anyString(), eq("TASK_MODIFIED"), eq(200L), anyString());
    }

    @Test
    @DisplayName("관찰_적재_실패해도_통지성공_metrics는_유지된다")
    void sendCompleted_recordFailure_doesNotBreakNotification() {
        // given — 통지 자체는 성공했으나 관찰 행 INSERT 가 예외.
        ReviewApprovedEvent event = new ReviewApprovedEvent(100L, 1L, Instant.now());
        when(client.sendTaskCompleted(any())).thenReturn(Mono.empty());
        when(fallbackService.recordImmediateSuccess(anyString(), anyString(), any(), anyString()))
                .thenThrow(new RuntimeException("db down"));

        // when — 예외가 밖으로 전파되지 않아야 한다.
        svc.sendCompleted(event);

        // then — 통지 성공 메트릭은 유지, 실패 메트릭/폴백 큐 적재는 없음.
        verify(metrics).incrementCompletedSuccess();
        verify(metrics, never()).incrementCompletedFailed();
        verify(fallbackService, never()).enqueuePending(anyString(), anyString(), any(), anyString());
    }

    // --- Phase 5: 메트릭 호출 검증 ---

    @Test
    @DisplayName("통지_성공시_metrics_completedSuccess_호출됨")
    void sendCompleted_success_incrementsMetric() {
        // given
        ReviewApprovedEvent event = new ReviewApprovedEvent(100L, 1L, Instant.now());
        when(client.sendTaskCompleted(any())).thenReturn(Mono.empty());

        // when
        svc.sendCompleted(event);

        // then
        verify(metrics).incrementCompletedSuccess();
        verify(metrics, never()).incrementCompletedFailed();
    }

    @Test
    @DisplayName("통지_실패시_metrics_completedFailed_호출됨")
    void sendCompleted_failure_incrementsMetric() {
        // given
        ReviewApprovedEvent event = new ReviewApprovedEvent(100L, 1L, Instant.now());
        when(client.sendTaskCompleted(any())).thenReturn(Mono.error(new RuntimeException("fail")));

        // when
        svc.sendCompleted(event);

        // then
        verify(metrics).incrementCompletedFailed();
        verify(metrics, never()).incrementCompletedSuccess();
    }

    @Test
    @DisplayName("Modified_통지_성공시_metrics_modifiedSuccess_호출됨")
    void sendModified_success_incrementsMetric() {
        // given
        when(client.sendTaskModified(any())).thenReturn(Mono.empty());

        // when
        svc.sendModified(200L, List.of(10L), List.of("LABEL_ADDED"));

        // then
        verify(metrics).incrementModifiedSuccess();
        verify(metrics, never()).incrementModifiedFailed();
    }

    @Test
    @DisplayName("Modified_통지_실패시_metrics_modifiedFailed_호출됨")
    void sendModified_failure_incrementsMetric() {
        // given
        when(client.sendTaskModified(any())).thenReturn(Mono.error(new RuntimeException("fail")));

        // when
        svc.sendModified(200L, List.of(10L), List.of("LABEL_UPDATED"));

        // then
        verify(metrics).incrementModifiedFailed();
        verify(metrics, never()).incrementModifiedSuccess();
    }
}
