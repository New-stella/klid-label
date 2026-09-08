package kr.co.cudo.authoring.aiserver.repository;

import kr.co.cudo.authoring.aiserver.entity.AiSrvrStatus;
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
     * <h3>⚠ 운영 경로에서 이 메서드를 부르는 곳은 <b>없다</b> — 다시 배선하지 말 것</h3>
     * <p>관리 창구는 유형별 형제({@link #demoteIfNotLastAvailableOfType})를 쓰고, <b>상태점검 배치는
     * {@link #demoteByHealthCheck} 를 쓴다</b>. 배치가 한때 이것을 불렀는데, 그 보호가 관측까지 막아
     * <b>죽은 장비가 「가용」으로 남아</b> 운영자가 화면에서 「한 대는 살아 있다」로 읽는 동안 AI 기능이
     * 이미 멈춰 있는 상태가 됐다(2026-09-07 실측 — gpu01·gpu02 가 같은 시각에 둘 다 응답하지 않는데
     * gpu01 만 가용). 그 보호는 <b>사람의 조작</b>에만 거는 것이다. [@design AC-1091] [@design AC-1093]
     *
     * <p>남겨 두는 이유는 잠금 CTE + 조건부 UPDATE 라는 <b>동시성 골격</b>과 그 근거(아래 두 절)를
     * 보존하기 위해서다 — 유형별 형제가 같은 골격을 쓴다. 지우려면 그 지식이 형제 쪽에 온전히
     * 남아 있는지 먼저 확인할 것.
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
     * <b>상태점검 배치 전용</b> — 가용 노드를 강등한다. <b>마지막 하나라도 내린다</b>.
     * [@design ADR-057] [@design AC-1093] [@design AC-1091]
     *
     * <h3>왜 마지막 노드 보호를 걸지 않는가</h3>
     * <p>그 보호는 <b>사람이 관리 화면에서 내리는 조작</b>에 거는 것이다. 상태점검이 내리는 이용불가는
     * 조작이 아니라 <b>관측</b>이며, 실제 장애를 소프트웨어로 부정할 수 없다. 막으면 죽은 장비가
     * 「가용」으로 남아 그리로 계속 보내게 되고, 화면은 「한 대는 살아 있다」고 거짓을 말한다.
     *
     * <p>가용이 0이 되는 것 자체를 이 쿼리가 막지 않는다. 그 뒤에 무슨 일이 일어나는지의 판정은
     * {@code AiSrvrHealthTxService.warnIfTypeExhausted} javadoc 이 소유한다(여기 복제하지 않는다).
     * 「내려도 갈 곳이 없다」는 옛 근거는 어느 축에서도 강등을 막을 이유가 되지 못한다 — <b>두 축 모두</b>
     * 후보가 0이면 요청이 <b>폴백 없이 거부</b>되므로, 죽은 장비를 가용으로 남겨 두면 거부될 요청이
     * 죽은 주소로 나갈 뿐이다. [@design AC-1093]
     *
     * <p>⚠ <b>구 서술 폐기(2026-09-08)</b> — 여기 <i>「추론은 아직 원장으로 장비를 고르지 않아 강등
     * 여부가 목적지를 바꾸지 않는다 … 「후보가 0이면 폴백 없이 거부된다」를 두 축에 함께 쓰지 말 것 —
     * 추론 축에서는 거짓이다」</i>라고 적혀 있었다. <b>추론 축도 이제 원장에서 고른다</b>
     * ({@code AiServerClient} → {@code AiSrvrTargetResolver}). 그때는 참이었던 서술이라 지우지 않고
     * 남긴다 — 왜 한때 문구를 갈랐는지가 사라지면 다음 사람이 그 비대칭을 다시 만들어 낸다.
     *
     * <h3>그래도 조건부 UPDATE 인 이유</h3>
     * <p>출발 상태를 {@code AVAILABLE} 로 못 박아 <b>금지 전이</b>(정비중→이용불가 · 비활성→이용불가)를
     * SQL 한 줄이 함께 막고, 두 WAS 가 같은 틱에 겹쳐도 한쪽만 실제로 갱신한다. 잠금 CTE 는 필요 없다 —
     * 세는 대상이 없으므로 다른 행의 상태가 이 판정에 끼어들지 않는다.
     *
     * @return 영향 행수. 0 이면 그 사이 상태가 바뀐 것이다(정상 — 조용히 넘어간다)
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE LS_AI_SRVR
               SET SRVR_STTS_CD = :next
             WHERE SRVR_ID = :srvrId
               AND SRVR_STTS_CD = 'AVAILABLE'
            """, nativeQuery = true)
    int demoteByHealthCheck(@Param("srvrId") String srvrId, @Param("next") String next);

    /**
     * 그 유형의 <b>가용 장비 수</b> — 상태점검이 마지막 하나를 내린 뒤 「이 유형이 통째로 멈췄다」를
     * 알리기 위한 축이다. [@design ADR-057] [@design AC-1093]
     *
     * <p>운영자에게 알려야 할 사실은 「한 대가 내려갔다」가 아니라 <b>「이 유형으로 나갈 길이 없어졌다」</b>
     * 다 — 그 순간부터 그 유형의 위탁은 폴백 없이 거부되기 때문이다.
     */
    long countBySrvrTypeCdAndSrvrSttsCd(LsAiSrvr.SrvrType srvrTypeCd, AiSrvrStatus srvrSttsCd);

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
     *
     * <h3>★ 영상 배정 행에는 <b>외래키가 없다</b> — 삭제를 막지 않는다</h3>
     * <p>{@code LS_AI_SRVR_ALTMNT} 의 장비 외래키는 <b>V26 이 의도적으로 드롭</b>했다. 근거는
     * <i>「한 번이라도 영상을 처리한 장비는 영구히 삭제 불가가 되어 장비 교체가 구조적으로 막힌다」</i>
     * 이며, 배정 표는 <b>처리가 도는 동안</b> 프레임을 한 장비에 묶어 두는 자리라 처리가 끝나면
     * 그 묶음이 순수 이력이기 때문이다. 그래서 이 문장은 배정 행이 남아 있어도 <b>성공</b>하고,
     * 그 행의 장비 식별자는 「이제 없는 장비를 가리키는 값」으로 남는다(그것이 의도다).
     *
     * <p><b>처리 중 보호는 다른 둘이 담당한다</b> — 정비중(DRAINING) 상태가 「신규 배정만 막고 진행
     * 중인 배정은 끝까지 간다」를 맡고, 그 위에 이 문장 자신의 <b>유형별 마지막 가용 장비 보호</b>가
     * 삭제·활성 이탈 양쪽을 따로 막는다. [@design AC-1091]
     *
     * <p>⚠ <b>구 서술 폐기(2026-09-08)</b> — 여기 <i>「영상 배정 행은 {@code ON DELETE RESTRICT} 라
     * 남아 있으면 이 문장이 무결성 위반으로 실패한다 — <b>호출측이 먼저 걸러 409 로 답한다</b>」</i>고
     * 적혀 있었다. <b>둘 다 사실이 아니다</b>: 그 외래키는 V26 이 없앴고, 호출측
     * ({@code AiSrvrAdminService#delete})은 {@code existsBySrvrId} 를 부르지 않는다(실측 0건).
     * <b>존재하지 않는 방어를 있다고 말하는 주석</b>이었다. 지우지 않고 남기는 이유는 그 문장이
     * 다음 라운드의 설계 근거로 역추정되어 폐기된 제약이 되살아나는 것을 막기 위해서다 —
     * 되살리려면 고아 행 정리가 선행돼야 한다(V26 주석의 되돌리기 절).
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
