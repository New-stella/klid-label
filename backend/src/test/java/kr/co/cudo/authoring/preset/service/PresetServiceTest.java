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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 프리셋 응용 서비스 — 「이벤트 + 라벨」 축(V17) 검증.
 *
 * <p>이벤트 검증 축이 <b>필터 옵션</b>에서 <b>등록 여부</b>로 바뀐 것이 이 시험의 핵심이다.
 * 필터에서 제외된 대분류의 유형도 영상이 들어오면 오토라벨이 돌아야 하므로 프리셋을 만들 수 있어야 한다.
 */
class PresetServiceTest {

    /** 등록 + 필터 노출 유형. */
    private static final String EV_FLOOD = "EV01000101";   // 침수(범람)
    private static final String EV_FIRE = "EV02000101";    // 화재
    /** 등록돼 있으나 제외 대분류(08 배회)라 필터 옵션에서 빠진 유형 — 프리셋은 만들 수 있어야 한다. */
    private static final String EV_EXCLUDED = "EV08000101";
    /** 등록돼 있으나 비수집이라 필터 옵션에서 빠진 유형. */
    private static final String EV_NOT_COLLECTED = "EV07000201";
    /** 등록돼 있으나 표시명이 하나도 없는 유형 — 표시명은 코드로 폴백한다. */
    private static final String EV_NO_NAME = "EV09000101";
    /** 마스터에 등록되지 않은 유형. */
    private static final String EV_UNREGISTERED = "EV99999999";

    /** 등록된 전체 유형(수집/비수집·제외 무관) — 판정의 원천. */
    private static final Set<String> REGISTERED = Set.of(
            EV_FLOOD, EV_FIRE, EV_EXCLUDED, EV_NOT_COLLECTED, EV_NO_NAME);

    /** 표시명 맵 — 이름이 없는 유형(EV_NO_NAME)은 담기지 않는다(codeLabelMap 계약). */
    private static final Map<String, String> EVENT_NAMES = Map.of(
            EV_FLOOD, "침수(범람)",
            EV_FIRE, "화재",
            EV_EXCLUDED, "배회",
            EV_NOT_COLLECTED, "기타 상황");

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
        lenient().when(eventTypeService.registeredCodes()).thenReturn(REGISTERED);
        lenient().when(eventTypeService.codeLabelMap()).thenReturn(EVENT_NAMES);
        // resolveLabel 은 이름이 없으면 원문(코드) 폴백 — 실제 구현과 같은 계약으로 스텁한다.
        lenient().when(eventTypeService.resolveLabel(anyString()))
                .thenAnswer(inv -> EVENT_NAMES.getOrDefault(inv.getArgument(0), inv.getArgument(0)));
        // 필터 옵션 축은 저장 경로가 더 이상 쓰지 않는다 — 스텁만 두고 호출 여부를 단언한다.
        lenient().when(eventTypeService.validFilterKeys()).thenReturn(Set.of(EV_FLOOD, EV_FIRE));
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

    private void stubSaveEcho() {
        when(repository.saveAndFlush(any(LsLabelPreset.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    // ----- AC-107 / AC-108: 이벤트 + 라벨 둘만, 이벤트는 필수 -----

    @Test
    @DisplayName("create_는_이벤트와_라벨만으로_저장된다")
    void createTakesEventAndLabelsOnly() {
        stubSaveEcho();

        PresetView saved = service.create(List.of(10L), EV_FIRE);

        assertThat(saved.eventTypeCd()).isEqualTo(EV_FIRE);
        assertThat(saved.eventTypeNm()).isEqualTo("화재");
        assertThat(saved.codes()).hasSize(1);
        assertThat(saved.codes().get(0).labelId()).isEqualTo(10L);
    }

    @Test
    @DisplayName("create_이벤트유형이_비어있으면_INVALID_INPUT_이고_저장하지_않는다")
    void createWithoutEventTypeRejected() {
        for (String blank : new String[]{null, "", "   "}) {
            assertThatThrownBy(() -> service.create(List.of(10L), blank))
                    .as("blank=[%s]", blank)
                    .isInstanceOf(CustomException.class)
                    .satisfies(ex -> {
                        CustomException ce = (CustomException) ex;
                        assertThat(ce.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT);
                        assertThat(ce.getMessage()).contains("이벤트");
                    });
        }
        verify(repository, never()).saveAndFlush(any(LsLabelPreset.class));
    }

    @Test
    @DisplayName("create_등록되지_않은_이벤트유형이면_INVALID_INPUT")
    void createWithUnregisteredEventRejected() {
        assertThatThrownBy(() -> service.create(List.of(10L), EV_UNREGISTERED))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    // ----- 핵심 축 전환: 필터 옵션이 아니라 등록 여부로 판정한다 -----

    @Test
    @DisplayName("create_필터에서_제외된_대분류의_이벤트도_등록돼_있으면_프리셋을_만들_수_있다")
    void createAllowsExcludedClassEventBecauseItIsRegistered() {
        stubSaveEcho();

        PresetView saved = service.create(List.of(10L), EV_EXCLUDED);

        assertThat(saved.eventTypeCd()).isEqualTo(EV_EXCLUDED);
        // 제외는 필터 드롭다운에서 감추는 것일 뿐, 그 유형의 영상이 들어오면 오토라벨이 돌아야 한다.
        verify(eventTypeService, never()).validFilterKeys();
    }

    @Test
    @DisplayName("create_비수집_이벤트유형도_등록돼_있으면_프리셋을_만들_수_있다")
    void createAllowsNotCollectedEvent() {
        stubSaveEcho();

        assertThat(service.create(List.of(10L), EV_NOT_COLLECTED).eventTypeCd())
                .isEqualTo(EV_NOT_COLLECTED);
        verify(eventTypeService, never()).validFilterKeys();
    }

    @Test
    @DisplayName("update_필터에서_제외된_대분류로_재매핑해도_등록돼_있으면_허용된다")
    void updateAllowsRemappingToExcludedClassEvent() {
        LsLabelPreset existing = LsLabelPreset.createWithOptions(
                List.of(new LabelCodeSpec(10L, null)), EV_FLOOD);
        when(repository.findById(1L)).thenReturn(Optional.of(existing));
        when(repository.existsByEventTypeCdAndPresetIdNot(EV_EXCLUDED, 1L)).thenReturn(false);
        stubSaveEcho();

        PresetView updated = service.update(1L, List.of(11L), EV_EXCLUDED);

        assertThat(updated.eventTypeCd()).isEqualTo(EV_EXCLUDED);
        verify(eventTypeService, never()).validFilterKeys();
    }

    // ----- AC-109: 이벤트 중복은 409 이고 어느 이벤트인지 밝힌다 -----

    @Test
    @DisplayName("create_이미_프리셋이_있는_이벤트면_CONFLICT_이고_어느_이벤트인지_메시지에_담긴다")
    void createWithDuplicateEventReturnsConflictWithEventInfo() {
        when(repository.existsByEventTypeCd(EV_FLOOD)).thenReturn(true);

        assertThatThrownBy(() -> service.create(List.of(10L), EV_FLOOD))
                .isInstanceOf(CustomException.class)
                .satisfies(ex -> {
                    CustomException ce = (CustomException) ex;
                    assertThat(ce.getErrorCode()).isEqualTo(ErrorCode.CONFLICT);
                    assertThat(ce.getMessage()).contains("침수(범람)").contains(EV_FLOOD);
                });
        verify(repository, never()).saveAndFlush(any(LsLabelPreset.class));
    }

    @Test
    @DisplayName("create_동시요청_경합으로_저장시점_UNIQUE_위반이_나도_CONFLICT_로_변환된다")
    void createConcurrentUniqueViolationBecomesConflict() {
        when(repository.existsByEventTypeCd(EV_FLOOD)).thenReturn(false);
        when(repository.saveAndFlush(any(LsLabelPreset.class)))
                .thenThrow(new DataIntegrityViolationException("UK_LS_LABEL_PRESET_EVNT"));

        assertThatThrownBy(() -> service.create(List.of(10L), EV_FLOOD))
                .isInstanceOf(CustomException.class)
                .satisfies(ex -> {
                    CustomException ce = (CustomException) ex;
                    assertThat(ce.getErrorCode()).isEqualTo(ErrorCode.CONFLICT);
                    assertThat(ce.getMessage()).contains("다른 프리셋에 매핑된 이벤트");
                });
    }

    @Test
    @DisplayName("update_다른_프리셋이_쓰는_이벤트로_재매핑하면_CONFLICT")
    void updateToEventOwnedByOtherPresetReturnsConflict() {
        LsLabelPreset existing = LsLabelPreset.createWithOptions(
                List.of(new LabelCodeSpec(10L, null)), EV_FLOOD);
        when(repository.findById(1L)).thenReturn(Optional.of(existing));
        when(repository.existsByEventTypeCdAndPresetIdNot(EV_FIRE, 1L)).thenReturn(true);

        assertThatThrownBy(() -> service.update(1L, List.of(10L), EV_FIRE))
                .isInstanceOf(CustomException.class)
                .satisfies(ex -> {
                    CustomException ce = (CustomException) ex;
                    assertThat(ce.getErrorCode()).isEqualTo(ErrorCode.CONFLICT);
                    assertThat(ce.getMessage()).contains("화재").contains(EV_FIRE);
                });
        verify(repository, never()).saveAndFlush(any(LsLabelPreset.class));
    }

    @Test
    @DisplayName("update_시_이벤트_UNIQUE_위반은_CONFLICT_로_변환")
    void updateWithDuplicateEventThrowsConflict() {
        LsLabelPreset existing = LsLabelPreset.createWithOptions(
                List.of(new LabelCodeSpec(10L, null)), EV_FIRE);
        when(repository.findById(1L)).thenReturn(Optional.of(existing));
        when(repository.existsByEventTypeCdAndPresetIdNot(EV_FLOOD, 1L)).thenReturn(false);
        when(repository.saveAndFlush(any(LsLabelPreset.class)))
                .thenThrow(new DataIntegrityViolationException("UK_LS_LABEL_PRESET_EVNT"));

        assertThatThrownBy(() -> service.update(1L, List.of(10L), EV_FLOOD))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    // ----- 수정: 매핑값이 실제로 바뀔 때만 검증한다 -----

    @Test
    @DisplayName("update_매핑을_그대로_둔_편집은_이벤트_검증을_다시_하지_않는다")
    void updateKeepingSameEventSkipsValidation() {
        // 나중에 제외 대상이 된 이벤트에 이미 매핑된 프리셋도 라벨만 고칠 수 있어야 한다.
        LsLabelPreset existing = LsLabelPreset.createWithOptions(
                List.of(new LabelCodeSpec(10L, null)), EV_EXCLUDED);
        when(repository.findById(1L)).thenReturn(Optional.of(existing));
        stubSaveEcho();

        PresetView updated = service.update(1L, List.of(11L), EV_EXCLUDED);

        assertThat(updated.eventTypeCd()).isEqualTo(EV_EXCLUDED);
        assertThat(updated.codes()).hasSize(1);
        assertThat(updated.codes().get(0).labelId()).isEqualTo(11L);
        verify(eventTypeService, never()).registeredCodes();
        verify(repository, never()).existsByEventTypeCdAndPresetIdNot(anyString(), any());
    }

    @Test
    @DisplayName("update_이벤트유형을_비우려_하면_INVALID_INPUT")
    void updateClearingEventRejected() {
        LsLabelPreset existing = LsLabelPreset.createWithOptions(
                List.of(new LabelCodeSpec(10L, null)), EV_FLOOD);
        when(repository.findById(1L)).thenReturn(Optional.of(existing));

        for (String blank : new String[]{null, "", "   "}) {
            assertThatThrownBy(() -> service.update(1L, List.of(10L), blank))
                    .as("blank=[%s]", blank)
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_INPUT);
        }
    }

    @Test
    @DisplayName("update_등록되지_않은_이벤트유형으로_재매핑하면_INVALID_INPUT")
    void updateRejectsUnregisteredEvent() {
        LsLabelPreset existing = LsLabelPreset.createWithOptions(
                List.of(new LabelCodeSpec(10L, null)), EV_FLOOD);
        when(repository.findById(1L)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.update(1L, List.of(10L), EV_UNREGISTERED))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("update_존재하지않는_프리셋이면_NOT_FOUND")
    void updateMissingPresetThrowsNotFound() {
        when(repository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(404L, List.of(10L), EV_FLOOD))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("create_중복labelId_요청시_코드는_1건으로_저장된다")
    void createDedupsDuplicateLabelId() {
        stubSaveEcho();

        // 같은 labelId(10) 를 3번 넣어도 도메인 dedup 으로 코드는 1건이어야 한다.
        PresetView saved = service.create(List.of(10L, 10L, 10L), EV_FLOOD);

        assertThat(saved.codes()).hasSize(1);
        assertThat(saved.codes().get(0).labelId()).isEqualTo(10L);
    }

    // ----- AC-110: 목록은 이벤트 표시명을 함께 내려준다 -----

    @Test
    @DisplayName("list_각_프리셋에_이벤트_표시명이_채워진다")
    void listFillsEventDisplayName() {
        when(repository.findAllWithCodes()).thenReturn(List.of(presetOn(EV_FLOOD, 10L)));

        PresetView view = service.list().get(0);

        assertThat(view.eventTypeCd()).isEqualTo(EV_FLOOD);
        assertThat(view.eventTypeNm()).isEqualTo("침수(범람)");
    }

    @Test
    @DisplayName("list_필터에서_제외된_대분류의_프리셋도_코드가_아니라_이름으로_읽힌다")
    void listFillsNameEvenForExcludedClassEvent() {
        when(repository.findAllWithCodes()).thenReturn(List.of(presetOn(EV_EXCLUDED, 10L)));

        PresetView view = service.list().get(0);

        assertThat(view.eventTypeNm())
                .as("노출 옵션 목록으로 역해석하면 이 유형이 코드 그대로 노출된다")
                .isEqualTo("배회");
    }

    @Test
    @DisplayName("list_표시명이_하나도_없는_이벤트는_유형코드로_폴백한다")
    void listFallsBackToCodeWhenNoDisplayName() {
        when(repository.findAllWithCodes()).thenReturn(List.of(presetOn(EV_NO_NAME, 10L)));

        assertThat(service.list().get(0).eventTypeNm()).isEqualTo(EV_NO_NAME);
    }

    @Test
    @DisplayName("list_labelId있지만_soft_delete된_마스터는_linked_false로_노출된다")
    void listWithLabelIdButDeletedMasterShowsUnlinked() {
        // labelId=77 은 저장돼 있으나 마스터 조회에서 활성 라벨로 나오지 않는다(soft delete/미존재).
        when(repository.findAllWithCodes()).thenReturn(List.of(presetOn(EV_FLOOD, 77L)));

        PresetCodeView code = service.list().get(0).codes().get(0);

        assertThat(code.linked()).isFalse();
        assertThat(code.labelId()).isEqualTo(77L);
        assertThat(code.labelType()).isNull();
        assertThat(code.bboxEnabled()).isFalse();
        assertThat(code.polygonEnabled()).isFalse();
    }

    // ----- 스냅샷 아님 (마스터 실시간 조회) -----

    @Test
    @DisplayName("마스터_라벨명_변경후_프리셋조회시_변경된_라벨명이_반영된다")
    void masterNameChangeIsReflectedOnRead() {
        // 프리셋 코드는 labelId=10 만 저장(라벨명 미저장). 조회 시 마스터가 돌려주는 이름이 그대로 반영돼야 한다.
        when(repository.findAllWithCodes()).thenReturn(List.of(presetOn(EV_FLOOD, 10L)));
        // 마스터가 '변경된 이름'을 돌려주도록 오버라이드.
        when(labelMasterService.findActiveByIds(anyCollection()))
                .thenReturn(Map.of(10L, master(10L, "사람_변경됨", "BBOX")));

        List<PresetView> views = service.list();

        assertThat(views).hasSize(1);
        PresetCodeView code = views.get(0).codes().get(0);
        assertThat(code.linked()).isTrue();
        assertThat(code.labelName()).isEqualTo("사람_변경됨");
    }

    // ----- 형태 마스터 파생 -----

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

    // ----- 미연결 레거시 코드 -----

    @Test
    @DisplayName("labelId가_null인_미연결코드는_linked_false와_legacy명으로_노출된다")
    void unlinkedCodeShowsLegacyName() {
        // labelId 없이 legacy 코드 문자열만 가진 프리셋(백필 미매칭 레거시).
        LsLabelPreset preset = LsLabelPreset.create(List.of("OLD_CODE"), EV_FLOOD);
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
        assertThatThrownBy(() -> service.create(List.of(9999L), EV_FLOOD))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("soft_delete된(USE_YN_N)_labelId로_코드추가시_400")
    void softDeletedLabelIdRejected() {
        // findActiveByIds 는 활성 라벨만 돌려주므로 soft delete 된 id(77)는 결과에서 빠진다 → 400.
        assertThatThrownBy(() -> service.create(List.of(77L), EV_FLOOD))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("음수_labelId는_검증에서_거부된다")
    void negativeLabelIdRejected() {
        assertThatThrownBy(() -> service.create(List.of(-1L), EV_FLOOD))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    // ----- N+1 방지 (배치 조회 각 1회) -----

    @Test
    @DisplayName("프리셋_목록_조회시_라벨과_이벤트명_조회가_각각_1회씩만_수행된다")
    void listDoesNotTriggerNPlusOne() {
        // 여러 프리셋 × 여러 코드라도 마스터 조회·이벤트 표시명 조회는 각각 정확히 1회(배치)여야 한다.
        LsLabelPreset p1 = LsLabelPreset.createWithOptions(
                List.of(new LabelCodeSpec(10L, null), new LabelCodeSpec(11L, null)), EV_FLOOD);
        LsLabelPreset p2 = LsLabelPreset.createWithOptions(
                List.of(new LabelCodeSpec(12L, null), new LabelCodeSpec(13L, null)), EV_FIRE);
        when(repository.findAllWithCodes()).thenReturn(List.of(p1, p2));

        List<PresetView> views = service.list();

        assertThat(views).hasSize(2);
        assertThat(views).extracting(PresetView::eventTypeNm).containsExactly("침수(범람)", "화재");
        // 코드가 4건이어도 findActiveByIds 는 단 1회 호출(코드별 반복 조회 금지).
        verify(labelMasterService, times(1)).findActiveByIds(anyCollection());
        // 프리셋이 2건이어도 이벤트 표시명 맵은 단 1회 조회(프리셋별 개별 조회 금지).
        verify(eventTypeService, times(1)).codeLabelMap();
        verify(eventTypeService, never()).resolveLabel(anyString());
    }

    // ----- helper -----

    private static LsLabelPreset presetOn(String eventTypeCd, long labelId) {
        return LsLabelPreset.createWithOptions(List.of(new LabelCodeSpec(labelId, null)), eventTypeCd);
    }

    /** labelId 1건짜리 프리셋을 list() 로 조회해 첫 코드 뷰를 얻는다. */
    private PresetCodeView codeViewFor(long labelId) {
        when(repository.findAllWithCodes()).thenReturn(List.of(presetOn(EV_FLOOD, labelId)));
        return service.list().get(0).codes().get(0);
    }
}
