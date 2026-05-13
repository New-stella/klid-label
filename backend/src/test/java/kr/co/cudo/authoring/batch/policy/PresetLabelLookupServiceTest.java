package kr.co.cudo.authoring.batch.policy;

import kr.co.cudo.authoring.batch.policy.PresetLabelLookupService.AnnotationToggle;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PresetLabelLookupServiceTest {

    private LsLabelPresetRepository presetRepository;
    private PresetLabelLookupService service;

    @BeforeEach
    void setUp() {
        presetRepository = mock(LsLabelPresetRepository.class);
        service = new PresetLabelLookupService(presetRepository);
    }

    @Test
    @DisplayName("매핑된_프리셋이_있으면_소문자_정규화된_라벨_집합_반환")
    @SuppressWarnings("deprecation")
    void mappedPresetReturnsLowercaseLabelSet() {
        LsLabelPreset preset = LsLabelPreset.create(
                "낙상 프리셋", "낙상", List.of("PERSON", "Fallen"), "EVT_FALL");
        when(presetRepository.findByEventTypeCd("EVT_FALL")).thenReturn(Optional.of(preset));

        Optional<Set<String>> result = service.labelsFor("EVT_FALL");

        assertThat(result).isPresent();
        assertThat(result.get()).containsExactlyInAnyOrder("person", "fallen");
    }

    @Test
    @DisplayName("eventTypeCd_null이면_repository_조회_없이_빈_Optional")
    @SuppressWarnings("deprecation")
    void nullEventReturnsEmptyWithoutQuery() {
        Optional<Set<String>> result = service.labelsFor(null);

        assertThat(result).isEmpty();
        verify(presetRepository, never()).findByEventTypeCd(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("eventTypeCd_blank이면_repository_조회_없이_빈_Optional")
    @SuppressWarnings("deprecation")
    void blankEventReturnsEmptyWithoutQuery() {
        assertThat(service.labelsFor("")).isEmpty();
        assertThat(service.labelsFor("   ")).isEmpty();
        verify(presetRepository, never()).findByEventTypeCd(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("매핑된_프리셋이_없으면_빈_Optional_fail_safe")
    @SuppressWarnings("deprecation")
    void unmappedEventReturnsEmpty() {
        when(presetRepository.findByEventTypeCd("EVT_UNKNOWN")).thenReturn(Optional.empty());

        Optional<Set<String>> result = service.labelsFor("EVT_UNKNOWN");

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("매핑된_프리셋의_코드가_비어있으면_빈_Optional")
    @SuppressWarnings("deprecation")
    void emptyCodesPresetReturnsEmpty() {
        LsLabelPreset preset = LsLabelPreset.create("빈 프리셋", null, List.of(), "EVT_FLOOD");
        when(presetRepository.findByEventTypeCd("EVT_FLOOD")).thenReturn(Optional.of(preset));

        Optional<Set<String>> result = service.labelsFor("EVT_FLOOD");

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("PresetLabelLookupService_togglesFor_가_이벤트별_옵션_맵을_반환")
    void togglesForReturnsToggleMap() {
        LsLabelPreset preset = LsLabelPreset.createWithOptions(
                "낙상 프리셋", "fall", List.of(
                        new LabelCodeSpec("PERSON", true, false),
                        new LabelCodeSpec("VEHICLE", true, true)
                ), "EVT_FALL");
        when(presetRepository.findByEventTypeCd("EVT_FALL")).thenReturn(Optional.of(preset));

        Optional<Map<String, AnnotationToggle>> result = service.togglesFor("EVT_FALL");

        assertThat(result).isPresent();
        Map<String, AnnotationToggle> map = result.get();
        assertThat(map).containsKeys("person", "vehicle");
        assertThat(map.get("person").bbox()).isTrue();
        assertThat(map.get("person").polygon()).isFalse();
        assertThat(map.get("vehicle").bbox()).isTrue();
        assertThat(map.get("vehicle").polygon()).isTrue();
    }

    @Test
    @DisplayName("PresetLabelLookupService_labelsFor_가_여전히_togglesFor_keys_와_일치")
    @SuppressWarnings("deprecation")
    void labelsForMatchesTogglesForKeys() {
        LsLabelPreset preset = LsLabelPreset.createWithOptions(
                "프리셋", "", List.of(
                        new LabelCodeSpec("PERSON", true, false),
                        new LabelCodeSpec("FALLEN", false, true)
                ), "EVT_FALL");
        when(presetRepository.findByEventTypeCd("EVT_FALL")).thenReturn(Optional.of(preset));

        Optional<Set<String>> labels = service.labelsFor("EVT_FALL");
        Optional<Map<String, AnnotationToggle>> toggles = service.togglesFor("EVT_FALL");

        assertThat(labels).isPresent();
        assertThat(toggles).isPresent();
        assertThat(labels.get()).isEqualTo(toggles.get().keySet());
    }

    @Test
    @DisplayName("AnnotationToggle_BOTH_상수는_둘다_활성")
    void annotationToggleBothConstant() {
        assertThat(AnnotationToggle.BOTH.bbox()).isTrue();
        assertThat(AnnotationToggle.BOTH.polygon()).isTrue();
    }

    @Test
    @DisplayName("togglesFor_null이면_조회_없이_빈_Optional")
    void togglesForNullReturnsEmpty() {
        Optional<Map<String, AnnotationToggle>> result = service.togglesFor(null);

        assertThat(result).isEmpty();
        verify(presetRepository, never()).findByEventTypeCd(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("togglesFor_blank이면_조회_없이_빈_Optional")
    void togglesForBlankReturnsEmpty() {
        assertThat(service.togglesFor("")).isEmpty();
        assertThat(service.togglesFor("   ")).isEmpty();
    }

    @Test
    @DisplayName("togglesFor_미매핑_이벤트면_빈_Optional")
    void togglesForUnmappedReturnsEmpty() {
        when(presetRepository.findByEventTypeCd("EVT_UNKNOWN")).thenReturn(Optional.empty());

        Optional<Map<String, AnnotationToggle>> result = service.togglesFor("EVT_UNKNOWN");

        assertThat(result).isEmpty();
    }
}
