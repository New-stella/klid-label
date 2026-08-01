package kr.co.cudo.authoring.augment.repository;

import kr.co.cudo.authoring.augment.entity.LsDataAugRvw;
import kr.co.cudo.authoring.common.datasource.ControlRepo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@ControlRepo
public interface LsDataAugRvwRepository extends JpaRepository<LsDataAugRvw, Long> {

    /**
     * 잡 카드 completedAt 산출용 — 여러 DATA_AUG_SN 의 검수 row 를 일괄 조회(N+1 회피).
     * 호출 측에서 DATA_AUG_SN 별 최신 RVW_DT 를 집계한다. 파라미터 바인딩만 사용(CWE-89).
     */
    @Query("SELECT r FROM LsDataAugRvw r WHERE r.dataAugSn IN :dataAugSns")
    List<LsDataAugRvw> findByDataAugSnIn(@Param("dataAugSns") Collection<Long> dataAugSns);

    /**
     * 그 증강의 <b>최신 검수 row</b> — 판정은 {@link LsDataAugRvw#RECENCY_ORDER} 단일 정의를 따른다.
     *
     * <h3>왜 {@code ORDER BY … LIMIT 1} 파생 쿼리가 아닌가 (DEV_FIX MEDIUM ①)</h3>
     * <p>구 구현은 {@code findFirstByDataAugSnOrderByRegDtDesc} 라는 <b>SQL 로 표현된 두 번째 정의</b>
     * 였고, 결과 조회 서비스는 메모리에서 {@code RVW_DT} 축으로 <b>또 다른 정의</b>를 갖고 있었다.
     * 이 테이블은 {@code DATA_AUG_SN} 유니크가 없어 중복 행이 공존할 수 있으므로, 두 정의가 서로
     * 다른 행을 골라 "화면은 복구 가능이라 하는데 복구 API 는 404" 가 성립했다.
     *
     * <p>그래서 정렬을 SQL 에서 걷어내고 <b>같은 비교자</b>로 고른다 — 한 {@code dataAugSn} 의 검수
     * 행은 많아야 몇 건이라 전량을 읽어도 비용이 같고(쿼리도 여전히 <b>1회</b>), 규칙이 자바 한 곳에만
     * 존재하게 된다. 반환 엔티티는 영속 컨텍스트 관리 대상이라 호출부의 {@code reopen()} 등
     * 더티체킹도 종전과 동일하게 동작한다.
     */
    default Optional<LsDataAugRvw> findLatestByDataAugSn(Long dataAugSn) {
        return LsDataAugRvw.latestOf(findByDataAugSnIn(List.of(dataAugSn)));
    }

    /** 영상 단위(DATA_RAW_SN) 검수 상태별 조회. */
    List<LsDataAugRvw> findAllByDataRawSnAndRvwSttsCd(Long dataRawSn, String rvwSttsCd);
}
