package kr.co.cudo.authoring.aiserver.service;

import kr.co.cudo.authoring.aiserver.entity.AiSrvrSlotLoad;
import kr.co.cudo.authoring.aiserver.entity.AiSrvrUsageType;

import java.util.Map;

/**
 * 부하 조회 <b>한 번의 결과</b>. [@design ADR-057]
 *
 * <h3>실패를 두 종류로 가르는 이유</h3>
 * <p>「아직 부하를 알리지 않는 노드」와 「응답이 없는 노드」는 <b>조치가 다르다</b>.
 * <ul>
 *   <li>{@link Outcome#NOT_REPORTING} — 부하 경로 자체가 없다. 부하를 알려주는 경로는 실행을
 *       용도별로 나누는 변경과 <b>함께</b> 생기므로 그 전에는 존재하지 않고, 되돌리기·배포 순서
 *       어긋남으로 구 버전 노드와 신 버전 WAS 가 겹치는 구간도 실재한다. 이는 장비에 이상이 있다는
 *       뜻이 아니므로 <b>상태점검 실패로 세지 않는다</b>.</li>
 *   <li>{@link Outcome#UNKNOWN} — 응답이 늦거나 해석되지 않았다. <b>포화로 해석하지 않는다</b> —
 *       느린 것은 부하 신호가 아니라 배선·프로세스 이상 신호다. 저장된 직전 값을 유지한다.</li>
 * </ul>
 *
 * <p>어느 쪽이든 <b>값을 지어내지 않는다</b>. 모르는 것은 모르는 채로 두고, 상태 판정은 상태점검
 * 축이 따로 한다.
 */
public record AiSrvrLoadReport(Outcome outcome, Map<AiSrvrUsageType, AiSrvrSlotLoad> slots) {

    public enum Outcome {
        /** 부하를 받아 왔다(아는 슬롯이 하나 이상). */
        REPORTED,
        /** 그 노드는 아직 부하를 알리지 않는다 — 이상이 아니다. */
        NOT_REPORTING,
        /** 물어봤는데 알 수 없었다(지연·해석 실패). 직전 값을 유지한다. */
        UNKNOWN
    }

    public AiSrvrLoadReport {
        slots = slots == null ? Map.of() : Map.copyOf(slots);
    }

    public static AiSrvrLoadReport reported(Map<AiSrvrUsageType, AiSrvrSlotLoad> slots) {
        return new AiSrvrLoadReport(Outcome.REPORTED, slots);
    }

    public static AiSrvrLoadReport notReporting() {
        return new AiSrvrLoadReport(Outcome.NOT_REPORTING, Map.of());
    }

    public static AiSrvrLoadReport unknown() {
        return new AiSrvrLoadReport(Outcome.UNKNOWN, Map.of());
    }

    /** 반영할 값이 있는가 — 없으면 저장된 값을 건드리지 않는다. */
    public boolean hasValues() {
        return outcome == Outcome.REPORTED && !slots.isEmpty();
    }
}
