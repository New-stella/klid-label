package kr.co.cudo.authoring.portal.repository;

import kr.co.cudo.authoring.common.datasource.ControlRepo;
import kr.co.cudo.authoring.portal.entity.LsPortalUserLabel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

@ControlRepo
public interface LsPortalUserLabelRepository extends JpaRepository<LsPortalUserLabel, Long> {

    List<LsPortalUserLabel> findByPortalUserNoAndSrcRawSnOrderByRegDtDesc(
            String portalUserNo, Long srcRawSn);

    List<LsPortalUserLabel> findByPortalUserNoAndSrcDataSrcSnOrderByRegDtDesc(
            String portalUserNo, Long srcDataSrcSn);
}
