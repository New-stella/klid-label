package kr.co.cudo.authoring.aiserver.repository;

import kr.co.cudo.authoring.aiserver.entity.LsAiSrvr;
import kr.co.cudo.authoring.common.datasource.ControlRepo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

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

    /** 원장 전체 — 목록 화면용. 순서를 못 박아 화면이 새로고침마다 흔들리지 않게 한다. */
    List<LsAiSrvr> findAllByOrderBySrvrIdAsc();

    /** 한 유형만 — 목록 화면의 유형 필터. */
    List<LsAiSrvr> findBySrvrTypeCdOrderBySrvrIdAsc(LsAiSrvr.SrvrType srvrTypeCd);

    /**
     * <b>관리 창구 전용</b> — 가용 노드를 강등하되, 그것이 <b>그 유형의</b> 마지막 가용 노드면
     * 아무것도 하지 않는다. [@design API-229] [@design AC-1091]
     *
     * <h3>왜 유형별인가 — {@link #demoteIfNotLastAvailable} 와 무엇이 다른가</h3>
     * <p>위쪽 형제는 <b>원장 전체</b>에서 가용 수를 센다. 유형이 하나뿐이던 시절에는 같은 판정이었지만,
     * 추론과 시계열이 함께 등록되면 <b>추론이 하나만 남았는데 시계열이 있어서 통과</b>한다 — 그러면
     * 추론 위탁이 통째로 멈춘다. 장비를 고르는 축이 유형별이므로 보호하는 축도 유형별이어야 한다.
     *
     * <p>⚠ <b>상태점검 배치는 이 메서드를 부르지 않는다.</b> 배치의 이용불가 전이는 사람의 결정이
     * 아니라 관측이고, 사람의 조작에만 거는 이 보호를 관측에까지 얹으면 죽은 장비로 계속 보내게 된다.
     * 그래서 배치는 종전대로 전체 축 형제를 그대로 쓴다(두 경로가 다른 것이 의도다).
     *
     * <h3>조건부 UPDATE <b>만으로도</b> 부족하다 — 그래서 잠금 조회를 앞에 붙인다</h3>
     * <p>서로 다른 행을 갱신하면 행 잠금이 부딪히지 않고, 읽기 커밋 격리에서는 각자의 스냅샷이
     * 여전히 「가용 2」를 보여 준다. 그래서 UPDATE 앞에 <b>그 유형의 가용 행 전체를 잠그는 조회</b>를
     * CTE 로 둔다. 먼저 온 쪽이 두 행을 모두 잠그고, 뒤에 온 쪽은 그 잠금에서 기다렸다가 <b>갱신된
     * 행으로 조건을 다시 평가</b>해 「가용 1」을 보고 스스로 물러난다.
     *
     * <p>출발 상태를 {@code AVAILABLE} 로 못 박은 것도 규칙이다 — 가용에서 출발하는 전이는 셋 다
     * 허용이므로, 이 조건 하나가 금지 전이까지 함께 막는다.
     *
     * @return 영향 행수. <b>0 이면 거부</b>다(마지막 가용 노드이거나 그 사이 상태가 바뀌었다)
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            WITH available AS (
                SELECT SRVR_ID FROM LS_AI_SRVR
                 WHERE SRVR_STTS_CD = 'AVAILABLE'
                   AND SRVR_TYPE_CD = :srvrTypeCd
                 FOR UPDATE
            )
            UPDATE LS_AI_SRVR
               SET SRVR_STTS_CD = :next,
                   MDFR_ID = :actorId,
                   MDFCN_DT = :now
             WHERE SRVR_ID = :srvrId
               AND SRVR_TYPE_CD = :srvrTypeCd
               AND SRVR_STTS_CD = 'AVAILABLE'
               AND (SELECT count(*) FROM available) > 1
            """, nativeQuery = true)
    int demoteIfNotLastAvailableOfType(@Param("srvrId") String srvrId,
                                       @Param("srvrTypeCd") String srvrTypeCd,
                                       @Param("next") String next,
                                       @Param("actorId") String actorId,
                                       @Param("now") LocalDateTime now);

    /**
     * 가용이 <b>아닌</b> 노드의 상태를 바꾼다 — 출발 상태를 조건으로 못 박는다. [@design API-229]
     *
     * <p>가용 노드를 줄이지 않으므로 마지막 노드 보호와 무관하다. 그래도 엔티티 필드를 직접 고치지
     * 않고 조건부 UPDATE 로 두는 이유는, 읽은 뒤 쓰는 사이에 상태가 바뀌면 <b>이미 지나간 상태에서
     * 출발하는 전이</b>가 성립해 버리기 때문이다. 0 행이면 그 사이 누가 바꾼 것이다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE LS_AI_SRVR
               SET SRVR_STTS_CD = :next,
                   MDFR_ID = :actorId,
                   MDFCN_DT = :now
             WHERE SRVR_ID = :srvrId
               AND SRVR_STTS_CD = :current
            """, nativeQuery = true)
    int transitionStatusFrom(@Param("srvrId") String srvrId,
                             @Param("current") String current,
                             @Param("next") String next,
                             @Param("actorId") String actorId,
                             @Param("now") LocalDateTime now);

    /**
     * <b>관리 창구 전용</b> — 노드를 지우되, 그것이 그 유형의 마지막 가용 노드면 지우지 않는다.
     * [@design API-230] [@design AC-1091]
     *
     * <p>가용이 아닌 노드는 언제든 지울 수 있다(이미 그 유형의 위탁을 받고 있지 않다). 잠금 CTE 를
     * 함께 두는 이유는 강등과 같다 — 세어 보고 지우면 두 관리자가 서로 다른 노드를 동시에 지울 때
     * 둘 다 통과한다.
     *
     * <p>⚠ 부하 관측 행({@code LS_AI_SRVR_USG})은 외래키 {@code ON DELETE CASCADE} 로 함께 사라진다.
     * 반면 영상 배정 행({@code LS_AI_SRVR_ALTMNT})은 {@code ON DELETE RESTRICT} 라 <b>남아 있으면
     * 이 문장이 무결성 위반으로 실패</b>한다 — 호출측이 먼저 걸러 409 로 답한다.
     *
     * @return 영향 행수. 0 이면 마지막 가용 노드라 거부됐거나 이미 없는 노드다
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            WITH available AS (
                SELECT SRVR_ID FROM LS_AI_SRVR
                 WHERE SRVR_STTS_CD = 'AVAILABLE'
                   AND SRVR_TYPE_CD = :srvrTypeCd
                 FOR UPDATE
            )
            DELETE FROM LS_AI_SRVR
             WHERE SRVR_ID = :srvrId
               AND SRVR_TYPE_CD = :srvrTypeCd
               AND (SRVR_STTS_CD <> 'AVAILABLE' OR (SELECT count(*) FROM available) > 1)
            """, nativeQuery = true)
    int deleteIfNotLastAvailableOfType(@Param("srvrId") String srvrId,
                                       @Param("srvrTypeCd") String srvrTypeCd);
}
