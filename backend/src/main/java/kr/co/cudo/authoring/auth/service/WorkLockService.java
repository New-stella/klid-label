package kr.co.cudo.authoring.auth.service;

import kr.co.cudo.authoring.auth.entity.LsAuthWorkLock;
import kr.co.cudo.authoring.auth.repository.LsAuthWorkLockRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(value = "controlTransactionManager")
public class WorkLockService {

    /** 만료 sweep 으로 회수된 락의 release 사유 — 정당한 장기작업 홀더의 락 상실을 로그로 구분 추적 가능하게 한다. */
    public static final String REASON_EXPIRED_SWEEP = "EXPIRED_SWEEP";
    private static final String SWEEP_ACTOR = "SYSTEM_SWEEP";

    private final LsAuthWorkLockRepository repository;

    /**
     * 자기 자신의 프록시 참조 — {@link #sweepExpiredLocks} 가 건별 {@link Propagation#REQUIRES_NEW}
     * release 를 <b>프록시 경유</b>로 호출하기 위함. 내부(self) 호출은 프록시를 우회해 REQUIRES_NEW 가
     * 무시되므로, 부분 실패 격리·독립 커밋을 보장하려면 프록시 빈을 통해야 한다. {@link ObjectProvider} 로
     * 지연 조회하여 생성자 자기참조 순환을 피한다.
     */
    private final ObjectProvider<WorkLockService> selfProvider;

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

    /**
     * 만료 락 sweep 전용 회수 — <b>만료 락의 정체성(PK)로 스코프</b>하여 {@link Propagation#REQUIRES_NEW}
     * 로 독립 커밋한다. rawSn 스코프 회수({@link #releaseRaw})와 달리, sweep 스냅샷 이후 홀더가 해당 락을
     * 정상 해제하고 동일 rawSn 에 신규 비만료 락을 재획득한 경우 그 신규 락을 오회수하지 않는다(CWE-362 배타성 보존).
     *
     * <p>회수는 {@link LsAuthWorkLockRepository#reclaimExpiredByPk} 의 <b>원자 조건부 UPDATE</b>로 수행되어
     * {@code LOCKED AND expireDt < now} 를 같은 연산에서 재검증한다(재조회 TOCTOU 제거). 정상 홀더 release
     * 경로인 {@link #releaseRaw} 는 그대로 두고 sweep 전용으로 분리한다.
     *
     * @return 실제 회수된 행 수 (0 또는 1)
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public int reclaimExpiredLockInNewTx(Long workLockSn, String actorId, String reason) {
        return repository.reclaimExpiredByPk(workLockSn, actorId, reason, LocalDateTime.now());
    }

    /**
     * 만료 작업락 sweep (R4). 만료된 LOCKED 락을 조회해 <b>건별로 독립 release</b> 한다.
     *
     * <p><b>2노드 Active-Active 안전</b>: {@code @Scheduled} 는 Quartz 와 달리 양 노드 모두 발화한다.
     * 각 락 회수는 {@link #reclaimExpiredLockInNewTx}(만료 락 <b>PK 로 스코프</b>한 원자 조건부 UPDATE)로
     * <b>조건부·멱등</b>하게 수행되어, 다른 노드의 동시 sweep 이나 홀더의 정상 release 와 경합해도 이미
     * RELEASED 면 0 건으로 skip 된다. rawSn 스코프 회수가 아니라 PK 스코프이므로, 스냅샷 이후 해제·재획득된
     * 동일 rawSn 의 <b>신규 비만료 락</b>을 오회수하지 않는다(CWE-362 배타성 보존).
     *
     * <p><b>커넥션 점유 최소화</b>: 메서드 자체는 {@link Propagation#NOT_SUPPORTED} 로 <b>외곽 트랜잭션 없이</b>
     * 실행한다. 만료 락 조회는 auto-commit 으로 즉시 커넥션을 반납하고, 회수만 건별 {@link Propagation#REQUIRES_NEW}
     * 로 커넥션을 짧게 점유·반납한다(만료 락 대량 시 유휴 커넥션 풀 고갈 방지).
     *
     * <p><b>부분 실패 격리</b>: 건별 {@link Propagation#REQUIRES_NEW}(프록시 경유) + try-catch 로,
     * 1건이 실패해도 나머지 락 회수는 계속된다. 회수는 순수 상태변경만 수행하며 이벤트/통지는 발행하지 않는다.
     *
     * <p><b>감사 로그</b>: 만료로 회수된 각 락을 WARN 으로 남겨, 만료시간(lockRaw=1h/redeident=6h)보다 오래
     * 걸린 정당한 장기작업 홀더가 락을 잃은 경우를 추적 가능하게 한다.
     *
     * @return 회수(release)된 락 수
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.NOT_SUPPORTED)
    public int sweepExpiredLocks() {
        List<LsAuthWorkLock> expired = repository.findExpiredLocked(LocalDateTime.now());
        int reclaimed = 0;
        for (LsAuthWorkLock lock : expired) {
            try {
                int released = selfProvider.getObject()
                        .reclaimExpiredLockInNewTx(lock.getWorkLockSn(), SWEEP_ACTOR, REASON_EXPIRED_SWEEP);
                if (released > 0) {
                    reclaimed += released;
                    log.warn("[WorkLock] expired lock reclaimed rawSn={} owner={} expireDt={}",
                            lock.getDataRawSn(), lock.getLockOwnerId(), lock.getExpireDt());
                }
            } catch (RuntimeException e) {
                // 부분 실패 격리 — 1건 실패가 나머지 sweep 을 막지 않도록 삼키고 다음 락으로 진행.
                log.warn("[WorkLock] expired lock sweep failed rawSn={} reason={}",
                        lock.getDataRawSn(), e.getClass().getSimpleName());
            }
        }
        return reclaimed;
    }
}
