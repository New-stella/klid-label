package kr.co.cudo.authoring.augment.repository;

import kr.co.cudo.authoring.augment.entity.LsDataAugLblMap;
import kr.co.cudo.authoring.common.datasource.ControlRepo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

@ControlRepo
public interface LsDataAugLblMapRepository extends JpaRepository<LsDataAugLblMap, Long> {

    /** 증강(DATA_AUG_SN) 단위 라벨 매핑 목록. */
    List<LsDataAugLblMap> findAllByDataAugSn(Long dataAugSn);

    /** 증강 라벨(DATA_LBL_SN) 이 어떤 증강 결과에 포함됐는지 역추적. */
    List<LsDataAugLblMap> findAllByDataLblSn(Long dataLblSn);

    /**
     * 삭제되는 라벨(LBL_SN) 집합을 참조하는 증강 매핑을 일괄 삭제 — R4 트랙 삭제 시 고아 방지.
     * <p>{@code LS_DATA_AUG_LBL_MAP} 은 DB FK 가 없어 라벨 삭제 시 자동 정리되지 않으므로, 삭제 대상
     * 라벨을 <b>증강물 본체(DATA_LBL_SN, NOT NULL) 또는 원본 참조(ORGNL_DATA_LBL_SN)</b>로 가진 매핑
     * row 를 명시적으로 제거한다(dangling 참조·유령 매핑 방지). 증강 영상이 아니면 매칭 0건 no-op.
     * 파라미터 바인딩({@code :lblSns})만 사용 — 문자열 연결 없음(CWE-89 무관). 빈 컬렉션은 caller 가 가드.
     */
    @Modifying
    @Query("DELETE FROM LsDataAugLblMap m WHERE m.dataLblSn IN :lblSns OR m.orgnlDataLblSn IN :lblSns")
    int deleteByLabelReferencesIn(@Param("lblSns") Collection<Long> lblSns);
}
