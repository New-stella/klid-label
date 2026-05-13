package kr.co.cudo.authoring.preset.service;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.preset.entity.LsLabelPreset;
import kr.co.cudo.authoring.preset.repository.LsLabelPresetRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PresetServiceTest {

    private LsLabelPresetRepository repository;
    private PresetService service;

    @BeforeEach
    void setUp() {
        repository = mock(LsLabelPresetRepository.class);
        service = new PresetService(repository);
    }

    @Test
    @DisplayName("동일_이벤트가_다른_프리셋에_이미_매핑되어_있으면_CONFLICT")
    void createWithDuplicateEventThrowsConflict() {
        when(repository.existsByName(any())).thenReturn(false);
        when(repository.saveAndFlush(any(LsLabelPreset.class)))
                .thenThrow(new DataIntegrityViolationException("UK_LS_LABEL_PRESET_EVNT"));

        assertThatThrownBy(() ->
                service.create("새 프리셋", "desc", List.of("PERSON"), "EVT_FALL"))
                .isInstanceOf(CustomException.class)
                .satisfies(ex -> {
                    CustomException ce = (CustomException) ex;
                    assertThat(ce.getErrorCode().name()).isEqualTo("CONFLICT");
                    assertThat(ce.getMessage()).contains("다른 프리셋에 매핑된 이벤트");
                });
    }

    @Test
    @DisplayName("update_시_이벤트_UNIQUE_위반은_CONFLICT_로_변환")
    void updateWithDuplicateEventThrowsConflict() {
        LsLabelPreset existing = LsLabelPreset.create("기존", "", List.of("PERSON"), null);
        when(repository.findById(1L)).thenReturn(Optional.of(existing));
        when(repository.existsByNameAndPresetIdNot("기존", 1L)).thenReturn(false);
        when(repository.saveAndFlush(any(LsLabelPreset.class)))
                .thenThrow(new DataIntegrityViolationException("UK_LS_LABEL_PRESET_EVNT"));

        assertThatThrownBy(() ->
                service.update(1L, "기존", "", List.of("PERSON"), "EVT_FALL"))
                .isInstanceOf(CustomException.class)
                .satisfies(ex -> {
                    CustomException ce = (CustomException) ex;
                    assertThat(ce.getErrorCode().name()).isEqualTo("CONFLICT");
                });
    }

    @Test
    @DisplayName("create_정상_케이스는_eventTypeCd가_전달되어_저장된다")
    void createPassesEventTypeToEntity() {
        when(repository.existsByName(any())).thenReturn(false);
        when(repository.saveAndFlush(any(LsLabelPreset.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        LsLabelPreset saved = service.create("화재", "fire preset", List.of("FIRE"), "EVT_FIRE");

        assertThat(saved.getEventTypeCd()).isEqualTo("EVT_FIRE");
        assertThat(saved.codeValues()).containsExactly("FIRE");
    }

    @Test
    @DisplayName("eventTypeCd_빈_문자열은_null_로_정규화되어_저장된다")
    void emptyEventTypeNormalizedToNull() {
        when(repository.existsByName(any())).thenReturn(false);
        when(repository.saveAndFlush(any(LsLabelPreset.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        LsLabelPreset saved = service.create("미매핑", "", List.of("PERSON"), "  ");

        assertThat(saved.getEventTypeCd()).isNull();
    }

    @Test
    @DisplayName("동일_이름_프리셋은_CONFLICT_변경_없음")
    void duplicateNameConflict() {
        when(repository.existsByName("중복")).thenReturn(true);

        assertThatThrownBy(() ->
                service.create("중복", "", List.of("PERSON"), null))
                .isInstanceOf(CustomException.class)
                .satisfies(ex -> {
                    CustomException ce = (CustomException) ex;
                    assertThat(ce.getErrorCode().name()).isEqualTo("CONFLICT");
                    assertThat(ce.getMessage()).contains("이름");
                });
    }

    @Test
    @DisplayName("clone_은_eventTypeCd를_상속하지_않는다_UNIQUE_충돌_회피")
    void cloneDoesNotInheritEventMapping() {
        LsLabelPreset src = LsLabelPreset.create("원본", "desc", List.of("PERSON"), "EVT_FALL");
        when(repository.findById(1L)).thenReturn(Optional.of(src));
        when(repository.existsByName(any())).thenReturn(false);
        when(repository.save(any(LsLabelPreset.class))).thenAnswer(inv -> inv.getArgument(0));

        LsLabelPreset copy = service.clone(1L);

        assertThat(copy.getEventTypeCd()).isNull();
        assertThat(copy.codeValues()).containsExactly("PERSON");
    }
}
