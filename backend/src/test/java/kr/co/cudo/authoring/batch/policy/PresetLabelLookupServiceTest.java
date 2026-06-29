package kr.co.cudo.authoring.batch.policy;

import kr.co.cudo.authoring.batch.policy.PresetLabelLookupService.AnnotationToggle;
import kr.co.cudo.authoring.eventtype.service.EventTypeService;
import kr.co.cudo.authoring.preset.entity.LsLabelPreset;
import kr.co.cudo.authoring.preset.entity.LsLabelPreset.LabelCodeSpec;
import kr.co.cudo.authoring.preset.repository.LsLabelPresetRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 4a — 프리셋은 관제 categoryKey 로 저장되고, 영상 이벤트는 상세 EV-코드다.
 * togglesFor 는 영상 EV-코드를 {@link EventTypeService#categoryKeyOf(String)} 로 categoryKey 로
 * 변환한 뒤 {@code findByEventTypeCd(categoryKey)} 로 프리셋을 찾아야 한다.
 */
class PresetLabelLookupServiceTest {

    /** 영상의 상세 EV-코드(침수 카테고리 소속). */
    private static final String VIDEO_EV_CODE = "EV01000102";
    /** 위 EV-코드가 속한 카테고리 키(프리셋 저장 단위). */
    private static final String CATEGORY_KEY = "010001";

    private LsLabelPresetRepository presetRepository;
    private EventTypeService eventTypeService;
    private PresetLabelLookupService service;

    @BeforeEach
    void setUp() {
        presetRepository = mock(LsLabelPresetRepository.class);
        eventTypeService = mock(EventTypeService.class);
        // 영상 EV-코드 → categoryKey 변환 기본 스텁(테스트별로 미사용이면 lenient).
        lenient().when(eventTypeService.categoryKeyOf(VIDEO_EV_CODE))
                .thenReturn(Optional.of(CATEGORY_KEY));
        service = new PresetLabelLookupService(presetRepository, eventTypeService);
    }

    @Test
    @DisplayName("프리셋매칭_영상_EV코드를_categoryKey로_변환해_프리셋을_찾는다")
    void togglesForConvertsEvCodeToCategoryKey() {
        LsLabelPreset preset = LsLabelPreset.create(
                "침수 프리셋", "침수", List.of("PERSON", "Fallen"), CATEGORY_KEY);
        when(presetRepository.findByEventTypeCd(CATEGORY_KEY)).thenReturn(Optional.of(preset));

        Optional<Set<String>> result = service.togglesFor(VIDEO_EV_CODE).map(Map::keySet);

        assertThat(result).isPresent();
        assertThat(result.get()).containsExactlyInAnyOrder("person", "fallen");
        // EV-코드로 직접 조회하지 않고 categoryKey 로만 조회해야 한다.
        verify(presetRepository).findByEventTypeCd(CATEGORY_KEY);
        verify(presetRepository, never()).findByEventTypeCd(VIDEO_EV_CODE);
    }

    @Test
    @DisplayName("프리셋매칭_미등록_EV코드는_빈Optional_failsafe")
    void unregisteredEvCodeReturnsEmpty() {
        // categoryKeyOf 가 빈 Optional(미등록 코드) → 프리셋 조회 없이 fail-safe 빈 Optional.
        when(eventTypeService.categoryKeyOf("EV99999999")).thenReturn(Optional.empty());

        Optional<Map<String, AnnotationToggle>> result = service.togglesFor("EV99999999");

        assertThat(result).isEmpty();
        verify(presetRepository, never()).findByEventTypeCd(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("eventTypeCd_null이면_변환_조회_없이_빈_Optional")
    void nullEventReturnsEmptyWithoutQuery() {
        Optional<Set<String>> result = service.togglesFor(null).map(Map::keySet);

        assertThat(result).isEmpty();
        verify(eventTypeService, never()).categoryKeyOf(org.mockito.ArgumentMatchers.any());
        verify(presetRepository, never()).findByEventTypeCd(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("eventTypeCd_blank이면_변환_조회_없이_빈_Optional")
    void blankEventReturnsEmptyWithoutQuery() {
        assertThat(service.togglesFor("").map(Map::keySet)).isEmpty();
        assertThat(service.togglesFor("   ").map(Map::keySet)).isEmpty();
        verify(eventTypeService, never()).categoryKeyOf(org.mockito.ArgumentMatchers.any());
        verify(presetRepository, never()).findByEventTypeCd(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("변환된_categoryKey에_매핑된_프리셋이_없으면_빈_Optional_fail_safe")
    void unmappedCategoryReturnsEmpty() {
        when(presetRepository.findByEventTypeCd(CATEGORY_KEY)).thenReturn(Optional.empty());

        Optional<Set<String>> result = service.togglesFor(VIDEO_EV_CODE).map(Map::keySet);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("매핑된_프리셋의_코드가_비어있으면_빈_Optional")
    void emptyCodesPresetReturnsEmpty() {
        LsLabelPreset preset = LsLabelPreset.create("빈 프리셋", null, List.of(), CATEGORY_KEY);
        when(presetRepository.findByEventTypeCd(CATEGORY_KEY)).thenReturn(Optional.of(preset));

        Optional<Set<String>> result = service.togglesFor(VIDEO_EV_CODE).map(Map::keySet);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("togglesFor_가_이벤트별_옵션_맵을_반환")
    void togglesForReturnsToggleMap() {
        LsLabelPreset preset = LsLabelPreset.createWithOptions(
                "침수 프리셋", "flood", List.of(
                        new LabelCodeSpec("PERSON", true, false),
                        new LabelCodeSpec("VEHICLE", true, true)
                ), CATEGORY_KEY);
        when(presetRepository.findByEventTypeCd(CATEGORY_KEY)).thenReturn(Optional.of(preset));

        Optional<Map<String, AnnotationToggle>> result = service.togglesFor(VIDEO_EV_CODE);

        assertThat(result).isPresent();
        Map<String, AnnotationToggle> map = result.get();
        assertThat(map).containsKeys("person", "vehicle");
        assertThat(map.get("person").bbox()).isTrue();
        assertThat(map.get("person").polygon()).isFalse();
        assertThat(map.get("vehicle").bbox()).isTrue();
        assertThat(map.get("vehicle").polygon()).isTrue();
    }

    @Test
    @DisplayName("togglesFor_keySet_으로_라벨_집합_도출_가능")
    void togglesForKeysMatchesLabelSet() {
        LsLabelPreset preset = LsLabelPreset.createWithOptions(
                "프리셋", "", List.of(
                        new LabelCodeSpec("PERSON", true, false),
                        new LabelCodeSpec("FALLEN", false, true)
                ), CATEGORY_KEY);
        when(presetRepository.findByEventTypeCd(CATEGORY_KEY)).thenReturn(Optional.of(preset));

        Optional<Map<String, AnnotationToggle>> toggles = service.togglesFor(VIDEO_EV_CODE);

        assertThat(toggles).isPresent();
        assertThat(toggles.get().keySet()).containsExactlyInAnyOrder("person", "fallen");
    }

    @Test
    @DisplayName("AnnotationToggle_BOTH_상수는_둘다_활성")
    void annotationToggleBothConstant() {
        assertThat(AnnotationToggle.BOTH.bbox()).isTrue();
        assertThat(AnnotationToggle.BOTH.polygon()).isTrue();
    }
}
