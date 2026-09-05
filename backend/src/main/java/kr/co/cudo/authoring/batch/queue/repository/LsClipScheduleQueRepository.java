package kr.co.cudo.authoring.batch.queue.repository;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import kr.co.cudo.authoring.batch.queue.entity.LsClipScheduleQue;
import kr.co.cudo.authoring.common.datasource.ControlRepo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

@ControlRepo
public interface LsClipScheduleQueRepository extends JpaRepository<LsClipScheduleQue, Long> {

    /**
     * 큐에서 PENDING 상태 작업 1건을 가장 오래된 순서로 조회.
     * 운영에서는 SKIP LOCKED 가 필요하나, JPQL 단계에서는 인터프리터 호환성을 우선해
     * 단순 ORDER BY + LIMIT 로 조회한다. 동시성은 service 레이어의 비관적 잠금/낙관적 잠금에서 처리.
     */
    @Query("SELECT q FROM LsClipScheduleQue q " +
            "WHERE q.sttsCd = 'PENDING' AND q.jobTypeCd = :jobTypeCd " +
            "ORDER BY q.regDt ASC")
    List<LsClipScheduleQue> findPendingByJobTypeCd(@Param("jobTypeCd") String jobTypeCd);

    default Optional<LsClipScheduleQue> findOldestPending(String jobTypeCd) {
        List<LsClipScheduleQue> list = findPendingByJobTypeCd(jobTypeCd);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    /**
     * PENDING 작업을 가장 오래된 순서로 조회하면서 PESSIMISTIC_WRITE 잠금을 획득.
     * - lock.timeout=0 (no-wait) 으로 다른 트랜잭션이 잡고 있으면 즉시 예외 발생 → service 에서 empty 처리.
     * - 전 환경 PostgreSQL 에서 지원되며 SKIP LOCKED 와 유사한 효과를 얻는다.
     *   ⚠ 구 서술 폐기(2026-08-28) — "H2(local) / MariaDB(dev/stg/prd)". DB 는 전 환경 PostgreSQL 이고
     *   local 도 Testcontainers PostgreSQL 이다(ADR-010). 이기종 호환을 제약으로 두지 말 것.
     * - SKIP LOCKED 는 향후 최적화 시 별도 native 쿼리로 도입.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({@QueryHint(name = "jakarta.persistence.lock.timeout", value = "0")})
    @Query("SELECT q FROM LsClipScheduleQue q " +
            "WHERE q.sttsCd = 'PENDING' AND q.jobTypeCd = :jobTypeCd " +
            "ORDER BY q.regDt ASC")
    List<LsClipScheduleQue> findPendingByJobTypeCdForUpdate(@Param("jobTypeCd") String jobTypeCd);

    default Optional<LsClipScheduleQue> findOldestPendingForUpdate(String jobTypeCd) {
        List<LsClipScheduleQue> list = findPendingByJobTypeCdForUpdate(jobTypeCd);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    long countByRawSnAndJobTypeCd(Long rawSn, String jobTypeCd);
}
