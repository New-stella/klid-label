package kr.co.cudo.authoring.controlnotify.fallback;

import kr.co.cudo.authoring.common.client.ControlNotifyClient;
import kr.co.cudo.authoring.controlnotify.service.ControlNotifyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import reactor.core.publisher.Mono;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 3 -- ControlNotifyFallbackRetryJob 단위 테스트.
 */
class ControlNotifyFallbackRetryJobTest {

    private LsControlNotifyFallbackRepository repository;
    private ControlNotifyFallbackService fallbackService;
    private ControlNotifyClient client;
    private ControlNotifyFallbackRetryJob job;

    @BeforeEach
    void setUp() {
        repository = mock(LsControlNotifyFallbackRepository.class);
        fallbackService = mock(ControlNotifyFallbackService.class);
        client = mock(ControlNotifyClient.class);
        job = new ControlNotifyFallbackRetryJob(repository, fallbackService, client);
    }

    private LsControlNotifyFallback makePending(Long sn, String key, String eventType) {
        LsControlNotifyFallback q = LsControlNotifyFallback.pending(key, eventType, sn, "{\"rawSn\":" + sn + "}");
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
        LsControlNotifyFallback q1 = makePending(1L, "key-1", "TASK_COMPLETED");
        when(repository.findBySttsCdAndNextRtryDtLessThanEqualOrderByNextRtryDtAsc(
                eq(LsControlNotifyFallback.STATUS_PENDING), any(LocalDateTime.class), any(PageRequest.class)))
                .thenReturn(List.of(q1));
        when(fallbackService.claimForRetry(1L)).thenReturn(Optional.of(q1));
        when(client.sendTaskCompleted(any())).thenReturn(Mono.empty());

        // when
        int processed = job.runOnce();

        // then
        assertThat(processed).isEqualTo(1);
        verify(fallbackService).markSucceeded(1L);
    }

    @Test
    @DisplayName("runOnce_claimForRetry_실패시_skip")
    void runOnce_claimFailed_skip() {
        // given
        LsControlNotifyFallback q1 = makePending(1L, "key-1", "TASK_COMPLETED");
        when(repository.findBySttsCdAndNextRtryDtLessThanEqualOrderByNextRtryDtAsc(
                eq(LsControlNotifyFallback.STATUS_PENDING), any(LocalDateTime.class), any(PageRequest.class)))
                .thenReturn(List.of(q1));
        when(fallbackService.claimForRetry(1L)).thenReturn(Optional.empty());

        // when
        int processed = job.runOnce();

        // then
        assertThat(processed).isZero();
        verify(client, never()).sendTaskCompleted(any());
        verify(client, never()).sendTaskModified(any());
        verify(fallbackService, never()).markSucceeded(any());
    }

    @Test
    @DisplayName("runOnce_processOne_실패시_markFailedAndSchedule")
    void runOnce_processFailed_markFailed() {
        // given
        LsControlNotifyFallback q1 = makePending(2L, "key-2", "TASK_MODIFIED");
        when(repository.findBySttsCdAndNextRtryDtLessThanEqualOrderByNextRtryDtAsc(
                eq(LsControlNotifyFallback.STATUS_PENDING), any(LocalDateTime.class), any(PageRequest.class)))
                .thenReturn(List.of(q1));
        when(fallbackService.claimForRetry(2L)).thenReturn(Optional.of(q1));
        when(client.sendTaskModified(any())).thenReturn(Mono.error(new RuntimeException("502 Bad Gateway")));

        // when
        int processed = job.runOnce();

        // then
        assertThat(processed).isZero();
        verify(fallbackService).markFailedAndSchedule(eq(2L), anyString());
    }
}
