package kr.co.cudo.authoring.version.fallback;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
 * Phase 3 — GiteaFallbackQueueService 단위 테스트.
 */
class GiteaFallbackQueueServiceTest {

    private LsGiteaFallbackQueueRepository repository;

    @BeforeEach
    void setUp() {
        repository = mock(LsGiteaFallbackQueueRepository.class);
        when(repository.save(any(LsGiteaFallbackQueue.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("Phase3_enabled_false_시_큐_적재_안_되고_Optional_empty")
    void disabledSkipsEnqueue() {
        GiteaFallbackQueueService svc = new GiteaFallbackQueueService(repository, false, null);
        Optional<String> result = svc.enqueuePut(null, "p", "main", "m", "u", "");
        assertThat(result).isEmpty();
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("Phase3_enabled_true_시_PUT_적재_+_idempotencyKey_반환")
    void enabledEnqueuesAndReturnsKey() {
        GiteaFallbackQueueService svc = new GiteaFallbackQueueService(repository, true, null);
        Optional<String> result = svc.enqueuePut("key-1", "10/1/2.json", "main", "msg", "u", "Y29udA==");
        assertThat(result).contains("key-1");
        verify(repository, times(1)).save(any(LsGiteaFallbackQueue.class));
    }

    @Test
    @DisplayName("Phase3_idempotencyKey_null_시_UUID_자동_발급")
    void autoIssuesUuidWhenKeyNull() {
        GiteaFallbackQueueService svc = new GiteaFallbackQueueService(repository, true, null);
        Optional<String> result = svc.enqueuePut(null, "p", "main", "m", "u", "");
        assertThat(result).isPresent();
        assertThat(result.get()).matches("^[0-9a-fA-F-]{36}$");
    }

    @Test
    @DisplayName("Phase3_UNIQUE_충돌_DataIntegrityViolation_흡수_그리고_멱등_반환")
    void duplicateKeyAbsorbed() {
        when(repository.save(any(LsGiteaFallbackQueue.class)))
                .thenThrow(new DataIntegrityViolationException("uk_idempotency_key"));
        GiteaFallbackQueueService svc = new GiteaFallbackQueueService(repository, true, null);
        Optional<String> result = svc.enqueuePut("dup-1", "p", "main", "m", "u", "");
        assertThat(result).contains("dup-1");
    }

    @Test
    @DisplayName("Phase3_isEnabled_노출")
    void isEnabledFlag() {
        assertThat(new GiteaFallbackQueueService(repository, true, null).isEnabled()).isTrue();
        assertThat(new GiteaFallbackQueueService(repository, false, null).isEnabled()).isFalse();
    }

    // ---------- DEV_FIX HIGH-1 (CWE-362) ----------

    @Test
    @DisplayName("DEV_FIX_HIGH1_claimForRetry_원자_UPDATE_1_반환_시_findById_조회_후_반환")
    void claimForRetry_atomicUpdateOne_returnsEntity() {
        // given: claimAtomically 가 1 반환 (CAS 성공)
        when(repository.claimAtomically(eq(123L), any(LocalDateTime.class))).thenReturn(1);
        LsGiteaFallbackQueue entity = LsGiteaFallbackQueue.putPending(
                "k", "p", "main", "m", "u", "");
        when(repository.findById(123L)).thenReturn(Optional.of(entity));
        GiteaFallbackQueueService svc = new GiteaFallbackQueueService(repository, true, null);

        // when
        Optional<LsGiteaFallbackQueue> result = svc.claimForRetry(123L);

        // then: 항목 반환 + 원자 UPDATE 호출 검증
        assertThat(result).isPresent();
        verify(repository, times(1)).claimAtomically(eq(123L), any(LocalDateTime.class));
        verify(repository, times(1)).findById(123L);
    }

    @Test
    @DisplayName("DEV_FIX_HIGH1_claimForRetry_원자_UPDATE_0_반환_시_findById_안_함_그리고_empty")
    void claimForRetry_atomicUpdateZero_returnsEmpty() {
        // given: 다른 인스턴스가 이미 RETRYING 으로 마킹 — UPDATE 영향 0건
        when(repository.claimAtomically(eq(456L), any(LocalDateTime.class))).thenReturn(0);
        GiteaFallbackQueueService svc = new GiteaFallbackQueueService(repository, true, null);

        // when
        Optional<LsGiteaFallbackQueue> result = svc.claimForRetry(456L);

        // then: 중복 처리 차단 (Optional.empty) + findById 호출 안 함 (불필요한 select 회피)
        assertThat(result).isEmpty();
        verify(repository, times(1)).claimAtomically(eq(456L), any(LocalDateTime.class));
        verify(repository, never()).findById(anyLong());
    }

    // ---------- DEV_FIX MEDIUM-3 (CWE-770) ----------

    @Test
    @DisplayName("DEV_FIX_MEDIUM3_큐_깊이_MAX_QUEUE_DEPTH_미만_시_정상_적재")
    void enqueuePut_belowMaxQueueDepth_succeeds() {
        when(repository.countByStatusIn(anyCollection())).thenReturn(9999L);
        GiteaFallbackQueueService svc = new GiteaFallbackQueueService(repository, true, null);

        Optional<String> result = svc.enqueuePut("k", "p", "main", "m", "u", "");

        assertThat(result).contains("k");
        verify(repository, times(1)).save(any(LsGiteaFallbackQueue.class));
    }

    @Test
    @DisplayName("DEV_FIX_MEDIUM3_큐_깊이_MAX_QUEUE_DEPTH_도달_시_적재_거부")
    void enqueuePut_atMaxQueueDepth_throws() {
        when(repository.countByStatusIn(anyCollection()))
                .thenReturn(GiteaFallbackQueueService.MAX_QUEUE_DEPTH);
        GiteaFallbackQueueService svc = new GiteaFallbackQueueService(repository, true, null);

        assertThatThrownBy(() -> svc.enqueuePut("k", "p", "main", "m", "u", ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("queue is full");
        verify(repository, never()).save(any(LsGiteaFallbackQueue.class));
    }

    @Test
    @DisplayName("DEV_FIX_MEDIUM3_큐_깊이_초과_시_적재_거부")
    void enqueuePut_overMaxQueueDepth_throws() {
        when(repository.countByStatusIn(anyCollection()))
                .thenReturn(GiteaFallbackQueueService.MAX_QUEUE_DEPTH + 100L);
        GiteaFallbackQueueService svc = new GiteaFallbackQueueService(repository, true, null);

        assertThatThrownBy(() -> svc.enqueuePut("k", "p", "main", "m", "u", ""))
                .isInstanceOf(IllegalStateException.class);
        verify(repository, never()).save(any(LsGiteaFallbackQueue.class));
    }

    @Test
    @DisplayName("DEV_FIX_MEDIUM3_enabled_false_시_큐_깊이_체크_생략_즉시_empty")
    void enqueuePut_disabled_skipsDepthCheck() {
        GiteaFallbackQueueService svc = new GiteaFallbackQueueService(repository, false, null);

        Optional<String> result = svc.enqueuePut("k", "p", "main", "m", "u", "");

        assertThat(result).isEmpty();
        verify(repository, never()).countByStatusIn(anyCollection());
    }
}
