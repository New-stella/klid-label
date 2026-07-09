package kr.co.cudo.authoring.controlnotify.fallback;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 3 -- ControlNotifyFallbackService 단위 테스트.
 */
class ControlNotifyFallbackServiceTest {

    private LsControlNotifyFallbackRepository repository;

    @BeforeEach
    void setUp() {
        repository = mock(LsControlNotifyFallbackRepository.class);
        when(repository.save(any(LsControlNotifyFallback.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("enqueuePending_enabled_true시_정상_적재")
    void enqueuePending_enabled_success() {
        // given
        when(repository.countBySttsCdIn(anyCollection())).thenReturn(0L);
        ControlNotifyFallbackService svc = new ControlNotifyFallbackService(repository, true, null);

        // when
        Optional<String> result = svc.enqueuePending("key-1", "TASK_COMPLETED", 100L, "{}");

        // then
        assertThat(result).contains("key-1");
        verify(repository, times(1)).save(any(LsControlNotifyFallback.class));
    }

    @Test
    @DisplayName("enqueuePending_enabled_false시_empty_반환")
    void enqueuePending_disabled_empty() {
        // given
        ControlNotifyFallbackService svc = new ControlNotifyFallbackService(repository, false, null);

        // when
        Optional<String> result = svc.enqueuePending("key-1", "TASK_COMPLETED", 100L, "{}");

        // then
        assertThat(result).isEmpty();
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("enqueuePending_큐_깊이_초과시_예외")
    void enqueuePending_queueFull_throws() {
        // given
        when(repository.countBySttsCdIn(anyCollection()))
                .thenReturn(ControlNotifyFallbackService.MAX_QUEUE_DEPTH);
        ControlNotifyFallbackService svc = new ControlNotifyFallbackService(repository, true, null);

        // when / then
        assertThatThrownBy(() -> svc.enqueuePending("key-1", "TASK_COMPLETED", 100L, "{}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("queue is full");
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("enqueuePending_중복_idempotencyKey_멱등_처리")
    void enqueuePending_duplicateKey_idempotent() {
        // given
        when(repository.countBySttsCdIn(anyCollection())).thenReturn(0L);
        when(repository.save(any(LsControlNotifyFallback.class)))
                .thenThrow(new DataIntegrityViolationException("uk_idempotency_key"));
        ControlNotifyFallbackService svc = new ControlNotifyFallbackService(repository, true, null);

        // when
        Optional<String> result = svc.enqueuePending("dup-1", "TASK_COMPLETED", 100L, "{}");

        // then
        assertThat(result).contains("dup-1");
    }

    // ---------- recordImmediateSuccess (발송 성공 관찰 적재) ----------

    @Test
    @DisplayName("recordImmediateSuccess_SUCCEEDED_SUCCESS_행_적재")
    void recordImmediateSuccess_savesTerminalSuccessRow() {
        // given
        ArgumentCaptor<LsControlNotifyFallback> captor =
                ArgumentCaptor.forClass(LsControlNotifyFallback.class);
        ControlNotifyFallbackService svc = new ControlNotifyFallbackService(repository, true, null);

        // when
        Optional<String> result =
                svc.recordImmediateSuccess("ok-1", "TASK_COMPLETED", 100L, "{}");

        // then
        assertThat(result).contains("ok-1");
        verify(repository).save(captor.capture());
        LsControlNotifyFallback saved = captor.getValue();
        assertThat(saved.getSttsCd()).isEqualTo(LsControlNotifyFallback.STATUS_SUCCEEDED);
        assertThat(saved.getSendRsltCd()).isEqualTo(LsControlNotifyFallback.SEND_RSLT_SUCCESS);
    }

    @Test
    @DisplayName("recordImmediateSuccess_disabled시_empty_반환")
    void recordImmediateSuccess_disabled_empty() {
        // given
        ControlNotifyFallbackService svc = new ControlNotifyFallbackService(repository, false, null);

        // when
        Optional<String> result =
                svc.recordImmediateSuccess("ok-1", "TASK_COMPLETED", 100L, "{}");

        // then
        assertThat(result).isEmpty();
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("recordImmediateSuccess_중복키_충돌시_멱등_처리")
    void recordImmediateSuccess_duplicateKey_idempotent() {
        // given
        when(repository.save(any(LsControlNotifyFallback.class)))
                .thenThrow(new DataIntegrityViolationException("uk_idempotency_key"));
        ControlNotifyFallbackService svc = new ControlNotifyFallbackService(repository, true, null);

        // when
        Optional<String> result =
                svc.recordImmediateSuccess("dup-ok", "TASK_MODIFIED", 200L, "{}");

        // then — 예외 전파 없이 멱등 반환.
        assertThat(result).contains("dup-ok");
    }

    @Test
    @DisplayName("claimForRetry_정상_RETRYING_전이")
    void claimForRetry_success() {
        // given
        when(repository.claimAtomically(eq(1L), any(LocalDateTime.class))).thenReturn(1);
        LsControlNotifyFallback entity = LsControlNotifyFallback.pending("k", "TASK_COMPLETED", 1L, "{}");
        when(repository.findById(1L)).thenReturn(Optional.of(entity));
        ControlNotifyFallbackService svc = new ControlNotifyFallbackService(repository, true, null);

        // when
        Optional<LsControlNotifyFallback> result = svc.claimForRetry(1L);

        // then
        assertThat(result).isPresent();
        verify(repository).claimAtomically(eq(1L), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("claimForRetry_CAS_실패시_empty")
    void claimForRetry_casFail_empty() {
        // given
        when(repository.claimAtomically(eq(1L), any(LocalDateTime.class))).thenReturn(0);
        ControlNotifyFallbackService svc = new ControlNotifyFallbackService(repository, true, null);

        // when
        Optional<LsControlNotifyFallback> result = svc.claimForRetry(1L);

        // then
        assertThat(result).isEmpty();
        verify(repository, never()).findById(anyLong());
    }

    @Test
    @DisplayName("markSucceeded_정상_처리")
    void markSucceeded_callsEntity() {
        // given
        LsControlNotifyFallback entity = LsControlNotifyFallback.pending("k", "TASK_COMPLETED", 1L, "{}");
        when(repository.findById(1L)).thenReturn(Optional.of(entity));
        ControlNotifyFallbackService svc = new ControlNotifyFallbackService(repository, true, null);

        // when
        svc.markSucceeded(1L);

        // then
        assertThat(entity.getSttsCd()).isEqualTo(LsControlNotifyFallback.STATUS_SUCCEEDED);
    }

    @Test
    @DisplayName("markFailedAndSchedule_재시도_가능시_PENDING_복귀")
    void markFailedAndSchedule_retryable() {
        // given
        LsControlNotifyFallback entity = LsControlNotifyFallback.pending("k", "TASK_COMPLETED", 1L, "{}");
        when(repository.findById(1L)).thenReturn(Optional.of(entity));
        ControlNotifyFallbackService svc = new ControlNotifyFallbackService(repository, true, null);

        // when
        svc.markFailedAndSchedule(1L, "503 Service Unavailable");

        // then
        assertThat(entity.getRtryCnt()).isEqualTo(1);
        assertThat(entity.getSttsCd()).isEqualTo(LsControlNotifyFallback.STATUS_PENDING);
    }

    @Test
    @DisplayName("markFailedAndSchedule_초과시_DEAD_LETTER")
    void markFailedAndSchedule_deadLetter() {
        // given
        LsControlNotifyFallback entity = LsControlNotifyFallback.pending("k", "TASK_COMPLETED", 1L, "{}");
        when(repository.findById(1L)).thenReturn(Optional.of(entity));
        ControlNotifyFallbackService svc = new ControlNotifyFallbackService(repository, true, null);

        // when -- 6회 실패 (max 5 초과)
        for (int i = 0; i < 6; i++) {
            svc.markFailedAndSchedule(1L, "err-" + i);
        }

        // then
        assertThat(entity.getSttsCd()).isEqualTo(LsControlNotifyFallback.STATUS_DEAD_LETTER);
        assertThat(entity.getDlqDt()).isNotNull();
    }
}
