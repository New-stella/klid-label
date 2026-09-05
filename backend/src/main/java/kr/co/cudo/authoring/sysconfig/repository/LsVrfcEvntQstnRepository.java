package kr.co.cudo.authoring.sysconfig.repository;

import kr.co.cudo.authoring.common.datasource.ControlRepo;
import kr.co.cudo.authoring.sysconfig.entity.LsVrfcEvntQstn;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * 검증 이벤트 유형별 질문 리포지토리 (LS_VRFC_EVNT_QSTN). [design: ERD-033]
 *
 * <p>조회 순서는 <b>언제나 정렬순서 오름차순</b>이다 — 「첫 번째 질문」이 조회마다 흔들리면 마킹을
 * 거치지 않는 경로가 채우는 기본값이 실행마다 달라진다. DB 의
 * {@code UK_LS_VRFC_EVNT_QSTN_TYPE_SORT}(유형코드, 정렬순서) 유일 제약이 그 순서의 유일성을 받친다.
 *
 * <p>파생 쿼리 + 파라미터 바인딩만 쓴다(CWE-89 표면 없음).
 */
@ControlRepo
public interface LsVrfcEvntQstnRepository extends JpaRepository<LsVrfcEvntQstn, Long> {

    /** 한 유형의 질문 전체 — 정렬순서 오름차순. */
    List<LsVrfcEvntQstn> findByVrfcEvntTypeCdOrderBySortSeqAsc(String vrfcEvntTypeCd);

    /**
     * 전체 질문 — (유형코드, 정렬순서) 오름차순. 목록 조회가 유형별로 N+1 쿼리를 돌지 않도록
     * <b>한 번에</b> 읽어 메모리에서 유형별로 묶는다(행수가 코드 체계 규모라 성립한다).
     */
    List<LsVrfcEvntQstn> findAllByOrderByVrfcEvntTypeCdAscSortSeqAsc();

    /**
     * ★ 그 유형의 <b>첫 번째 질문</b> — 정렬순서 최선두 1건.
     *
     * <p>조달 판정기({@code VerificationEventQuestionResolver}) 전용이며 <b>다른 곳에서 직접 부르지
     * 말 것</b>. 「첫 번째」의 해석이 여러 곳으로 흩어지면 그것이 곧 두 번째 진실원이 된다.
     */
    Optional<LsVrfcEvntQstn> findFirstByVrfcEvntTypeCdOrderBySortSeqAsc(String vrfcEvntTypeCd);

    /**
     * 한 유형의 질문을 전부 지운다 — <b>전체 교체 저장의 1단계</b>.
     *
     * <p>벌크 삭제(JPQL)라 영속성 컨텍스트를 우회해 <b>즉시 DB 에 나간다</b>. 그래야 같은 트랜잭션의
     * 2단계 INSERT 가 {@code (유형코드, 정렬순서)} 유일 제약과 충돌하지 않는다 — 지연 flush 에 맡기면
     * Hibernate 가 INSERT 를 먼저 내보내 순서 재배치에서 제약 위반이 난다.
     *
     * <p>{@code flushAutomatically} 로 이전 변경을 먼저 밀어내고, {@code clearAutomatically} 로
     * 삭제된 행이 1차 캐시에 유령으로 남지 않게 한다.
     *
     * @return 지운 행 수
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM LsVrfcEvntQstn q WHERE q.vrfcEvntTypeCd = :vrfcEvntTypeCd")
    int deleteByVrfcEvntTypeCd(@Param("vrfcEvntTypeCd") String vrfcEvntTypeCd);
}
