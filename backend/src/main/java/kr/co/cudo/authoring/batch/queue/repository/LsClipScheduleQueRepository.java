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
     * 운영(MariaDB 10.6+)에서는 SKIP LOCKED 가 필요하나, JPQL 단계에서는 인터프리터 호환성을 우선해
     * 단순 ORDER BY + LIMIT 로 조회한다. 동시성은 service 레이어의 비관적 잠금/낙관적 잠금에서 처리.
     */
    @Query("SELECT q FROM LsClipScheduleQue q " +
            "WHERE q.status = 'PENDING' AND q.jobType = :jobType " +
            "ORDER BY q.registeredAt ASC")
    List<LsClipScheduleQue> findPendingByJobType(@Param("jobType") String jobType);

    default Optional<LsClipScheduleQue> findOldestPending(String jobType) {
        List<LsClipScheduleQue> list = findPendingByJobType(jobType);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    /**
     * PENDING 작업을 가장 오래된 순서로 조회하면서 PESSIMISTIC_WRITE 잠금을 획득.
     * - lock.timeout=0 (no-wait) 으로 다른 트랜잭션이 잡고 있으면 즉시 예외 발생 → service 에서 empty 처리.
     * - H2(local) / MariaDB(dev/stg/prd) 모두 지원되며 SKIP LOCKED 와 유사한 효과를 얻는다.
     * - SKIP LOCKED 는 향후 최적화 시 별도 native 쿼리로 도입.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({@QueryHint(name = "jakarta.persistence.lock.timeout", value = "0")})
    @Query("SELECT q FROM LsClipScheduleQue q " +
            "WHERE q.status = 'PENDING' AND q.jobType = :jobType " +
            "ORDER BY q.registeredAt ASC")
    List<LsClipScheduleQue> findPendingByJobTypeForUpdate(@Param("jobType") String jobType);

    default Optional<LsClipScheduleQue> findOldestPendingForUpdate(String jobType) {
        List<LsClipScheduleQue> list = findPendingByJobTypeForUpdate(jobType);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    long countByRawSnAndJobType(Long rawSn, String jobType);
}
