package kr.co.cudo.authoring.eventtype.repository;

import kr.co.cudo.authoring.common.datasource.ControlRepo;
import kr.co.cudo.authoring.video.entity.MngExEvntTypeMap;
import kr.co.cudo.authoring.video.entity.MngExEvntTypeMapId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * 관제 이벤트 타입 매핑(MNG_EX_EVNT_TYPE_MAP) 조회 전용 리포지토리.
 *
 * <p>공유 DB(관제) READ 만 수행한다 — {@link ControlRepo} 로 controlEntityManager/controlTransactionManager
 * 에 바인딩된다. MNG_* 테이블은 관제팀 소유이므로 조회만 노출하며 쓰기 메서드를 두지 않는다.
 * PK 는 복합키 {@link MngExEvntTypeMapId}. 파생 쿼리이므로 SQL Injection 위험 없이 자동 바인딩된다.
 */
@ControlRepo
public interface MngExEvntTypeMapRepository extends JpaRepository<MngExEvntTypeMap, MngExEvntTypeMapId> {

    /**
     * 코드 구분(CD_TYPE) 으로 매핑 행을 조회한다. '01'=대분류명행, '02'=카테고리명행.
     */
    List<MngExEvntTypeMap> findByCdType(String cdType);

    /**
     * (EVNT_CLS_CD, EVNT_CTGRY_CD) 로 카테고리명행을 단건 조회한다.
     *
     * <p>이벤트 코드의 (대분류, 카테고리) 로 CD_TYPE='02' 카테고리명행을 찾아 카테고리 한글 라벨
     * (EVNT_NM)을 도출하는 용도(Phase 2 라벨 매핑 크리티컬 경로).
     *
     * <p>복합 PK 5컬럼 중 카테고리명행은 {@code CD_TYPE='02'} + {@code DTL_EVNT=''} +
     * {@code EVNT_TYPE_CD=''} 로 정확히 1행이다. 3컬럼((cdType, cls, ctgry))만 조건으로 걸면 동일
     * (cls, ctgry) 에 상세행(DTL_EVNT/EVNT_TYPE_CD 채워짐)이 섞일 때 다건이 반환되어
     * {@code IncorrectResultSizeDataAccessException} 이 발생하므로, 카테고리명행 5컬럼을 모두 고정해
     * 항상 단건만 조회한다. JPQL 바인딩이라 SQL Injection 위험 없다.
     */
    @Query("SELECT m FROM MngExEvntTypeMap m"
            + " WHERE m.cdType = '02' AND m.dtlEvnt = '' AND m.evntTypeCd = ''"
            + " AND m.evntClsCd = :evntClsCd AND m.evntCtgryCd = :evntCtgryCd")
    Optional<MngExEvntTypeMap> findCategoryLabel(@Param("evntClsCd") String evntClsCd,
                                                 @Param("evntCtgryCd") String evntCtgryCd);
}
