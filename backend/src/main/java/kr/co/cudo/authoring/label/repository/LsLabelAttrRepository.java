package kr.co.cudo.authoring.label.repository;

import kr.co.cudo.authoring.common.datasource.ControlRepo;
import kr.co.cudo.authoring.label.entity.LsLabelAttr;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 라벨 속성 정의 Repository (klid_system 공유 DB — Control 데이터소스).
 *
 * <p>비즈니스 로직 금지 — 조회/저장 메서드만 정의한다.
 */
@ControlRepo
public interface LsLabelAttrRepository extends JpaRepository<LsLabelAttr, Long> {

    /** 라벨 내 활성(USE_YN='Y') 속성을 SORT_SEQ ASC 로 조회. */
    List<LsLabelAttr> findByLabelIdAndUseYnOrderBySortSeqAsc(Long labelId, String useYn);

    /** create 검증용 — 동일 라벨의 동일 이름(활성/비활성 무관) 존재 여부. */
    boolean existsByLabelIdAndAttrNm(Long labelId, String attrNm);

    /** update 검증용 — 자기 자신을 제외한 동일 이름 존재 여부. */
    boolean existsByLabelIdAndAttrNmAndAttrIdNot(Long labelId, String attrNm, Long attrId);
}
