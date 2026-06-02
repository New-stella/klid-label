package kr.co.cudo.authoring.controlnotify.fallback;

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
 * Phase 2 — 관제서버 통지 fallback 큐 Repository.
 *
 * <p>{@code klid_system} (Control) DB 의 {@code LS_CONTROL_NOTIFY_FALLBACK} 에 매핑된다.
 */
@ControlRepo
@Repository
public interface LsControlNotifyFallbackRepository extends JpaRepository<LsControlNotifyFallback, Long> {

    Optional<LsControlNotifyFallback> findByIdmpKey(String idmpKey);

    /** 재시도 대상 polling — STTS_CD=PENDING + NEXT_RTRY_DT <= now. */
    List<LsControlNotifyFallback> findBySttsCdAndNextRtryDtLessThanEqualOrderByNextRtryDtAsc(
            String sttsCd, LocalDateTime cutoff, Pageable pageable);

    long countBySttsCd(String sttsCd);

    /**
     * 큐 깊이 측정용 — 다중 STTS_CD 집계.
     */
    long countBySttsCdIn(Collection<String> statuses);

    /**
     * CWE-362 — Read-Then-Write race 차단용 원자 CAS UPDATE.
     *
     * <p>{@code STATUS='PENDING'} 인 행만 {@code STATUS='RETRYING'} 으로 전이시킨다.
     *
     * @return 영향받은 행 수 (1 = claim 성공, 0 = 다른 인스턴스가 이미 처리 중)
     */
    @Modifying
    @Query("UPDATE LsControlNotifyFallback q SET q.sttsCd = 'RETRYING', q.mdfcnDt = :now " +
            "WHERE q.queueSn = :sn AND q.sttsCd = 'PENDING'")
    int claimAtomically(@Param("sn") Long sn, @Param("now") LocalDateTime now);
}
