package kr.co.cudo.authoring.label.repository;

import kr.co.cudo.authoring.common.datasource.ControlRepo;
import kr.co.cudo.authoring.label.entity.LsDeidentReport;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

@ControlRepo
public interface LsDeidentReportRepository extends JpaRepository<LsDeidentReport, Long> {

    /**
     * 시스템 비식별 트랜잭션 PROC_STTS_CD 기준 조회.
     */
    List<LsDeidentReport> findAllByDataRawSnAndProcSttsCd(Long rawSn, String procSttsCd);

    /**
     * 사용자 신고 REPORT_STTS_CD 기준 조회 (Phase 3 - OPEN 신고 일괄 RESOLVED 전이용).
     */
    List<LsDeidentReport> findAllByDataRawSnAndReportSttsCd(Long rawSn, String reportSttsCd);

    /**
     * 영상별 시스템 트랜잭션 이력 (REQ_DT DESC 정렬).
     */
    List<LsDeidentReport> findAllByDataRawSnOrderByReqDtDesc(Long rawSn);

    /**
     * 영상별 사용자 신고 이력 (REPORT_DT DESC 정렬). FE 신고 이력 표시용.
     */
    List<LsDeidentReport> findAllByDataRawSnOrderByReportDtDesc(Long rawSn);

    /**
     * 영상의 가장 최근 비식별 성공 트랜잭션 1건.
     * <p>FfmpegFrameExtractor.extractBoth 가 비식별 영상 경로를 결정할 때 사용.
     * @return PROC_STTS_CD='SUCCEEDED' 중 REQ_DT 가 가장 최근인 1건. 없으면 empty.
     */
    @Query("SELECT r FROM LsDeidentReport r " +
            "WHERE r.dataRawSn = :rawSn AND r.procSttsCd = '" + LsDeidentReport.PROC_SUCCESS + "' " +
            "ORDER BY r.reqDt DESC")
    List<LsDeidentReport> findSuccessHistory(@Param("rawSn") Long rawSn, PageRequest pageable);

    default Optional<LsDeidentReport> findLatestSuccessByDataRawSn(Long rawSn) {
        if (rawSn == null) {
            return Optional.empty();
        }
        List<LsDeidentReport> hits = findSuccessHistory(rawSn, PageRequest.of(0, 1));
        return hits.isEmpty() ? Optional.empty() : Optional.of(hits.get(0));
    }

    // ============================================================
    // 기존 호출자 호환 (테스트가 사용하던 별칭)
    // ============================================================

    default List<LsDeidentReport> findAllByRawSnAndSttsCd(Long rawSn, String sttsCd) {
        // 우선 사용자 신고 STTS 컬럼으로 조회 후, 매칭이 없으면 시스템 트랜잭션 컬럼 조회.
        List<LsDeidentReport> reports = findAllByDataRawSnAndReportSttsCd(rawSn, sttsCd);
        if (!reports.isEmpty()) {
            return reports;
        }
        return findAllByDataRawSnAndProcSttsCd(rawSn, sttsCd);
    }

    default List<LsDeidentReport> findAllByRawSnOrderByRprtDtDesc(Long rawSn) {
        return findAllByDataRawSnOrderByReportDtDesc(rawSn);
    }
}
