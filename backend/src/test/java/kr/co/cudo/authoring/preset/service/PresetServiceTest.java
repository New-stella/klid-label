package kr.co.cudo.authoring.preset.service;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.eventtype.service.EventTypeService;
import kr.co.cudo.authoring.preset.dto.LabelCodeOptionDto;
import kr.co.cudo.authoring.preset.entity.LsLabelPreset;
import kr.co.cudo.authoring.preset.entity.LsLabelPreset.LabelCodeSpec;
import kr.co.cudo.authoring.preset.entity.LsLabelPresetCode;
import kr.co.cudo.authoring.preset.repository.LsLabelPresetRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PresetServiceTest {

    /** Phase 4a: 프리셋 매핑은 관제 categoryKey 로 검증된다(EVT_* 폐기). */
    private static final String CK_FLOOD = "010001";   // 침수
    private static final String CK_FIRE = "020001";    // 화재

    private LsLabelPresetRepository repository;
    private EventTypeService eventTypeService;
    private PresetService service;

    @BeforeEach
    void setUp() {
        repository = mock(LsLabelPresetRepository.class);
        eventTypeService = mock(EventTypeService.class);
        // 유효 categoryKey 집합 — 비빈값 검증 경로에서만 호출되므로 lenient.
        lenient().when(eventTypeService.validCategoryKeys())
                .thenReturn(Set.of(CK_FLOOD, CK_FIRE));
        service = new PresetService(repository, eventTypeService);
    }

    private static List<LabelCodeOptionDto> bothOptions(String... codes) {
        return java.util.Arrays.stream(codes).map(LabelCodeOptionDto::both).toList();
    }

    @Test
    @DisplayName("동일_이벤트가_다른_프리셋에_이미_매핑되어_있으면_CONFLICT")
    void createWithDuplicateEventThrowsConflict() {
        when(repository.existsByPresetNm(any())).thenReturn(false);
        when(repository.saveAndFlush(any(LsLabelPreset.class)))
                .thenThrow(new DataIntegrityViolationException("UK_LS_LABEL_PRESET_EVNT"));

        assertThatThrownBy(() ->
                service.create("새 프리셋", "desc", bothOptions("PERSON"), CK_FLOOD))
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
        when(repository.existsByPresetNmAndPresetIdNot("기존", 1L)).thenReturn(false);
        when(repository.saveAndFlush(any(LsLabelPreset.class)))
                .thenThrow(new DataIntegrityViolationException("UK_LS_LABEL_PRESET_EVNT"));

        assertThatThrownBy(() ->
                service.update(1L, "기존", "", bothOptions("PERSON"), CK_FLOOD))
                .isInstanceOf(CustomException.class)
                .satisfies(ex -> {
                    CustomException ce = (CustomException) ex;
                    assertThat(ce.getErrorCode().name()).isEqualTo("CONFLICT");
                });
    }

    @Test
    @DisplayName("create_정상_케이스는_eventTypeCd가_전달되어_저장된다")
    void createPassesEventTypeToEntity() {
        when(repository.existsByPresetNm(any())).thenReturn(false);
        when(repository.saveAndFlush(any(LsLabelPreset.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        LsLabelPreset saved = service.create("화재", "fire preset", bothOptions("FIRE"), CK_FIRE);

        assertThat(saved.getEventTypeCd()).isEqualTo(CK_FIRE);
        assertThat(saved.codeValues()).containsExactly("FIRE");
    }

    @Test
    @DisplayName("eventTypeCd_빈_문자열은_null_로_정규화되어_저장된다")
    void emptyEventTypeNormalizedToNull() {
        when(repository.existsByPresetNm(any())).thenReturn(false);
        when(repository.saveAndFlush(any(LsLabelPreset.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        LsLabelPreset saved = service.create("미매핑", "", bothOptions("PERSON"), "  ");

        assertThat(saved.getEventTypeCd()).isNull();
    }

    @Test
    @DisplayName("동일_이름_프리셋은_CONFLICT_변경_없음")
    void duplicateNameConflict() {
        when(repository.existsByPresetNm("중복")).thenReturn(true);

        assertThatThrownBy(() ->
                service.create("중복", "", bothOptions("PERSON"), null))
                .isInstanceOf(CustomException.class)
                .satisfies(ex -> {
                    CustomException ce = (CustomException) ex;
                    assertThat(ce.getErrorCode().name()).isEqualTo("CONFLICT");
                    assertThat(ce.getMessage()).contains("이름");
                });
    }

    @Test
    @DisplayName("프리셋_저장시_유효_categoryKey면_통과_미유효면_400")
    void validateEventTypeAgainstCategoryKeys() {
        when(repository.existsByPresetNm(any())).thenReturn(false);
        when(repository.saveAndFlush(any(LsLabelPreset.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        // 유효 categoryKey → 통과
        assertThat(service.create("유효", "", bothOptions("PERSON"), CK_FLOOD).getEventTypeCd())
                .isEqualTo(CK_FLOOD);
        // 빈값/공백 → 이벤트 무관 프리셋 허용(null 정규화)
        assertThat(service.create("빈값", "", bothOptions("PERSON"), "").getEventTypeCd()).isNull();

        // 구 EVT_* 코드 → 400 INVALID_INPUT
        assertThatThrownBy(() -> service.create("구코드", "", bothOptions("PERSON"), "EVT_FALL"))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
        // 미등록 categoryKey → 400
        assertThatThrownBy(() -> service.create("미등록", "", bothOptions("PERSON"), "999999"))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("update_도_미유효_categoryKey면_400_저장차단")
    void updateRejectsInvalidCategoryKey() {
        // update 는 findById 이전에 검증되어야 한다 — 미유효면 조회 없이 400.
        assertThatThrownBy(() -> service.update(1L, "이름", "", bothOptions("PERSON"), "EVT_FALL"))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("clone_은_eventTypeCd를_상속하지_않는다_UNIQUE_충돌_회피")
    void cloneDoesNotInheritEventMapping() {
        LsLabelPreset src = LsLabelPreset.create("원본", "desc", List.of("PERSON"), CK_FLOOD);
        when(repository.findById(1L)).thenReturn(Optional.of(src));
        when(repository.existsByPresetNm(any())).thenReturn(false);
        when(repository.save(any(LsLabelPreset.class))).thenAnswer(inv -> inv.getArgument(0));

        LsLabelPreset copy = service.clone(1L);

        assertThat(copy.getEventTypeCd()).isNull();
        assertThat(copy.codeValues()).containsExactly("PERSON");
    }

    @Test
    @DisplayName("PresetService_create_시_옵션이_그대로_엔티티에_전파")
    void createPropagatesTogglesToEntity() {
        when(repository.existsByPresetNm(any())).thenReturn(false);
        when(repository.saveAndFlush(any(LsLabelPreset.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        List<LabelCodeOptionDto> options = List.of(
                new LabelCodeOptionDto("PERSON", true, false),
                new LabelCodeOptionDto("VEHICLE", true, true)
        );

        LsLabelPreset saved = service.create("혼합", "mixed", options, CK_FLOOD);

        assertThat(saved.getCodes()).hasSize(2);
        LsLabelPresetCode person = saved.getCodes().get(0);
        LsLabelPresetCode vehicle = saved.getCodes().get(1);
        assertThat(person.getCode()).isEqualTo("PERSON");
        assertThat(person.isBboxEnabled()).isTrue();
        assertThat(person.isPolygonEnabled()).isFalse();
        assertThat(vehicle.getCode()).isEqualTo("VEHICLE");
        assertThat(vehicle.isBboxEnabled()).isTrue();
        assertThat(vehicle.isPolygonEnabled()).isTrue();
    }

    @Test
    @DisplayName("PresetService_update_시_옵션이_변경되면_replaceCodes_가_새_옵션으로_갱신")
    void updateAppliesNewToggles() {
        LsLabelPreset existing = LsLabelPreset.create("기존", "", List.of("PERSON", "VEHICLE"), null);
        // 기존은 모두 BOTH (createWithStrings 경유)
        assertThat(existing.getCodes().get(0).isBboxEnabled()).isTrue();
        assertThat(existing.getCodes().get(0).isPolygonEnabled()).isTrue();

        when(repository.findById(1L)).thenReturn(Optional.of(existing));
        when(repository.existsByPresetNmAndPresetIdNot(any(), any())).thenReturn(false);
        when(repository.saveAndFlush(any(LsLabelPreset.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        List<LabelCodeOptionDto> newOptions = List.of(
                new LabelCodeOptionDto("PERSON", true, false),  // BBOX only
                new LabelCodeOptionDto("VEHICLE", false, true)  // POLYGON only
        );
        LsLabelPreset updated = service.update(1L, "기존", "", newOptions, null);

        assertThat(updated.getCodes()).hasSize(2);
        LsLabelPresetCode person = updated.getCodes().stream()
                .filter(c -> c.getCode().equals("PERSON")).findFirst().orElseThrow();
        LsLabelPresetCode vehicle = updated.getCodes().stream()
                .filter(c -> c.getCode().equals("VEHICLE")).findFirst().orElseThrow();
        assertThat(person.isBboxEnabled()).isTrue();
        assertThat(person.isPolygonEnabled()).isFalse();
        assertThat(vehicle.isBboxEnabled()).isFalse();
        assertThat(vehicle.isPolygonEnabled()).isTrue();
    }

    @Test
    @DisplayName("PresetService_create_빈_옵션이면_엔티티에_빈_코드_목록_저장")
    void createWithEmptyOptionsResultsInEmptyCodes() {
        when(repository.existsByPresetNm(any())).thenReturn(false);
        when(repository.saveAndFlush(any(LsLabelPreset.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        LsLabelPreset saved = service.create("빈", "", List.of(), null);

        assertThat(saved.getCodes()).isEmpty();
    }

    @Test
    @DisplayName("LabelCodeSpec_도메인_경유로_생성된_프리셋도_토글이_저장된다")
    void domainCreateWithOptionsPersistsToggles() {
        LsLabelPreset preset = LsLabelPreset.createWithOptions(
                "직접", "", List.of(
                        new LabelCodeSpec("A", true, false),
                        new LabelCodeSpec("B", false, true)
                ), null);

        assertThat(preset.getCodes()).hasSize(2);
        assertThat(preset.getCodes().get(0).isBboxEnabled()).isTrue();
        assertThat(preset.getCodes().get(0).isPolygonEnabled()).isFalse();
        assertThat(preset.getCodes().get(1).isBboxEnabled()).isFalse();
        assertThat(preset.getCodes().get(1).isPolygonEnabled()).isTrue();
    }

    @Test
    @DisplayName("LabelCodeSpec_둘다_false_조합은_도메인이_즉시_거부")
    void domainRejectsBothFalseSpec() {
        assertThatThrownBy(() -> LsLabelPreset.createWithOptions(
                "거부", "", List.of(new LabelCodeSpec("PERSON", false, false)), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("최소 하나");
    }
}
