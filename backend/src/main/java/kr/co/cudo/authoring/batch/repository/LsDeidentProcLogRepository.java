package kr.co.cudo.authoring.batch.repository;

import kr.co.cudo.authoring.batch.entity.LsDeidentProcLog;
import kr.co.cudo.authoring.common.datasource.ControlRepo;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@ControlRepo
public interface LsDeidentProcLogRepository extends JpaRepository<LsDeidentProcLog, Long> {

    List<LsDeidentProcLog> findAllByDataRawSnOrderByReqDtDesc(Long rawSn);

    /**
     * 영상 목록(Phase 2) — rawSn 집합에 대해 각 DATA_RAW_SN 별 최신 procLog 1행을 단일 IN 쿼리로 조회한다
     * (N+1 회피). "최신"은 PROC_LOG_SN(IDENTITY 증가) 최대값 기준 — 재비식별 등으로 다중행이면 가장 최근
     * 삽입된 1행만 반환한다. rawSns 가 비면 빈 리스트를 반환해 불필요한 쿼리를 막는다.
     */
    default List<LsDeidentProcLog> findLatestByDataRawSnIn(Collection<Long> rawSns) {
        if (rawSns == null || rawSns.isEmpty()) {
            return List.of();
        }
        return findLatestByDataRawSnInInternal(rawSns);
    }

    @Query("SELECT p FROM LsDeidentProcLog p WHERE p.dataRawSn IN :rawSns "
            + "AND p.procLogSn IN (SELECT MAX(p2.procLogSn) FROM LsDeidentProcLog p2 "
            + "WHERE p2.dataRawSn IN :rawSns GROUP BY p2.dataRawSn)")
    List<LsDeidentProcLog> findLatestByDataRawSnInInternal(@Param("rawSns") Collection<Long> rawSns);

    /**
     * Phase 2 보강 (DEV_FIX H-3) — 동일 externalJobId 재인계 시 upsert 대상 행 조회.
     */
    Optional<LsDeidentProcLog> findByExternalJobId(String externalJobId);

    @Query("SELECT p FROM LsDeidentProcLog p WHERE p.dataRawSn = :rawSn AND p.procSttsCd = '" + LsDeidentProcLog.SUCCEEDED + "' ORDER BY p.reqDt DESC")
    List<LsDeidentProcLog> findSuccessHistory(@Param("rawSn") Long rawSn, PageRequest pageable);

    /**
     * Phase 2 (UC018) — KPST 폴링 잡 대상 조회: WAITING/POLLING 상태의 위탁 건.
     */
    List<LsDeidentProcLog> findByPollSttsCdIn(List<String> pollSttsCds);

    default Optional<LsDeidentProcLog> findLatestSuccessByDataRawSn(Long rawSn) {
        if (rawSn == null) return Optional.empty();
        List<LsDeidentProcLog> hits = findSuccessHistory(rawSn, PageRequest.of(0, 1));
        return hits.isEmpty() ? Optional.empty() : Optional.of(hits.get(0));
    }
}
