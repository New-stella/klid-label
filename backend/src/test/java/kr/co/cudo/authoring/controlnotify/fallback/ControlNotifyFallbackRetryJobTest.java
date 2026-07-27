package kr.co.cudo.authoring.controlnotify.fallback;

import kr.co.cudo.authoring.controlnotify.dto.TaskCompletedPayload;
import kr.co.cudo.authoring.controlnotify.dto.TaskModifiedPayload;
import kr.co.cudo.authoring.controlnotify.service.ControlNotifyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageRequest;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 3 -- ControlNotifyFallbackRetryJob 단위 테스트.
 *
 * <p>재시도는 반드시 {@link ControlNotifyService} 의 dispatch 경로를 태운다 — Client 를 직접 호출하면
 * 재시도 때 만난 409/404 가 자기치유 없이 영구 실패로 남아 dead-letter 가 증식한다.
 */
class ControlNotifyFallbackRetryJobTest {

    /** 폴백 큐에 실제로 저장되는 형태와 동일한 계약 JSON (snake_case 평면). */
    private static final String COMPLETED_JSON = """
            {"job_id":"1","event_type_cd":"FIRE","lclgv_cd":"11680",\
            "lclgv_nm":"서울특별시 강남구","duration_sec":30,"image_count":16}""";

    private static final String MODIFIED_JSON = """
            {"job_id":"2","changed_items":{"images":[],"jsons":["0007.json"]}}""";

    private LsControlNotifyFallbackRepository repository;
    private ControlNotifyFallbackService fallbackService;
    private ControlNotifyService notifyService;
    private ControlNotifyFallbackRetryJob job;

    @BeforeEach
    void setUp() {
        repository = mock(LsControlNotifyFallbackRepository.class);
        fallbackService = mock(ControlNotifyFallbackService.class);
        notifyService = mock(ControlNotifyService.class);
        job = new ControlNotifyFallbackRetryJob(repository, fallbackService, notifyService);
    }

    private LsControlNotifyFallback makePending(Long sn, String key, String eventType, String payloadJson) {
        LsControlNotifyFallback q = LsControlNotifyFallback.pending(key, eventType, sn, payloadJson);
        setField(q, "queueSn", sn);
        return q;
    }

    private static void setField(Object target, String fieldName, Object value) {
        try {
            Field f = findField(target.getClass(), fieldName);
            f.setAccessible(true);
            f.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static Field findField(Class<?> clazz, String name) throws NoSuchFieldException {
        Class<?> cur = clazz;
        while (cur != null) {
            try { return cur.getDeclaredField(name); }
            catch (NoSuchFieldException ignored) { cur = cur.getSuperclass(); }
        }
        throw new NoSuchFieldException(name);
    }

    @Test
    @DisplayName("runOnce_pending_항목_처리_성공")
    void runOnce_success() {
        // given
        LsControlNotifyFallback q1 = makePending(1L, "key-1", "TASK_COMPLETED", COMPLETED_JSON);
        when(repository.findBySttsCdAndNextRtryDtLessThanEqualOrderByNextRtryDtAsc(
                eq(LsControlNotifyFallback.STATUS_PENDING), any(LocalDateTime.class), any(PageRequest.class)))
                .thenReturn(List.of(q1));
        when(fallbackService.claimForRetry(1L)).thenReturn(Optional.of(q1));

        // when
        int processed = job.runOnce();

        // then — 자기치유 규칙을 공유하는 dispatch 경로로 재전송된다(Client 직접 호출 금지).
        assertThat(processed).isEqualTo(1);
        ArgumentCaptor<TaskCompletedPayload> captor = ArgumentCaptor.forClass(TaskCompletedPayload.class);
        verify(notifyService).dispatchCompleted(captor.capture(), eq(1L));
        // 폴백 큐에 저장된 snake_case JSON 이 계약 페이로드로 정확히 복원되어야 한다.
        assertThat(captor.getValue().jobId()).isEqualTo("1");
        assertThat(captor.getValue().imageCount()).isEqualTo(16);
        verify(fallbackService).markSucceeded(1L);
    }

    @Test
    @DisplayName("runOnce_TASK_MODIFIED_항목은_dispatchModified_로_재전송된다")
    void runOnce_modified_usesDispatchModified() {
        // given
        LsControlNotifyFallback q1 = makePending(2L, "key-2", "TASK_MODIFIED", MODIFIED_JSON);
        when(repository.findBySttsCdAndNextRtryDtLessThanEqualOrderByNextRtryDtAsc(
                eq(LsControlNotifyFallback.STATUS_PENDING), any(LocalDateTime.class), any(PageRequest.class)))
                .thenReturn(List.of(q1));
        when(fallbackService.claimForRetry(2L)).thenReturn(Optional.of(q1));

        // when
        int processed = job.runOnce();

        // then
        assertThat(processed).isEqualTo(1);
        ArgumentCaptor<TaskModifiedPayload> captor = ArgumentCaptor.forClass(TaskModifiedPayload.class);
        verify(notifyService).dispatchModified(captor.capture(), eq(2L));
        assertThat(captor.getValue().changedItems().jsons()).containsExactly("0007.json");
        verify(notifyService, never()).dispatchCompleted(any(), any());
        verify(fallbackService).markSucceeded(2L);
    }

    @Test
    @DisplayName("runOnce_claimForRetry_실패시_skip")
    void runOnce_claimFailed_skip() {
        // given
        LsControlNotifyFallback q1 = makePending(1L, "key-1", "TASK_COMPLETED", COMPLETED_JSON);
        when(repository.findBySttsCdAndNextRtryDtLessThanEqualOrderByNextRtryDtAsc(
                eq(LsControlNotifyFallback.STATUS_PENDING), any(LocalDateTime.class), any(PageRequest.class)))
                .thenReturn(List.of(q1));
        when(fallbackService.claimForRetry(1L)).thenReturn(Optional.empty());

        // when
        int processed = job.runOnce();

        // then
        assertThat(processed).isZero();
        verify(notifyService, never()).dispatchCompleted(any(), any());
        verify(notifyService, never()).dispatchModified(any(), any());
        verify(fallbackService, never()).markSucceeded(any());
    }

    @Test
    @DisplayName("runOnce_processOne_실패시_markFailedAndSchedule")
    void runOnce_processFailed_markFailed() {
        // given
        LsControlNotifyFallback q1 = makePending(2L, "key-2", "TASK_MODIFIED", MODIFIED_JSON);
        when(repository.findBySttsCdAndNextRtryDtLessThanEqualOrderByNextRtryDtAsc(
                eq(LsControlNotifyFallback.STATUS_PENDING), any(LocalDateTime.class), any(PageRequest.class)))
                .thenReturn(List.of(q1));
        when(fallbackService.claimForRetry(2L)).thenReturn(Optional.of(q1));
        doThrow(new RuntimeException("502 Bad Gateway"))
                .when(notifyService).dispatchModified(any(), eq(2L));

        // when
        int processed = job.runOnce();

        // then
        assertThat(processed).isZero();
        verify(fallbackService).markFailedAndSchedule(eq(2L), anyString());
    }

    @Test
    @DisplayName("페이로드_없이_적재된_항목은_dispatch_에_null_을_넘겨_재조립하게_한다")
    void runOnce_payloadRebuildRequired_passesNull() {
        // given — 페이로드 조립 실패로 큐에 들어온 항목(A-1). 본문이 비어 있다.
        LsControlNotifyFallback q1 = makePending(4L, "key-4", "TASK_COMPLETED",
                LsControlNotifyFallback.PAYLOAD_REBUILD_REQUIRED);
        when(repository.findBySttsCdAndNextRtryDtLessThanEqualOrderByNextRtryDtAsc(
                eq(LsControlNotifyFallback.STATUS_PENDING), any(LocalDateTime.class), any(PageRequest.class)))
                .thenReturn(List.of(q1));
        when(fallbackService.claimForRetry(4L)).thenReturn(Optional.of(q1));

        // when
        int processed = job.runOnce();

        // then — 역직렬화(실패)로 죽지 않고 null 을 넘겨 dispatch 가 팩토리로 재조립한다.
        assertThat(processed).isEqualTo(1);
        verify(notifyService).dispatchCompleted(isNull(), eq(4L));
        verify(fallbackService).markSucceeded(4L);
    }

    @Test
    @DisplayName("페이로드_없는_수정통지_항목도_dispatchModified_에_null_로_재조립된다")
    void runOnce_modifiedPayloadRebuildRequired_passesNull() {
        // given
        LsControlNotifyFallback q1 = makePending(5L, "key-5", "TASK_MODIFIED",
                LsControlNotifyFallback.PAYLOAD_REBUILD_REQUIRED);
        when(repository.findBySttsCdAndNextRtryDtLessThanEqualOrderByNextRtryDtAsc(
                eq(LsControlNotifyFallback.STATUS_PENDING), any(LocalDateTime.class), any(PageRequest.class)))
                .thenReturn(List.of(q1));
        when(fallbackService.claimForRetry(5L)).thenReturn(Optional.of(q1));

        // when
        int processed = job.runOnce();

        // then
        assertThat(processed).isEqualTo(1);
        verify(notifyService).dispatchModified(isNull(), eq(5L));
    }

    @Test
    @DisplayName("지원하지않는_eventType_이면_격리되고_스케줄러가_죽지_않는다")
    void runOnce_unsupportedEventType_isIsolated() {
        // given — 알 수 없는 이벤트 타입이 큐에 있어도 Job 전체가 죽으면 안 된다.
        LsControlNotifyFallback q1 = makePending(6L, "key-6", "TASK_UNKNOWN", COMPLETED_JSON);
        when(repository.findBySttsCdAndNextRtryDtLessThanEqualOrderByNextRtryDtAsc(
                eq(LsControlNotifyFallback.STATUS_PENDING), any(LocalDateTime.class), any(PageRequest.class)))
                .thenReturn(List.of(q1));
        when(fallbackService.claimForRetry(6L)).thenReturn(Optional.of(q1));

        // when
        int processed = job.runOnce();

        // then — 해당 항목만 실패 처리되고 전송은 시도되지 않는다.
        assertThat(processed).isZero();
        verify(notifyService, never()).dispatchCompleted(any(), any());
        verify(notifyService, never()).dispatchModified(any(), any());
        verify(fallbackService).markFailedAndSchedule(eq(6L), anyString());
    }

    @Test
    @DisplayName("run_최상위에서_예외가_나도_스케줄러_스레드로_전파되지_않는다")
    void run_swallowsExceptionsSoSchedulerSurvives() {
        // given — 조회 자체가 실패(DB 장애)해도 @Scheduled 스레드가 죽으면 이후 재시도가 영구 정지한다.
        when(repository.findBySttsCdAndNextRtryDtLessThanEqualOrderByNextRtryDtAsc(
                any(), any(LocalDateTime.class), any(PageRequest.class)))
                .thenThrow(new IllegalStateException("db down"));

        // when / then — 예외 없이 반환되어야 한다.
        job.run();
    }

    @Test
    @DisplayName("runOnce_역직렬화_불가_페이로드는_markFailedAndSchedule_로_격리된다")
    void runOnce_undeserializablePayload_marksFailed() {
        // given — 큐에 손상된 JSON 이 있어도 Job 전체가 죽지 않아야 한다(fail-closed, 격리).
        LsControlNotifyFallback q1 = makePending(3L, "key-3", "TASK_COMPLETED", "{not-json");
        when(repository.findBySttsCdAndNextRtryDtLessThanEqualOrderByNextRtryDtAsc(
                eq(LsControlNotifyFallback.STATUS_PENDING), any(LocalDateTime.class), any(PageRequest.class)))
                .thenReturn(List.of(q1));
        when(fallbackService.claimForRetry(3L)).thenReturn(Optional.of(q1));

        // when
        int processed = job.runOnce();

        // then
        assertThat(processed).isZero();
        verify(notifyService, never()).dispatchCompleted(any(), any());
        verify(fallbackService).markFailedAndSchedule(eq(3L), anyString());
    }
}
