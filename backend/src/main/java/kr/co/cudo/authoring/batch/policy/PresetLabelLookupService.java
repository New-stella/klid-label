package kr.co.cudo.authoring.batch.policy;

import kr.co.cudo.authoring.preset.entity.LsLabelPreset;
import kr.co.cudo.authoring.preset.entity.LsLabelPresetCode;
import kr.co.cudo.authoring.preset.repository.LsLabelPresetRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 이벤트 타입 → 프리셋 라벨 코드 매핑 조회 서비스.
 *
 * <p>V1.8 변경: 기존 {@code EventPresetMapping} 코드 상수가 제거되고,
 * 매핑은 LS_LABEL_PRESET.EVNT_TYPE_CD (UNIQUE) 컬럼으로 DB 화. 운영자가 프리셋 UI 에서
 * 동적으로 관리한다.
 *
 * <p>Phase 1 — 라벨별 BBOX/POLYGON 토글 조회({@link #togglesFor(String)}) 추가.
 * 기존 {@link #labelsFor(String)} 는 {@code togglesFor().keySet()} 로 구현되어 호환된다.
 *
 * <p>호출자({@code YoloAutolabelStep}) 는 라벨 비교 시 소문자 정규화된 set 를 기대한다.
 *
 * <ul>
 *   <li>eventTypeCd 가 null/blank → {@link Optional#empty()} (호출자 fail-safe: 필터 미적용)</li>
 *   <li>해당 이벤트에 매핑된 프리셋이 없음 → {@link Optional#empty()} (호출자 fail-safe)</li>
 *   <li>매핑 존재 → 라벨 코드 집합/맵 (소문자 정규화)</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class PresetLabelLookupService {

    private final LsLabelPresetRepository presetRepository;

    /**
     * 주어진 이벤트 타입 코드에 매핑된 허용 라벨 집합을 반환한다.
     *
     * @param eventTypeCd 이벤트 타입 코드 (예: EVT_FALL). null/blank/미매핑 시 빈 Optional 반환.
     * @return 매핑된 허용 라벨 집합 (소문자 정규화). 빈 Optional 이면 필터 미적용 (전체 통과).
     * @deprecated Phase 1 이후 {@link #togglesFor(String)} 사용. keySet 으로 동등 동작.
     */
    @Deprecated
    @Transactional(value = "controlTransactionManager", readOnly = true)
    public Optional<Set<String>> labelsFor(String eventTypeCd) {
        return togglesFor(eventTypeCd).map(Map::keySet);
    }

    /**
     * 주어진 이벤트 타입에 매핑된 라벨 → 어노테이션 토글 맵을 반환한다 (Phase 1).
     *
     * <p>YoloAutolabelStep / Sam2SegmentStep 이 라벨별로 BBOX/POLYGON 저장 여부를 분기할 때 사용한다.
     *
     * @param eventTypeCd 이벤트 타입 코드 (예: EVT_FALL). null/blank/미매핑 시 빈 Optional.
     * @return 라벨(소문자 정규화) → 토글 매핑. 빈 Optional 이면 필터 미적용 (호출자 default BOTH).
     */
    @Transactional(value = "controlTransactionManager", readOnly = true)
    public Optional<Map<String, AnnotationToggle>> togglesFor(String eventTypeCd) {
        if (eventTypeCd == null || eventTypeCd.isBlank()) {
            return Optional.empty();
        }
        Optional<LsLabelPreset> preset = presetRepository.findByEventTypeCd(eventTypeCd);
        if (preset.isEmpty()) {
            return Optional.empty();
        }
        LinkedHashMap<String, AnnotationToggle> map = preset.get().getCodes().stream()
                .collect(Collectors.toMap(
                        c -> c.getCode().toLowerCase(),
                        PresetLabelLookupService::toToggle,
                        (a, b) -> a,
                        LinkedHashMap::new
                ));
        if (map.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(Collections.unmodifiableMap(map));
    }

    private static AnnotationToggle toToggle(LsLabelPresetCode c) {
        return new AnnotationToggle(c.isBboxEnabled(), c.isPolygonEnabled());
    }

    /**
     * 라벨별 어노테이션 토글 VO.
     *
     * <p>{@link #BOTH} 는 fail-safe 기본값 — 호출자가 옵션 미설정 라벨을 만나면 사용한다.
     */
    public record AnnotationToggle(boolean bbox, boolean polygon) {

        /** 기본값(BBOX + POLYGON 모두 활성) — fail-safe. */
        public static final AnnotationToggle BOTH = new AnnotationToggle(true, true);
    }
}
