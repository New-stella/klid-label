package kr.co.cudo.authoring.label.repository;

import kr.co.cudo.authoring.common.datasource.ControlRepo;
import kr.co.cudo.authoring.label.entity.LsDataLblAttrVal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;

import java.util.Collection;
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

    /**
     * 라벨(LBL_SN) 집합에 속한 모든 속성값을 단일 IN 쿼리로 일괄 조회.
     * <p>해상도 변경(Phase 3) 시 원본 라벨 → 신규 라벨 속성값(LS_DATA_LBL_ATTR_VAL) 복사용 — N+1 회피.
     * 빈 컬렉션 입력 시 빈 결과 반환 (default).
     */
    List<LsDataLblAttrVal> findByLblSnIn(Collection<Long> lblSns);

    /**
     * 라벨(LBL_SN) 집합의 속성값 일괄 삭제 (R1 v1.14 — 비식별 신고 시 고아 방지 선삭제).
     * 빈 컬렉션 입력 시 no-op.
     */
    @Modifying
    void deleteByLblSnIn(Collection<Long> lblSns);
}
