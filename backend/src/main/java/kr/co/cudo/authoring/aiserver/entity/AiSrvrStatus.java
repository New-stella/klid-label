package kr.co.cudo.authoring.aiserver.entity;

import java.util.Set;

/**
 * AI 서버 상태와 <b>허용 전이</b>. [@design ADR-057]
 *
 * <pre>
 *   AVAILABLE   -> UNAVAILABLE | DRAINING | DISABLED
 *   UNAVAILABLE -> AVAILABLE   | DISABLED      (DRAINING 금지)
 *   DRAINING    -> AVAILABLE   | DISABLED      (UNAVAILABLE 금지)
 *   DISABLED    -> AVAILABLE
 * </pre>
 *
 * <h3>금지 전이 둘은 「굳이 막을 필요 있나」 싶은 자리라 명시해 둔다</h3>
 * <ul>
 *   <li>{@code UNAVAILABLE -> DRAINING} — 죽은 노드는 정비 대상이 아니다. 이미 신규 배정에서
 *       배제됐으므로 정비로 얻을 것이 없고, 오히려 정비 완료 판정(잔여 배정 0)이 장애 노드에
 *       걸려 관리자가 <b>끝나지 않는 정비</b>를 기다리게 된다.</li>
 *   <li>{@code DRAINING -> UNAVAILABLE} — 정비 중 상태점검 실패는 상태를 바꾸지 않고 <b>기록만</b>
 *       한다. 허용하면 관리자가 의도적으로 세운 정비 상태를 상태점검 배치가 덮어써, 사람이 만든
 *       상태가 기계에 의해 조용히 사라진다.</li>
 * </ul>
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
    private static final Set<AiSrvrStatus> FROM_DRAINING = Set.of(AVAILABLE, DISABLED);
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

    private Set<AiSrvrStatus> allowedTransitions() {
        return switch (this) {
            case AVAILABLE -> FROM_AVAILABLE;
            case UNAVAILABLE -> FROM_UNAVAILABLE;
            case DRAINING -> FROM_DRAINING;
            case DISABLED -> FROM_DISABLED;
        };
    }
}
