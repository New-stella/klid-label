package kr.co.cudo.authoring.label.repository;

import kr.co.cudo.authoring.common.datasource.ControlRepo;
import kr.co.cudo.authoring.label.entity.LsDataLblAttrVal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
     * 라벨(LBL_SN) 집합의 속성값 일괄 삭제 (R1 v1.14 비식별 신고 · R4 트랙 삭제 — 고아 방지 선삭제).
     *
     * <p><b>즉시 bulk DELETE(JPQL)</b>로 구현한다 — 파생 delete({@code deleteByLblSnIn} 자동 파생)는
     * SELECT-후-엔티티별 {@code em.remove()} 로 삭제를 <b>영속성 컨텍스트에 큐잉</b>만 하고, 뒤이어
     * 실행되는 <b>부모(LS_DATA_LBL) bulk 삭제</b>가 auto-flush 대상 테이블(LS_DATA_LBL)만 flush 하므로
     * ATTR_VAL 삭제가 DB 에 반영되지 않아 FK 위반(500)이 발생한다. 명시적 {@code @Query} 로 즉시 SQL
     * 을 발행해 이 순서 의존 버그를 제거한다(N+1 도 함께 해소). 빈 컬렉션 입력 시 no-op.
     * 파라미터 바인딩({@code :lblSns})만 사용 — 문자열 연결 없음(CWE-89 무관).
     */
    @Modifying
    @Query("DELETE FROM LsDataLblAttrVal a WHERE a.lblSn IN :lblSns")
    void deleteByLblSnIn(@Param("lblSns") Collection<Long> lblSns);
}
