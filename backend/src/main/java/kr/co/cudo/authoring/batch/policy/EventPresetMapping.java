package kr.co.cudo.authoring.batch.policy;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 이벤트 타입별 오토라벨 필수 클래스 매핑.
 * <p>
 * YOLO 결과 중 매핑된 클래스만 LS_DATA_LBL 에 저장하여 노이즈를 제거한다.
 * 매핑은 시드/DB 가 아닌 코드 상수로 관리한다 (운영 정책상 고정 매핑 선택).
 * <ul>
 *   <li>eventTypeCd 미정/없음/미매핑 → {@link Optional#empty()} 반환 → 호출자는 필터 미적용 (fail-safe)</li>
 *   <li>라벨 비교는 호출자가 소문자/trim 정규화 후 수행해야 한다</li>
 * </ul>
 */
public final class EventPresetMapping {

    private static final Map<String, Set<String>> MAPPING = Map.of(
            "EVT_FALL",     Set.of("person"),
            "EVT_VIOLENCE", Set.of("person"),
            "EVT_ACCIDENT", Set.of("car", "truck", "motorcycle", "bus", "bicycle", "person"),
            "EVT_FIRE",     Set.of("fire", "person"),
            "EVT_TRASH",    Set.of("trash")
    );

    private EventPresetMapping() {
    }

    /**
     * 주어진 이벤트 타입 코드에 매핑된 허용 라벨 집합을 반환한다.
     *
     * @param eventTypeCd 이벤트 타입 코드 (예: EVT_FALL). null/blank/미매핑 시 빈 Optional 반환.
     * @return 매핑된 허용 라벨 집합. 빈 Optional 이면 필터 미적용 (전체 통과).
     */
    public static Optional<Set<String>> labelsFor(String eventTypeCd) {
        if (eventTypeCd == null || eventTypeCd.isBlank()) {
            return Optional.empty();
        }
        Set<String> set = MAPPING.get(eventTypeCd);
        return set == null ? Optional.empty() : Optional.of(set);
    }
}
