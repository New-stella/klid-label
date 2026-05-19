package kr.co.cudo.authoring.batch.repository;

import kr.co.cudo.authoring.batch.entity.LsDeidentProcLog;
import kr.co.cudo.authoring.common.datasource.ControlRepo;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

@ControlRepo
public interface LsDeidentProcLogRepository extends JpaRepository<LsDeidentProcLog, Long> {

    List<LsDeidentProcLog> findAllByDataRawSnOrderByReqDtDesc(Long rawSn);

    /**
     * Phase 2 보강 (DEV_FIX H-3) — 동일 externalJobId 재인계 시 upsert 대상 행 조회.
     */
    Optional<LsDeidentProcLog> findByExternalJobId(String externalJobId);

    @Query("SELECT p FROM LsDeidentProcLog p WHERE p.dataRawSn = :rawSn AND p.procSttsCd = '" + LsDeidentProcLog.SUCCEEDED + "' ORDER BY p.reqDt DESC")
    List<LsDeidentProcLog> findSuccessHistory(@Param("rawSn") Long rawSn, PageRequest pageable);

    default Optional<LsDeidentProcLog> findLatestSuccessByDataRawSn(Long rawSn) {
        if (rawSn == null) return Optional.empty();
        List<LsDeidentProcLog> hits = findSuccessHistory(rawSn, PageRequest.of(0, 1));
        return hits.isEmpty() ? Optional.empty() : Optional.of(hits.get(0));
    }
}
