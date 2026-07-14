package kr.co.cudo.authoring.auth.service;

import kr.co.cudo.authoring.auth.entity.LsAuthWorkLock;
import kr.co.cudo.authoring.auth.repository.LsAuthWorkLockRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(value = "controlTransactionManager")
public class WorkLockService {

    private final LsAuthWorkLockRepository repository;

    public void lockRawForRedeident(Long rawSn, String ownerId) {
        if (isRawLocked(rawSn)) {
            throw new CustomException(ErrorCode.CONFLICT, "이미 비식별 재처리 중인 영상입니다.");
        }
        repository.save(LsAuthWorkLock.lockRawForRedeident(rawSn, ownerId));
    }

    /**
     * 영상(rawSn) 배타 편집 락 선점 — Phase 4 트랙 병합용. <b>{@link Propagation#REQUIRES_NEW} 로
     * 독립 커밋</b>하여 LOCKED row 를 즉시 가시화한다(핵심). 이렇게 해야 caller(TrackMergeService)의
     * 병합 트랜잭션이 아직 진행 중이어도, 다른 READ COMMITTED 조회({@code LabelService.bulkUpsert} 의
     * {@link #isRawLocked})가 LOCKED 를 관측해 병합 중 편집을 실제 409 로 차단한다.
     *
     * <p>과거(클래스 기본 REQUIRED)에는 이 락 INSERT 가 caller 의 병합 tx 에 합류하여, finally 의
     * release 와 같은 tx 로 커밋되는 바람에 <b>LOCKED 가 단 한 순간도 커밋되지 않아</b> 동시 편집이
     * 무방비였다(DEV_FIX HIGH). REQUIRES_NEW 로 락 획득 시점에 즉시 커밋해 이를 해소한다.
     *
     * <p>이미 잠겨 있으면(재비식별/다른 병합/편집) {@link ErrorCode#CONFLICT}. check-then-act 경합에서
     * 선제 검사를 통과한 동시 두 요청 중 하나만 INSERT 에 성공하고, 나머지는 V69 partial unique index 가
     * 원자적으로 거부한다({@link org.springframework.dao.DataIntegrityViolationException} → 상위에서 409).
     * redeident 와 동일 {@code TARGET_RAW} 락이라 {@link #isRawLocked} 로 함께 관측된다. 호출자는
     * try-finally 로 {@link #releaseRawInNewTx} 를 보장해야 한다.
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public void lockRawExclusiveInNewTx(Long rawSn, String ownerId) {
        if (isRawLocked(rawSn)) {
            throw new CustomException(ErrorCode.CONFLICT, "이미 편집/재처리 중인 영상입니다.");
        }
        repository.save(LsAuthWorkLock.lockRaw(rawSn, ownerId, LsAuthWorkLock.REASON_MERGE));
    }

    @Transactional(value = "controlTransactionManager", readOnly = true)
    public boolean isRawLocked(Long rawSn) {
        if (rawSn == null) {
            return false;
        }
        return repository.existsByLockTargetCdAndDataRawSnAndLockSttsCd(
                LsAuthWorkLock.TARGET_RAW, rawSn, LsAuthWorkLock.STATUS_LOCKED);
    }

    /**
     * 트랙 병합 전용 락 해제 — <b>{@link Propagation#REQUIRES_NEW} 로 독립 커밋</b>한다. 병합 본
     * 트랜잭션이 재보간 실패로 롤백되더라도 finally 에서 이 메서드가 락을 확실히 해제(커밋)하여 락
     * 누수를 막는다. 배치/재비식별 경로가 caller tx 원자성에 의존하는 공용 {@link #releaseRaw} 와
     * 분리하여, 그쪽 시맨틱을 건드리지 않는다.
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public int releaseRawInNewTx(Long rawSn, String actorId, String reason) {
        return releaseRaw(rawSn, actorId, reason);
    }

    public int releaseRaw(Long rawSn, String actorId, String reason) {
        var locks = repository.findAllByLockTargetCdAndDataRawSnAndLockSttsCd(
                LsAuthWorkLock.TARGET_RAW, rawSn, LsAuthWorkLock.STATUS_LOCKED);
        for (LsAuthWorkLock lock : locks) {
            lock.release(actorId, reason);
        }
        return locks.size();
    }
}
