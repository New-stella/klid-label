package kr.co.cudo.authoring.video.repository;

import kr.co.cudo.authoring.common.datasource.ControlRepo;
import kr.co.cudo.authoring.video.entity.LsResolutionLblMap;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 해상도 파생영상 라벨 매핑(LS_RESOLUTION_LBL_MAP) 리포지토리 — Phase 2.
 */
@ControlRepo
public interface LsResolutionLblMapRepository extends JpaRepository<LsResolutionLblMap, Long> {

    List<LsResolutionLblMap> findByResExportSn(Long resExportSn);
}
