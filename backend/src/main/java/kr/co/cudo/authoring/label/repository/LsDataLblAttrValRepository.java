package kr.co.cudo.authoring.label.repository;

import kr.co.cudo.authoring.common.datasource.ControlRepo;
import kr.co.cudo.authoring.label.entity.LsDataLblAttrVal;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 객체별 속성값 Repository (klid_system 공유 DB — Control 데이터소스).
 *
 * <p>(LBL_SN, ATTR_ID) UNIQUE — upsert 키.
 */
@ControlRepo
public interface LsDataLblAttrValRepository extends JpaRepository<LsDataLblAttrVal, Long> {

    /** 객체별 속성값 전체 — ATTR_ID ASC. */
    List<LsDataLblAttrVal> findByLblSnOrderByAttrIdAsc(Long lblSn);

    /** upsert 키 조회. */
    Optional<LsDataLblAttrVal> findByLblSnAndAttrId(Long lblSn, Long attrId);
}
