package kr.co.cudo.authoring.label.repository;

import kr.co.cudo.authoring.common.datasource.ControlRepo;
import kr.co.cudo.authoring.label.entity.LsDeidentReport;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

@ControlRepo
public interface LsDeidentReportRepository extends JpaRepository<LsDeidentReport, Long> {

    /** 영상별 특정 상태(OPEN 등)의 신고 목록 — 재비식별 완료 시 OPEN→RESOLVED 일괄 전이용. */
    List<LsDeidentReport> findAllByRawSnAndSttsCd(Long rawSn, String sttsCd);

    /** 영상별 전체 신고 (관리/조회 용). */
    List<LsDeidentReport> findAllByRawSnOrderByRprtDtDesc(Long rawSn);
}
