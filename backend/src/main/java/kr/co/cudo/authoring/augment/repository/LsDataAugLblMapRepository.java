package kr.co.cudo.authoring.augment.repository;

import kr.co.cudo.authoring.augment.entity.LsDataAugLblMap;
import kr.co.cudo.authoring.common.datasource.ControlRepo;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

@ControlRepo
public interface LsDataAugLblMapRepository extends JpaRepository<LsDataAugLblMap, Long> {

    /** 증강(DATA_AUG_SN) 단위 라벨 매핑 목록. */
    List<LsDataAugLblMap> findAllByDataAugSn(Long dataAugSn);

    /** 증강 라벨(DATA_LBL_SN) 이 어떤 증강 결과에 포함됐는지 역추적. */
    List<LsDataAugLblMap> findAllByDataLblSn(Long dataLblSn);
}
