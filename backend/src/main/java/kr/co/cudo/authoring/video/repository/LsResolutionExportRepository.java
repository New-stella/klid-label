package kr.co.cudo.authoring.video.repository;

import kr.co.cudo.authoring.common.datasource.ControlRepo;
import kr.co.cudo.authoring.video.entity.LsResolutionExport;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 해상도 변경 산출 추적 레코드(LS_RESOLUTION_EXPORT) 리포지토리 — Phase 1.
 */
@ControlRepo
public interface LsResolutionExportRepository extends JpaRepository<LsResolutionExport, Long> {

    boolean existsByDataRawSnAndTargetResCd(Long dataRawSn, String targetResCd);
}
