package kr.co.cudo.authoring.batch.service;

import kr.co.cudo.authoring.marking.service.MarkingActivationTxService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DeidentReservationHook} 단위 테스트 (ADR-052 · SEQ-030 · AC-1032 · AC-1033).
 *
 * <p>이 훅의 존재 이유는 <b>시점</b>이다. 활성 트랜잭션 안에서 예약을 곧바로 깨우면
 * {@code MarkingBatchBridge} 가 {@code AFTER_COMMIT} 으로 영상 행을 다시 읽을 때 비식별 결과가
 * 아직 보이지 않아 skip 으로 판정하고, 그 skip 이 방금 깨운 마킹을 종결시킨다. 그래서 여기서는
 * "커밋 전에는 부르지 않는다"와 "롤백이면 아예 부르지 않는다"를 직접 단언한다.
 */
class DeidentReservationHookTest {

    private MarkingActivationTxService activationService;
    private DeidentReservationHook hook;

    @BeforeEach
    void setUp() {
        activationService = mock(MarkingActivationTxService.class);
        when(activationService.activateReserved(anyLong())).thenReturn(Optional.empty());
        hook = new DeidentReservationHook(activationService);
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("활성_트랜잭션이_없으면_즉시_활성화한다 — 호출자가 이미 커밋을 끝낸 컨텍스트")
    void activatesImmediatelyWithoutTransaction() {
        hook.activateAfterCommit(9001L);

        verify(activationService).activateReserved(9001L);
    }

    @Test
    @DisplayName("활성_트랜잭션에서는_커밋_전에_활성화되지_않고_afterCommit에서만_활성화된다")
    void activatesOnlyAfterCommitWhenTransactionActive() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            hook.activateAfterCommit(9001L);

            // 커밋 전 — 아직 부르면 안 된다. 여기서 부르면 브리지가 비식별 미완료로 보고 skip 한다.
            verify(activationService, never()).activateReserved(anyLong());

            TransactionSynchronizationUtils.triggerAfterCommit();

            verify(activationService).activateReserved(9001L);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("트랜잭션이_롤백되면_예약을_활성화하지_않는다 — afterCommit 미발화")
    void doesNotActivateWhenTransactionRollsBack() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            hook.activateAfterCommit(9001L);

            // 롤백 — afterCommit 은 발화하지 않고 afterCompletion 만 발화한다.
            TransactionSynchronizationUtils.triggerAfterCompletion(
                    org.springframework.transaction.support.TransactionSynchronization.STATUS_ROLLED_BACK);

            verify(activationService, never()).activateReserved(anyLong());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("예약이_없어도_오류가_아니다 — 통상 영상은 Optional.empty 를 돌려받는다")
    void emptyReservationIsNotAnError() {
        when(activationService.activateReserved(9001L)).thenReturn(Optional.empty());

        assertThatCode(() -> hook.activateAfterCommit(9001L)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("활성화가_실패해도_예외를_밖으로_내보내지_않는다 — 비식별 완료를 되돌리지 않는다")
    void activationFailureIsIsolated() {
        when(activationService.activateReserved(9001L)).thenThrow(new IllegalStateException("boom"));

        assertThatCode(() -> hook.activateAfterCommit(9001L)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("마감이_실패해도_예외를_밖으로_내보내지_않는다")
    void closeFailureIsIsolated() {
        doThrow(new IllegalStateException("boom"))
                .when(activationService).closeReservations(eq(9001L), anyString());

        assertThatCode(() -> hook.closeAfterCommit(9001L, DeidentReservationHook.REASON_DEIDENT_FAILED))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("마감도_커밋_이후에만_수행된다")
    void closesOnlyAfterCommitWhenTransactionActive() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            hook.closeAfterCommit(9001L, DeidentReservationHook.REASON_DEIDENT_FAILED);

            verify(activationService, never()).closeReservations(anyLong(), anyString());

            TransactionSynchronizationUtils.triggerAfterCommit();

            verify(activationService).closeReservations(9001L,
                    DeidentReservationHook.REASON_DEIDENT_FAILED);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("rawSn이_null이면_아무것도_하지_않는다")
    void nullRawSnIsNoOp() {
        hook.activateAfterCommit(null);
        hook.closeAfterCommit(null, DeidentReservationHook.REASON_DEIDENT_FAILED);

        verify(activationService, never()).activateReserved(any());
        verify(activationService, never()).closeReservations(any(), anyString());
    }

    @Test
    @DisplayName("마감_사유는_고정_상수다 — 외부 유래 문자열이 로그로 흘러들지 않는다")
    void closeReasonIsFixedConstant() {
        hook.closeAfterCommit(9001L, DeidentReservationHook.REASON_DEIDENT_FAILED);

        verify(activationService).closeReservations(9001L, "DEIDENT_FAILED");
    }
}
