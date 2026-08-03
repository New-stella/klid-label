package kr.co.cudo.authoring.preset.service;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.eventtype.service.EventTypeService;
import kr.co.cudo.authoring.label.dto.LabelMasterResponse;
import kr.co.cudo.authoring.label.service.LabelMasterService;
import kr.co.cudo.authoring.preset.dto.PresetCodeView;
import kr.co.cudo.authoring.preset.dto.PresetView;
import kr.co.cudo.authoring.preset.entity.LsLabelPreset;
import kr.co.cudo.authoring.preset.entity.LsLabelPreset.LabelCodeSpec;
import kr.co.cudo.authoring.preset.repository.LsLabelPresetRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PresetServiceTest {

    /** Phase 4a: 프리셋 매핑은 관제 categoryKey 로 검증된다(EVT_* 폐기). */
    private static final String CK_FLOOD = "010001";   // 침수
    private static final String CK_FIRE = "020001";    // 화재
    /** 제외 대분류 설정으로 필터 옵션에서 빠진 카테고리(= validCategoryKeys 미포함). */
    private static final String CK_EXCLUDED = "080001";       // 배회
    private static final String CK_EXCLUDED_OTHER = "090001"; // 다른 제외 카테고리

    /** 활성 마스터 라벨 레지스트리 (id → 라벨명/형태). */
    private static final Map<Long, LabelMasterResponse> KNOWN_LABELS = Map.of(
            10L, master(10L, "PERSON", "BBOX"),
            11L, master(11L, "VEHICLE", "POLYGON"),
            12L, master(12L, "HEAD", "POINT"),
            13L, master(13L, "POSE", "SKELETON")
    );

    private LsLabelPresetRepository repository;
    private EventTypeService eventTypeService;
    private LabelMasterService labelMasterService;
    private PresetService service;

    private static LabelMasterResponse master(long id, String name, String type) {
        return new LabelMasterResponse(id, name, "#FF0000", type, 0, "Y", null);
    }

    @BeforeEach
    void setUp() {
        repository = mock(LsLabelPresetRepository.class);
        eventTypeService = mock(EventTypeService.class);
        labelMasterService = mock(LabelMasterService.class);
        lenient().when(eventTypeService.validCategoryKeys())
                .thenReturn(Set.of(CK_FLOOD, CK_FIRE));
        // 요청 id 중 활성 마스터에 있는 것만 돌려준다(soft delete/미존재는 제외됨).
        lenient().when(labelMasterService.findActiveByIds(anyCollection()))
                .thenAnswer(inv -> {
                    Collection<Long> ids = inv.getArgument(0);
                    Map<Long, LabelMasterResponse> out = new LinkedHashMap<>();
                    for (Long id : ids) {
                        if (KNOWN_LABELS.containsKey(id)) {
                            out.put(id, KNOWN_LABELS.get(id));
                        }
                    }
                    return out;
                });
        service = new PresetService(repository, eventTypeService, labelMasterService);
    }

    // ----- 기존 계약 적응 -----

    @Test
    @DisplayName("동일_이벤트가_다른_프리셋에_이미_매핑되어_있으면_CONFLICT")
    void createWithDuplicateEventThrowsConflict() {
        when(repository.existsByPresetNm(any())).thenReturn(false);
        when(repository.saveAndFlush(any(LsLabelPreset.class)))
                .thenThrow(new DataIntegrityViolationException("UK_LS_LABEL_PRESET_EVNT"));

        assertThatThrownBy(() ->
                service.create("새 프리셋", "desc", List.of(10L), CK_FLOOD))
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
                service.update(1L, "기존", "", List.of(10L), CK_FLOOD))
                .isInstanceOf(CustomException.class)
                .satisfies(ex -> assertThat(((CustomException) ex).getErrorCode().name()).isEqualTo("CONFLICT"));
    }

    @Test
    @DisplayName("create_정상_케이스는_eventTypeCd가_전달되어_저장된다")
    void createPassesEventTypeToEntity() {
        when(repository.existsByPresetNm(any())).thenReturn(false);
        when(repository.saveAndFlush(any(LsLabelPreset.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        PresetView saved = service.create("화재", "fire preset", List.of(10L), CK_FIRE);

        assertThat(saved.eventTypeCd()).isEqualTo(CK_FIRE);
        assertThat(saved.codes()).hasSize(1);
        assertThat(saved.codes().get(0).labelId()).isEqualTo(10L);
    }

    @Test
    @DisplayName("eventTypeCd_빈_문자열은_null_로_정규화되어_저장된다")
    void emptyEventTypeNormalizedToNull() {
        when(repository.existsByPresetNm(any())).thenReturn(false);
        when(repository.saveAndFlush(any(LsLabelPreset.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        PresetView saved = service.create("미매핑", "", List.of(10L), "  ");

        assertThat(saved.eventTypeCd()).isNull();
    }

    @Test
    @DisplayName("동일_이름_프리셋은_CONFLICT_변경_없음")
    void duplicateNameConflict() {
        when(repository.existsByPresetNm("중복")).thenReturn(true);

        assertThatThrownBy(() ->
                service.create("중복", "", List.of(10L), null))
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

        assertThat(service.create("유효", "", List.of(10L), CK_FLOOD).eventTypeCd()).isEqualTo(CK_FLOOD);
        assertThat(service.create("빈값", "", List.of(10L), "").eventTypeCd()).isNull();

        assertThatThrownBy(() -> service.create("구코드", "", List.of(10L), "EVT_FALL"))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
        assertThatThrownBy(() -> service.create("미등록", "", List.of(10L), "999999"))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("update_도_미유효_categoryKey면_400_저장차단")
    void updateRejectsInvalidCategoryKey() {
        // 기존 매핑(null)에서 미유효 값으로 '변경'을 시도하므로 검증이 수행된다.
        LsLabelPreset existing = LsLabelPreset.create("이름", "", List.of("PERSON"), null);
        when(repository.findById(1L)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.update(1L, "이름", "", List.of(10L), "EVT_FALL"))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    // ----- R1-1: 제외 대분류 코드 동적화에 따른 기존 프리셋 호환 -----

    @Test
    @DisplayName("제외된_카테고리에_매핑된_기존_프리셋은_이벤트값_유지한채_이름수정_성공")
    void updateKeepsExcludedEventMappingEditable() {
        // given — 나중에 제외되어 validCategoryKeys 에서 빠진 카테고리(080001)에 매핑된 기존 프리셋
        LsLabelPreset existing = LsLabelPreset.createWithOptions(
                "옛프리셋", "", List.of(new LabelCodeSpec(10L, null)), CK_EXCLUDED);
        when(repository.findById(1L)).thenReturn(Optional.of(existing));
        when(repository.existsByPresetNmAndPresetIdNot("새이름", 1L)).thenReturn(false);
        when(repository.saveAndFlush(any(LsLabelPreset.class))).thenAnswer(inv -> inv.getArgument(0));

        // when — eventTypeCd 는 그대로 두고 이름/설명/라벨만 수정
        PresetView updated = service.update(1L, "새이름", "설명변경", List.of(11L), CK_EXCLUDED);

        // then — 제외 카테고리라도 편집이 계속 가능하고 매핑값이 유지된다
        assertThat(updated.presetNm()).isEqualTo("새이름");
        assertThat(updated.eventTypeCd()).isEqualTo(CK_EXCLUDED);
    }

    @Test
    @DisplayName("기존_프리셋을_다른_제외된_카테고리로_재매핑시도하면_INVALID_INPUT")
    void updateRejectsRemappingToExcludedCategory() {
        // given — 현재 080001 에 매핑된 프리셋
        LsLabelPreset existing = LsLabelPreset.createWithOptions(
                "옛프리셋", "", List.of(new LabelCodeSpec(10L, null)), CK_EXCLUDED);
        when(repository.findById(1L)).thenReturn(Optional.of(existing));

        // when / then — 다른 제외 카테고리(090001)로 '변경'하는 것은 여전히 차단
        assertThatThrownBy(() -> service.update(1L, "옛프리셋", "", List.of(10L), CK_EXCLUDED_OTHER))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("신규_프리셋_생성시_제외된_카테고리_매핑은_기존과_동일하게_차단")
    void createStillRejectsExcludedCategory() {
        when(repository.existsByPresetNm(any())).thenReturn(false);

        assertThatThrownBy(() -> service.create("신규", "", List.of(10L), CK_EXCLUDED))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("update_이벤트매핑없는프리셋을_null유지한채_이름만수정_성공_검증스킵")
    void updateKeepsNullEventMappingEditable() {
        // given — 이벤트 매핑이 없는(eventTypeCd=null) 프리셋. 실사용에서 가장 흔한 형태다.
        LsLabelPreset existing = LsLabelPreset.createWithOptions(
                "무매핑", "", List.of(new LabelCodeSpec(10L, null)), null);
        when(repository.findById(1L)).thenReturn(Optional.of(existing));
        when(repository.existsByPresetNmAndPresetIdNot("새이름", 1L)).thenReturn(false);
        when(repository.saveAndFlush(any(LsLabelPreset.class))).thenAnswer(inv -> inv.getArgument(0));

        // when — eventTypeCd 를 null 그대로 둔 채 이름/설명/라벨만 수정 (null == null → 검증 스킵 분기)
        PresetView updated = service.update(1L, "새이름", "설명변경", List.of(11L), null);

        // then — 예외 없이 성공하고 매핑은 계속 미매핑(null) 이다
        assertThat(updated.presetNm()).isEqualTo("새이름");
        assertThat(updated.expln()).isEqualTo("설명변경");
        assertThat(updated.eventTypeCd()).isNull();
        assertThat(updated.codes()).hasSize(1);
        assertThat(updated.codes().get(0).labelId()).isEqualTo(11L);
        // 매핑값이 바뀌지 않았으므로 이벤트 유효값 조회(validateEventType) 자체가 수행되지 않는다.
        verify(eventTypeService, never()).validCategoryKeys();
    }

    @Test
    @DisplayName("update_eventTypeCd_빈문자열로_null유지_검증스킵")
    void updateBlankEventTypeIsTreatedAsUnchanged() {
        // given — 저장값이 null 인 프리셋. FE 가 미선택 상태를 빈 문자열로 보내는 경우를 재현한다.
        LsLabelPreset existing = LsLabelPreset.createWithOptions(
                "무매핑", "", List.of(new LabelCodeSpec(10L, null)), null);
        when(repository.findById(1L)).thenReturn(Optional.of(existing));
        when(repository.saveAndFlush(any(LsLabelPreset.class))).thenAnswer(inv -> inv.getArgument(0));

        // when / then — 엔티티(assignToEvent) 와 동일한 trim+blank→null 정규화로 "변경 없음" 판정 → 스킵
        for (String blank : new String[]{"", "   "}) {
            PresetView updated = service.update(1L, "무매핑", "", List.of(10L), blank);

            assertThat(updated.eventTypeCd()).as("blank=[%s]", blank).isNull();
        }
        verify(eventTypeService, never()).validCategoryKeys();
    }

    @Test
    @DisplayName("clone_은_eventTypeCd를_상속하지_않고_코드를_복사한다")
    void cloneDoesNotInheritEventMapping() {
        LsLabelPreset src = LsLabelPreset.createWithOptions(
                "원본", "desc", List.of(new LabelCodeSpec(10L, null)), CK_FLOOD);
        when(repository.findById(1L)).thenReturn(Optional.of(src));
        when(repository.existsByPresetNm(any())).thenReturn(false);
        when(repository.save(any(LsLabelPreset.class))).thenAnswer(inv -> inv.getArgument(0));

        PresetView copy = service.clone(1L);

        assertThat(copy.eventTypeCd()).isNull();
        assertThat(copy.codes()).hasSize(1);
        assertThat(copy.codes().get(0).labelId()).isEqualTo(10L);
    }

    @Test
    @DisplayName("update_존재하지않는_프리셋이면_NOT_FOUND")
    void updateMissingPresetThrowsNotFound() {
        when(repository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(404L, "이름", "", List.of(10L), null))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("create_중복labelId_요청시_코드는_1건으로_저장된다")
    void createDedupsDuplicateLabelId() {
        when(repository.existsByPresetNm(any())).thenReturn(false);
        when(repository.saveAndFlush(any(LsLabelPreset.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        // 같은 labelId(10) 를 3번 넣어도 도메인 dedup 으로 코드는 1건이어야 한다.
        PresetView saved = service.create("중복라벨", "", List.of(10L, 10L, 10L), null);

        assertThat(saved.codes()).hasSize(1);
        assertThat(saved.codes().get(0).labelId()).isEqualTo(10L);
    }

    @Test
    @DisplayName("list_labelId있지만_soft_delete된_마스터는_linked_false로_노출된다")
    void listWithLabelIdButDeletedMasterShowsUnlinked() {
        // labelId=77 은 저장돼 있으나 마스터 조회에서 활성 라벨로 나오지 않는다(soft delete/미존재).
        LsLabelPreset preset = LsLabelPreset.createWithOptions(
                "half", "", List.of(new LabelCodeSpec(77L, null)), null);
        when(repository.findAllWithCodes()).thenReturn(List.of(preset));

        PresetCodeView code = service.list().get(0).codes().get(0);

        assertThat(code.linked()).isFalse();
        assertThat(code.labelId()).isEqualTo(77L);
        assertThat(code.labelType()).isNull();
        assertThat(code.bboxEnabled()).isFalse();
        assertThat(code.polygonEnabled()).isFalse();
    }

    // ----- AC1: 스냅샷 아님 (마스터 실시간 조회) -----

    @Test
    @DisplayName("마스터_라벨명_변경후_프리셋조회시_변경된_라벨명이_반영된다")
    void masterNameChangeIsReflectedOnRead() {
        // 프리셋 코드는 labelId=10 만 저장(라벨명 미저장). 조회 시 마스터가 돌려주는 이름이 그대로 반영돼야 한다.
        LsLabelPreset preset = LsLabelPreset.createWithOptions(
                "p", "", List.of(new LabelCodeSpec(10L, null)), null);
        when(repository.findAllWithCodes()).thenReturn(List.of(preset));
        // 마스터가 '변경된 이름'을 돌려주도록 오버라이드.
        when(labelMasterService.findActiveByIds(anyCollection()))
                .thenReturn(Map.of(10L, master(10L, "사람_변경됨", "BBOX")));

        List<PresetView> views = service.list();

        assertThat(views).hasSize(1);
        PresetCodeView code = views.get(0).codes().get(0);
        assertThat(code.linked()).isTrue();
        assertThat(code.labelName()).isEqualTo("사람_변경됨");
    }

    // ----- AC2 / R5: 형태 마스터 파생 -----

    @Test
    @DisplayName("마스터_형태_BBOX면_프리셋응답_토글이_bbox만_활성이다")
    void bboxMasterEnablesBboxOnly() {
        PresetCodeView view = codeViewFor(10L); // PERSON/BBOX
        assertThat(view.labelType()).isEqualTo("BBOX");
        assertThat(view.bboxEnabled()).isTrue();
        assertThat(view.polygonEnabled()).isFalse();
    }

    @Test
    @DisplayName("마스터_형태_POLYGON이면_polygon만_활성이다")
    void polygonMasterEnablesPolygonOnly() {
        PresetCodeView view = codeViewFor(11L); // VEHICLE/POLYGON
        assertThat(view.bboxEnabled()).isFalse();
        assertThat(view.polygonEnabled()).isTrue();
    }

    @Test
    @DisplayName("마스터_형태_POINT_SKELETON이면_두_토글_모두_비활성이다")
    void pointAndSkeletonDisableBothToggles() {
        PresetCodeView point = codeViewFor(12L);     // HEAD/POINT
        PresetCodeView skeleton = codeViewFor(13L);  // POSE/SKELETON
        assertThat(point.bboxEnabled()).isFalse();
        assertThat(point.polygonEnabled()).isFalse();
        assertThat(skeleton.bboxEnabled()).isFalse();
        assertThat(skeleton.polygonEnabled()).isFalse();
    }

    // ----- AC4: 미연결 -----

    @Test
    @DisplayName("labelId가_null인_미연결코드는_linked_false와_legacy명으로_노출된다")
    void unlinkedCodeShowsLegacyName() {
        // labelId 없이 legacy 코드 문자열만 가진 프리셋(백필 미매칭 레거시).
        LsLabelPreset preset = LsLabelPreset.create("legacy", "", List.of("OLD_CODE"), null);
        when(repository.findAllWithCodes()).thenReturn(List.of(preset));

        PresetCodeView code = service.list().get(0).codes().get(0);

        assertThat(code.linked()).isFalse();
        assertThat(code.labelId()).isNull();
        assertThat(code.labelName()).isEqualTo("OLD_CODE");
        assertThat(code.labelType()).isNull();
        assertThat(code.bboxEnabled()).isFalse();
        assertThat(code.polygonEnabled()).isFalse();
    }

    // ----- 검증: 마스터 없는/soft delete labelId -----

    @Test
    @DisplayName("마스터에_없는_labelId로_코드추가시_400")
    void unknownLabelIdRejected() {
        when(repository.existsByPresetNm(any())).thenReturn(false);

        assertThatThrownBy(() -> service.create("p", "", List.of(9999L), null))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("soft_delete된(USE_YN_N)_labelId로_코드추가시_400")
    void softDeletedLabelIdRejected() {
        when(repository.existsByPresetNm(any())).thenReturn(false);
        // findActiveByIds 는 활성 라벨만 돌려주므로 soft delete 된 id(77)는 결과에서 빠진다 → 400.
        assertThatThrownBy(() -> service.create("p", "", List.of(77L), null))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("음수_labelId는_검증에서_거부된다")
    void negativeLabelIdRejected() {
        when(repository.existsByPresetNm(any())).thenReturn(false);
        assertThatThrownBy(() -> service.create("p", "", List.of(-1L), null))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    // ----- HIGH: N+1 방지 (배치 조회 1회) -----

    @Test
    @DisplayName("프리셋_목록_조회시_코드_라벨_join이_N플러스1을_유발하지_않는다")
    void listDoesNotTriggerNPlusOne() {
        // 여러 프리셋 × 여러 코드라도 마스터 조회는 정확히 1회(배치)여야 한다.
        LsLabelPreset p1 = LsLabelPreset.createWithOptions(
                "p1", "", List.of(new LabelCodeSpec(10L, null), new LabelCodeSpec(11L, null)), null);
        LsLabelPreset p2 = LsLabelPreset.createWithOptions(
                "p2", "", List.of(new LabelCodeSpec(12L, null), new LabelCodeSpec(13L, null)), null);
        when(repository.findAllWithCodes()).thenReturn(List.of(p1, p2));

        List<PresetView> views = service.list();

        assertThat(views).hasSize(2);
        // 코드가 4건이어도 findActiveByIds 는 단 1회 호출(코드별 반복 조회 금지).
        verify(labelMasterService, times(1)).findActiveByIds(anyCollection());
    }

    // ----- helper -----

    /** labelId 1건짜리 프리셋을 list() 로 조회해 첫 코드 뷰를 얻는다. */
    private PresetCodeView codeViewFor(long labelId) {
        LsLabelPreset preset = LsLabelPreset.createWithOptions(
                "p", "", List.of(new LabelCodeSpec(labelId, null)), null);
        when(repository.findAllWithCodes()).thenReturn(List.of(preset));
        return service.list().get(0).codes().get(0);
    }
}
