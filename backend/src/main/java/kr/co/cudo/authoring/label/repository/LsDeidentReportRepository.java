package kr.co.cudo.authoring.label.repository;

import kr.co.cudo.authoring.common.datasource.ControlRepo;
import kr.co.cudo.authoring.label.entity.LsDeidentReport;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

@ControlRepo
public interface LsDeidentReportRepository extends JpaRepository<LsDeidentReport, Long> {

    List<LsDeidentReport> findAllByDataRawSnAndProcSttsCd(Long rawSn, String sttsCd);

    List<LsDeidentReport> findAllByDataRawSnOrderByReqDtDesc(Long rawSn);
}
