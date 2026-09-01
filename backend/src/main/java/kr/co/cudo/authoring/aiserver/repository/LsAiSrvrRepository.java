package kr.co.cudo.authoring.aiserver.repository;

import kr.co.cudo.authoring.aiserver.entity.LsAiSrvr;
import kr.co.cudo.authoring.common.datasource.ControlRepo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * AI 서버 원장 저장소 (Control 데이터소스). [@design ADR-057] [@req R6] [@req R10]
 *
 * <p>비즈니스 로직 금지 — 조회/저장과 <b>원자 전이</b>만 둔다.
 */
@ControlRepo
public interface LsAiSrvrRepository extends JpaRepository<LsAiSrvr, String> {

    /**
     * 가용 노드를 <b>강등</b>한다 — 단, 그것이 마지막 가용 노드면 아무것도 하지 않는다.
     *
     * <h3>왜 조회 후 UPDATE 가 아닌가</h3>
     * <p>가용 노드가 0이 되면 AI 기능 전체가 멈춘다. "세어 보고 1보다 크면 내린다"로 만들면 두
     * 관리자가 <b>서로 다른</b> 노드를 동시에 내릴 때 둘 다 세는 시점에 2를 보고 둘 다 통과한다.
     *
     * <h3>조건부 UPDATE <b>만으로도</b> 부족하다 — 그래서 잠금 조회를 앞에 붙인다</h3>
     * <p>서로 다른 행을 갱신하면 행 잠금이 부딪히지 않고, 읽기 커밋 격리에서는 각자의 스냅샷이
     * 여전히 "가용 2"를 보여 준다. 그래서 UPDATE 앞에 <b>가용 행 전체를 잠그는 조회</b>를 CTE 로
     * 둔다. 먼저 온 쪽이 두 행을 모두 잠그고, 뒤에 온 쪽은 그 잠금에서 기다렸다가 <b>갱신된 행으로
     * 조건을 다시 평가</b>해 "가용 1"을 보고 스스로 물러난다.
     *
     * <p>출발 상태를 {@code AVAILABLE} 로 못 박은 것도 규칙이다 — 가용에서 출발하는 전이는 셋 다
     * 허용이므로, 이 조건 하나가 금지 전이(이용불가→정비중 · 정비중→이용불가)까지 함께 막는다.
     *
     * @param srvrId 강등 대상
     * @param next   전이 목표 상태명. 호출측이 상태 enum 의 이름을 넘긴다(문자열 연결 금지 —
     *               파라미터 바인딩이다)
     * @return 영향 행수. <b>0 이면 거부</b>다 — 호출측이 "마지막 가용 노드"(409) 인지
     *         "없는 노드"(404) 인지 가려 응답한다
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            WITH available AS (
                SELECT SRVR_ID FROM LS_AI_SRVR WHERE SRVR_STTS_CD = 'AVAILABLE' FOR UPDATE
            )
            UPDATE LS_AI_SRVR
               SET SRVR_STTS_CD = :next
             WHERE SRVR_ID = :srvrId
               AND SRVR_STTS_CD = 'AVAILABLE'
               AND (SELECT count(*) FROM available) > 1
            """, nativeQuery = true)
    int demoteIfNotLastAvailable(@Param("srvrId") String srvrId, @Param("next") String next);

    /**
     * 이용불가 노드를 <b>가용으로 되돌린다</b> — 연속 성공이 복귀 임계에 닿았을 때만 호출한다.
     *
     * <h3>왜 여기에 두는가</h3>
     * <p>강등과 달리 복귀는 가용 노드를 <b>줄이지 않으므로</b> 마지막 노드 보호와 무관하다. 그래도
     * 엔티티 필드를 직접 바꾸지 않고 조건부 UPDATE 로 두는 이유는 두 가지다 — (1)출발 상태를
     * {@code UNAVAILABLE} 로 못 박아 <b>금지 전이</b>(정비중→가용을 배치가 임의로 하는 것)를 SQL
     * 한 줄이 막고, (2)두 WAS 가 같은 틱에 겹쳐도 한쪽만 실제로 갱신한다.
     *
     * <p>연속 카운터 둘을 <b>같은 문장에서</b> 0으로 되돌린다. 복귀 직후 카운터가 남아 있으면 다음
     * 실패 한 번에 임계를 넘겨 곧바로 다시 내려간다.
     *
     * @return 영향 행수. 0 이면 이미 다른 노드가 되돌렸거나 그 사이 상태가 바뀐 것이다(정상)
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE LS_AI_SRVR
               SET SRVR_STTS_CD = 'AVAILABLE',
                   CHCK_FAIL_NOCS = 0,
                   CHCK_SCS_NOCS = 0
             WHERE SRVR_ID = :srvrId
               AND SRVR_STTS_CD = 'UNAVAILABLE'
            """, nativeQuery = true)
    int promoteIfUnavailable(@Param("srvrId") String srvrId);
}
