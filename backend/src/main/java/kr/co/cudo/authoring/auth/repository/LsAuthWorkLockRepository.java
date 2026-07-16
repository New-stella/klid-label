package kr.co.cudo.authoring.auth.repository;

import kr.co.cudo.authoring.auth.entity.LsAuthWorkLock;
import kr.co.cudo.authoring.common.datasource.ControlRepo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

@ControlRepo
public interface LsAuthWorkLockRepository extends JpaRepository<LsAuthWorkLock, Long> {

    boolean existsByLockTargetCdAndDataRawSnAndLockSttsCd(String lockTargetCd, Long dataRawSn, String lockSttsCd);

    List<LsAuthWorkLock> findAllByLockTargetCdAndDataRawSnAndLockSttsCd(
            String lockTargetCd, Long dataRawSn, String lockSttsCd);

    /**
     * 만료된 LOCKED 락 조회 (R4 sweeper).
     *
     * <p><b>null-safe 필수</b>: {@code expireDt IS NOT NULL} 을 명시한다. expireDt 가 null 인 락은
     * {@code expireDt < :now} 비교가 항상 false 라 조회에서 빠지지만(SQL 3치 논리), 의도를 코드로 못박아
     * "만료시각 미설정 락은 sweep 대상 아님(영구 미회수)"을 명확히 한다.
     */
    @Query("SELECT l FROM LsAuthWorkLock l "
            + "WHERE l.lockSttsCd = 'LOCKED' AND l.expireDt IS NOT NULL AND l.expireDt < :now")
    List<LsAuthWorkLock> findExpiredLocked(@Param("now") LocalDateTime now);

    /**
     * 만료 락을 <b>정체성(PK)로 스코프</b>하여 원자적·조건부 회수 (R4 sweeper 전용).
     *
     * <p><b>왜 PK 스코프인가(CWE-362 배타성)</b>: rawSn 스코프 회수({@code releaseRaw})는 sweep 스냅샷 이후
     * 홀더가 해당 락을 정상 해제하고 동일 rawSn 에 <b>신규 비만료 락</b>을 재획득한 경우(V69 partial unique 상
     * 정상 전이), 그 신규 락을 오회수해 배타성이 붕괴한다. 이 UPDATE 는 스냅샷한 만료 락의 PK 만 대상으로 하고,
     * {@code LOCKED AND expireDt IS NOT NULL AND expireDt < :now} 를 <b>같은 원자 연산</b>에서 재검증하므로
     * 재조회 TOCTOU 없이 대상 0건(이미 해제/재획득/미래만료)이면 회수하지 않는다.
     *
     * @return 실제 회수(release)된 행 수 (0 또는 1)
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE LsAuthWorkLock l "
            + "SET l.lockSttsCd = 'RELEASED', l.releaseRsn = :reason, l.releaseDt = :now, "
            + "l.mdfcnId = :actorId, l.mdfcnDt = :now "
            + "WHERE l.workLockSn = :workLockSn AND l.lockSttsCd = 'LOCKED' "
            + "AND l.expireDt IS NOT NULL AND l.expireDt < :now")
    int reclaimExpiredByPk(@Param("workLockSn") Long workLockSn,
                           @Param("actorId") String actorId,
                           @Param("reason") String reason,
                           @Param("now") LocalDateTime now);
}
