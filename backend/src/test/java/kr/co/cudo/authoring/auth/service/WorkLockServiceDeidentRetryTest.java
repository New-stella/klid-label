package kr.co.cudo.authoring.auth.service;

import kr.co.cudo.authoring.auth.entity.LsAuthWorkLock;
import kr.co.cudo.authoring.auth.repository.LsAuthWorkLockRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 선두 비식별 재시작 잠금 — 종류 구분과 한정 해제.
 *
 * <p>같은 영상 단위 잠금을 트랙 병합·검수완료 재비식별도 쓰므로, 해제가 이 기능의 잠금에만 닿는지가 핵심이다.
 *
 * @design AC-1135
 */
class WorkLockServiceDeidentRetryTest {

    private static final long RAW_SN = 77L;

    private LsAuthWorkLockRepository repository;
    private WorkLockService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        repository = mock(LsAuthWorkLockRepository.class);
        service = new WorkLockService(repository, mock(ObjectProvider.class));
    }

    private void givenActive(LsAuthWorkLock... locks) {
        when(repository.findAllByLockTargetCdAndDataRawSnAndLockSttsCd(
                LsAuthWorkLock.TARGET_RAW, RAW_SN, LsAuthWorkLock.STATUS_LOCKED)).thenReturn(List.of(locks));
    }

    @Test
    @DisplayName("재시작_잠금은_식별자_접두로_구분되고_영상단위_활성잠금이다")
    void 재시작잠금_형태() {
        LsAuthWorkLock lock = LsAuthWorkLock.lockRawForDeidentRetry(RAW_SN, "batch-deident-retry");

        assertThat(lock.isDeidentRetryLock()).isTrue();
        assertThat(lock.getLockTargetCd()).isEqualTo(LsAuthWorkLock.TARGET_RAW);
        assertThat(lock.getLockSttsCd()).isEqualTo(LsAuthWorkLock.STATUS_LOCKED);
        assertThat(lock.getLockId()).hasSizeLessThanOrEqualTo(64);
        assertThat(lock.getExpireDt()).isAfter(lock.getLockDt().plusMinutes(180));
        assertThat(LsAuthWorkLock.lockRaw(RAW_SN, "1", LsAuthWorkLock.REASON_MERGE).isDeidentRetryLock()).isFalse();
        assertThat(LsAuthWorkLock.lockRawForRedeident(RAW_SN, "1").isDeidentRetryLock()).isFalse();
    }

    @Test
    @DisplayName("★해제는_재시작_잠금만_풀고_병합·재비식별_잠금은_유지한다")
    void 한정해제() {
        LsAuthWorkLock retry = LsAuthWorkLock.lockRawForDeidentRetry(RAW_SN, "batch-deident-retry");
        LsAuthWorkLock merge = LsAuthWorkLock.lockRaw(RAW_SN, "1", LsAuthWorkLock.REASON_MERGE);
        LsAuthWorkLock redeident = LsAuthWorkLock.lockRawForRedeident(RAW_SN, "2");
        givenActive(retry, merge, redeident);

        int released = service.releaseDeidentRetryLock(RAW_SN, "batch", "DEIDENT_RETRY_FAILED");

        assertThat(released).isEqualTo(1);
        assertThat(retry.getLockSttsCd()).isEqualTo(LsAuthWorkLock.STATUS_RELEASED);
        assertThat(merge.getLockSttsCd()).isEqualTo(LsAuthWorkLock.STATUS_LOCKED);
        assertThat(redeident.getLockSttsCd()).isEqualTo(LsAuthWorkLock.STATUS_LOCKED);
    }

    @Test
    @DisplayName("재시작_잠금이_없으면_해제는_no-op")
    void 없으면_noop() {
        LsAuthWorkLock merge = LsAuthWorkLock.lockRaw(RAW_SN, "1", LsAuthWorkLock.REASON_MERGE);
        givenActive(merge);

        assertThat(service.releaseDeidentRetryLock(RAW_SN, "batch", "X")).isZero();
        assertThat(service.releaseDeidentRetryLock(null, "batch", "X")).isZero();
        assertThat(merge.getLockSttsCd()).isEqualTo(LsAuthWorkLock.STATUS_LOCKED);
    }

    @Test
    @DisplayName("다른_기능이_잠갔으면_재시작_선점은_409이고_INSERT하지_않는다")
    void 이미잠김은_409() {
        when(repository.existsByLockTargetCdAndDataRawSnAndLockSttsCd(
                LsAuthWorkLock.TARGET_RAW, RAW_SN, LsAuthWorkLock.STATUS_LOCKED)).thenReturn(true);

        CustomException e = catchThrowableOfType(
                () -> service.lockRawForDeidentRetry(RAW_SN, "owner", "사유"), CustomException.class);

        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONFLICT);
        assertThat(e.getMessage()).isEqualTo("사유");
        verify(repository, never()).saveAndFlush(any());
    }
}
