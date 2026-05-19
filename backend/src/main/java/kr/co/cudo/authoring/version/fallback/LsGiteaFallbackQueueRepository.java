package kr.co.cudo.authoring.version.fallback;

import kr.co.cudo.authoring.common.datasource.ControlRepo;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Phase 3 — Gitea fallback 큐 Repository.
 *
 * <p>{@code klid_system} (Control) DB 의 {@code LS_GITEA_FALLBACK_QUEUE} 에 매핑된다.
 * 듀얼 데이터소스 환경에서 Control EntityManager 가 본 인터페이스를 스캔하도록 {@link ControlRepo} 어노테이션 필수.
 */
@ControlRepo
@Repository
public interface LsGiteaFallbackQueueRepository extends JpaRepository<LsGiteaFallbackQueue, Long> {

    Optional<LsGiteaFallbackQueue> findByIdempotencyKey(String idempotencyKey);

    /** 재시도 대상 polling — STATUS=PENDING + NEXT_RETRY_AT <= now. */
    List<LsGiteaFallbackQueue> findByStatusAndNextRetryAtLessThanEqualOrderByNextRetryAtAsc(
            String status, LocalDateTime cutoff, Pageable pageable);

    long countByStatus(String status);

    /**
     * 큐 깊이 측정용 — 다중 STATUS 집계.
     * MEDIUM-3 (CWE-770) 큐 상한 검증에서 PENDING + RETRYING 합산에 사용.
     */
    long countByStatusIn(Collection<String> statuses);

    /**
     * HIGH-1 (CWE-362) — Read-Then-Write race 차단용 원자 CAS UPDATE.
     *
     * <p>{@code STATUS='PENDING'} 인 행만 {@code STATUS='RETRYING'} 으로 전이시킨다.
     * 멀티 인스턴스/Quartz 동시 실행 시 한 인스턴스만 UPDATE 성공(=1) 하므로 중복 처리 차단.
     *
     * @return 영향받은 행 수 (1 = claim 성공, 0 = 다른 인스턴스가 이미 처리 중)
     */
    @Modifying
    @Query("UPDATE LsGiteaFallbackQueue q SET q.status = 'RETRYING', q.updatedAt = :now " +
            "WHERE q.queueSn = :sn AND q.status = 'PENDING'")
    int claimAtomically(@Param("sn") Long sn, @Param("now") LocalDateTime now);
}
