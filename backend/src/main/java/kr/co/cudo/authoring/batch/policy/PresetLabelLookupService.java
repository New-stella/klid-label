package kr.co.cudo.authoring.batch.policy;

import kr.co.cudo.authoring.eventtype.service.EventTypeService;
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
import java.util.stream.Collectors;

/**
 * 이벤트 타입 → 프리셋 라벨 코드 매핑 조회 서비스.
 *
 * <p>V1.8 변경: 기존 {@code EventPresetMapping} 코드 상수가 제거되고,
 * 매핑은 LS_LABEL_PRESET.EVNT_TYPE_CD (UNIQUE) 컬럼으로 DB 화. 운영자가 프리셋 UI 에서
 * 동적으로 관리한다.
 *
 * <p>Phase 1 — 라벨별 BBOX/POLYGON 토글 조회({@link #togglesFor(String)}) 추가.
 *
 * <p>Phase 4a — 프리셋은 관제 <b>카테고리 키</b>(categoryKey = EVNT_CLS_CD+EVNT_CTGRY_CD, 예 "010001")
 * 단위로 저장된다. 반면 영상의 이벤트 코드({@code LsDataRaw.getEvntTypeCd()})는 <b>상세 EV-코드</b>
 * (예 EV01000102)다. 따라서 매칭 전 {@link EventTypeService#categoryKeyOf(String)} 로 영상 EV-코드를
 * categoryKey 로 변환한 뒤 {@code findByEventTypeCd(categoryKey)} 로 조회한다. 변환 없이 EV-코드로
 * 직접 조회하면 프리셋이 절대 매칭되지 않는다.
 *
 * <p>호출자({@code YoloAutolabelStep}) 는 라벨 비교 시 소문자 정규화된 set 를 기대한다.
 *
 * <ul>
 *   <li>eventTypeCd 가 null/blank → {@link Optional#empty()} (호출자 fail-safe: 필터 미적용)</li>
 *   <li>관제 미등록 EV-코드(categoryKey 변환 실패) → {@link Optional#empty()} (fail-safe)</li>
 *   <li>해당 카테고리에 매핑된 프리셋이 없음 → {@link Optional#empty()} (호출자 fail-safe)</li>
 *   <li>매핑 존재 → 라벨 코드 집합/맵 (소문자 정규화)</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class PresetLabelLookupService {

    private final LsLabelPresetRepository presetRepository;
    private final EventTypeService eventTypeService;

    /**
     * 주어진 이벤트 타입에 매핑된 라벨 → 어노테이션 토글 맵을 반환한다 (Phase 1).
     *
     * <p>YoloAutolabelStep / Sam2SegmentStep 이 라벨별로 BBOX/POLYGON 저장 여부를 분기할 때 사용한다.
     *
     * @param eventTypeCd 영상의 상세 이벤트 EV-코드 (예: EV01000102). null/blank/미등록/미매핑 시 빈 Optional.
     * @return 라벨(소문자 정규화) → 토글 매핑. 빈 Optional 이면 필터 미적용 (호출자 default BOTH).
     */
    @Transactional(value = "controlTransactionManager", readOnly = true)
    public Optional<Map<String, AnnotationToggle>> togglesFor(String eventTypeCd) {
        if (eventTypeCd == null || eventTypeCd.isBlank()) {
            return Optional.empty();
        }
        // 영상 EV-코드 → 프리셋 저장 단위인 categoryKey 로 변환 (미등록 코드는 fail-safe 빈 Optional).
        Optional<String> categoryKey = eventTypeService.categoryKeyOf(eventTypeCd);
        if (categoryKey.isEmpty()) {
            return Optional.empty();
        }
        Optional<LsLabelPreset> preset = presetRepository.findByEventTypeCd(categoryKey.get());
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
