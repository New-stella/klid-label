package kr.co.cudo.authoring.batch.policy;

import kr.co.cudo.authoring.preset.entity.LsLabelPreset;
import kr.co.cudo.authoring.preset.repository.LsLabelPresetRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
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
    void nullEventReturnsEmptyWithoutQuery() {
        Optional<Set<String>> result = service.labelsFor(null);

        assertThat(result).isEmpty();
        verify(presetRepository, never()).findByEventTypeCd(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("eventTypeCd_blank이면_repository_조회_없이_빈_Optional")
    void blankEventReturnsEmptyWithoutQuery() {
        assertThat(service.labelsFor("")).isEmpty();
        assertThat(service.labelsFor("   ")).isEmpty();
        verify(presetRepository, never()).findByEventTypeCd(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("매핑된_프리셋이_없으면_빈_Optional_fail_safe")
    void unmappedEventReturnsEmpty() {
        when(presetRepository.findByEventTypeCd("EVT_UNKNOWN")).thenReturn(Optional.empty());

        Optional<Set<String>> result = service.labelsFor("EVT_UNKNOWN");

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("매핑된_프리셋의_코드가_비어있으면_빈_Optional")
    void emptyCodesPresetReturnsEmpty() {
        LsLabelPreset preset = LsLabelPreset.create("빈 프리셋", null, List.of(), "EVT_TRASH");
        when(presetRepository.findByEventTypeCd("EVT_TRASH")).thenReturn(Optional.of(preset));

        Optional<Set<String>> result = service.labelsFor("EVT_TRASH");

        assertThat(result).isEmpty();
    }
}
