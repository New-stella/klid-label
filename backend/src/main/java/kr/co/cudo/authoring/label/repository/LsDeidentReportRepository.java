package kr.co.cudo.authoring.label.repository;

import kr.co.cudo.authoring.common.datasource.ControlRepo;
import kr.co.cudo.authoring.label.entity.LsDeidentReport;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

@ControlRepo
public interface LsDeidentReportRepository extends JpaRepository<LsDeidentReport, Long> {

    /**
     * 신고 상태(REPORT_STTS_CD) 기준 페이징 조회 (G-1 — REVIEWER 신고 관리 목록).
     * 정렬은 caller 가 Pageable 로 지정 (기본 reportDt DESC).
     */
    Page<LsDeidentReport> findByReportSttsCd(String reportSttsCd, Pageable pageable);

    /**
     * 사용자 신고 REPORT_STTS_CD 기준 조회 (Phase 3 - OPEN 신고 일괄 RESOLVED 전이용).
     */
    List<LsDeidentReport> findAllByDataRawSnAndReportSttsCd(Long rawSn, String reportSttsCd);

    /**
     * 영상별 사용자 신고 이력 (REPORT_DT DESC 정렬). FE 신고 이력 표시용.
     */
    List<LsDeidentReport> findAllByDataRawSnOrderByReportDtDesc(Long rawSn);

    // ============================================================
    // 기존 호출자 호환 (테스트가 사용하던 별칭)
    // ============================================================

    default List<LsDeidentReport> findAllByRawSnOrderByRprtDtDesc(Long rawSn) {
        return findAllByDataRawSnOrderByReportDtDesc(rawSn);
    }
}
