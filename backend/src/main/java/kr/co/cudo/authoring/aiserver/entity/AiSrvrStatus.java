package kr.co.cudo.authoring.aiserver.entity;

import java.util.Set;

/**
 * AI 서버 상태와 <b>허용 전이</b>. [@design ADR-057]
 *
 * <pre>
 *   AVAILABLE   -> UNAVAILABLE | DRAINING | DISABLED
 *   UNAVAILABLE -> AVAILABLE   | DISABLED      (DRAINING 금지)
 *   DRAINING    -> AVAILABLE   | UNAVAILABLE | DISABLED
 *   DISABLED    -> AVAILABLE
 * </pre>
 *
 * <h3>남은 금지 전이 하나는 「굳이 막을 필요 있나」 싶은 자리라 명시해 둔다</h3>
 * <ul>
 *   <li>{@code UNAVAILABLE -> DRAINING} — 죽은 노드는 정비 대상이 아니다. 이미 신규 배정에서
 *       배제됐으므로 정비로 얻을 것이 없고, 오히려 정비 완료 판정(잔여 배정 0)이 장애 노드에
 *       걸려 관리자가 <b>끝나지 않는 정비</b>를 기다리게 된다.</li>
 * </ul>
 *
 * <h3>★★ {@code DRAINING -> UNAVAILABLE} 은 <b>열려 있다</b> (2026-09-08) [@design ADR-057] [@design API-229]</h3>
 * <p>정비중은 <b>하던 일을 끝까지 흘려보낸다</b>는 뜻이지 <b>죽어도 살아 있는 것으로 친다</b>는 뜻이
 * 아니다. 그 길이 없으면 정비 중에 <b>실제로 멈춘</b> 장비에 고정된 영상이 죽은 주소에 <b>영구히
 * 묶여</b> 프레임마다 시간 초과를 겪고, 「고정된 장비를 지금 고를 수 없으면 재배정한다」는 조항도
 * 그 장비가 이용불가로 <b>관측되지 않아</b> 발동하지 않는다.
 *
 * <p>⚠ <b>구 서술 폐기(2026-09-08)</b> — 여기 <i>「정비 중 상태점검 실패는 상태를 바꾸지 않고 기록만
 * 한다. 허용하면 관리자가 의도적으로 세운 정비 상태를 상태점검 배치가 덮어써, 사람이 만든 상태가
 * 기계에 의해 조용히 사라진다」</i>고 적혀 있었다. 그 우려 자체는 사라지지 않았다 —
 * <b>정비 의도는 실제로 사라진다</b>(그 장비가 복귀 임계에 닿으면 정비중이 아니라 가용으로 돌아온다).
 * 다만 그 대가보다 <b>죽은 장비에 영상이 영구히 묶이는 쪽</b>이 무겁다는 것이 확정된 판단이며,
 * 애초에 관측 대상이 된 장비는 이미 「사람이 세운 상태대로 동작하고 있지 않다」. 지우지 않고 남기는
 * 이유는 그 대가를 모른 채 되돌리는 일을 막기 위해서다.
 *
 * <p>⚠ <b>두 술어는 계속 갈라 둔다</b> — 정비중은 {@link #retainsPinnedAssignment() 고정 유지}
 * 에서는 참이고 <b>신규 배정 후보</b>에서는 거짓이다. 전이가 열린 것과 그 구분은 <b>다른 축</b>이며,
 * 합치면 진행 중인 작업을 끝까지 처리한다는 이점이 사라진다.
 *
 * <p>⚠ 헬스 실패로 <b>전부</b> {@code UNAVAILABLE} 이 되는 것은 막지 않는다. 실제 장애를
 * 소프트웨어로 부정할 수 없다. 반면 <b>사람이 내리는</b> 마지막 가용 노드는 막는다(원장 저장소의
 * 조건부 UPDATE) — 그건 실제 장애가 아니라 조작이기 때문이다.
 */
public enum AiSrvrStatus {

    /** 가용 — 신규 배정과 호출을 모두 받는다. */
    AVAILABLE,

    /** 이용불가 — 상태점검 연속 실패로 배제됐다. 복귀는 연속 성공으로만 한다. */
    UNAVAILABLE,

    /** 정비중 — 신규 배정만 막고 진행 중인 배정은 끝까지 간다(그것이 무중단의 정의다). */
    DRAINING,

    /** 비활성 — 관리자가 목록에서 내려 둔 상태. */
    DISABLED;

    private static final Set<AiSrvrStatus> FROM_AVAILABLE = Set.of(UNAVAILABLE, DRAINING, DISABLED);
    private static final Set<AiSrvrStatus> FROM_UNAVAILABLE = Set.of(AVAILABLE, DISABLED);
    // ★DRAINING 에서 UNAVAILABLE 이 열려 있다(위 클래스 javadoc §DRAINING -> UNAVAILABLE 은 열려 있다).
    //   ⚠ 전이표만 열면 안 된다 — 상태점검 강등 문장의 출발 상태 조건도 함께 넓혀야 실제로 내려간다
    //     (LsAiSrvrRepository#demoteByHealthCheck). 한 곳만 열면 여전히 막힌다.
    private static final Set<AiSrvrStatus> FROM_DRAINING = Set.of(AVAILABLE, UNAVAILABLE, DISABLED);
    private static final Set<AiSrvrStatus> FROM_DISABLED = Set.of(AVAILABLE);

    /**
     * 이 상태에서 {@code next} 로 갈 수 있는가.
     *
     * <p><b>같은 상태로의 「전이」는 거짓이다</b> — 상태가 안 바뀌는 갱신은 전이가 아니라 no-op 이고,
     * 참을 돌려주면 무변경 갱신이 전이 감사·통지 경로를 그대로 타게 된다. {@code null} 도 거짓이다
     * (판정 불가는 거부다 — fail-closed).
     */
    public boolean canTransitionTo(AiSrvrStatus next) {
        return next != null && allowedTransitions().contains(next);
    }

    /**
     * <b>이미 고정된 배정을 그대로 유지</b>할 수 있는 상태인가 — 「신규 배정 대상인가」와 <b>다른 술어</b>다.
     * [@design ADR-057] [@design AC-1100]
     *
     * <h3>★ 두 술어를 하나로 합치지 말 것 (합치면 반대쪽이 깨진다)</h3>
     * <table>
     *   <caption>같은 상태를 두 축이 다르게 본다</caption>
     *   <tr><th>상태</th><th>신규 배정을 받는가</th><th>고정된 배정을 유지하는가</th></tr>
     *   <tr><td>{@link #AVAILABLE}</td><td>받는다</td><td>유지한다</td></tr>
     *   <tr><td>{@link #DRAINING}</td><td><b>안 받는다</b></td><td><b>유지한다</b></td></tr>
     *   <tr><td>{@link #UNAVAILABLE}</td><td>안 받는다</td><td>유지하지 않는다(재배정 대상)</td></tr>
     *   <tr><td>{@link #DISABLED}</td><td>안 받는다</td><td>유지하지 않는다(재배정 대상)</td></tr>
     * </table>
     *
     * <p><b>정비중이 갈리는 자리가 이것뿐이다.</b> 그 상태의 정의가 「신규 배정만 막고 진행 중인 배정은
     * 끝까지 간다」이고 그것이 무중단 정비의 뜻이므로, 고정 유지 판정에서 정비중을 빼면 <b>정비로 내려
     * 둔 장비에 붙어 있던 영상이 곧바로 다른 장비로 재배정</b>되어 그 정의가 깨진다(추론 두 단계가 서로
     * 다른 장비로 가면 뒤 단계가 앞 단계의 추적 상태를 이어받지 못한다).
     *
     * <p>반대로 <b>「신규 배정 대상」 판정을 넓혀 정비중을 넣으면</b> 정비 중인 장비가 새 영상을 계속
     * 받아 정비가 끝나지 않는다 — 무중단 정비가 반대쪽에서 깨진다. 그래서 술어를 <b>둘로 나눠</b> 둔다.
     *
     * <p>{@link #UNAVAILABLE} 이 유지 대상이 아닌 것은 종전 그대로다 — 그 장비의 추적 상태는 이용불가로
     * 관측된 시점에 <b>이미 사라졌으므로</b> 지킬 연속성이 남아 있지 않고, 유지하면 그 영상이 영영 죽은
     * 장비에 묶인다. {@link #DISABLED} 도 같다(관리자가 회전에서 뺀 장비다).
     */
    public boolean retainsPinnedAssignment() {
        return this == AVAILABLE || this == DRAINING;
    }

    private Set<AiSrvrStatus> allowedTransitions() {
        return switch (this) {
            case AVAILABLE -> FROM_AVAILABLE;
            case UNAVAILABLE -> FROM_UNAVAILABLE;
            case DRAINING -> FROM_DRAINING;
            case DISABLED -> FROM_DISABLED;
        };
    }
}
