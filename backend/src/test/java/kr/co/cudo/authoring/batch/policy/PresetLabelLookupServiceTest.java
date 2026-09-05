package kr.co.cudo.authoring.batch.policy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import kr.co.cudo.authoring.batch.policy.PresetLabelLookupService.AnnotationToggle;
import kr.co.cudo.authoring.eventtype.service.EventTypeService;
import kr.co.cudo.authoring.label.dto.LabelMasterResponse;
import kr.co.cudo.authoring.label.service.LabelMasterService;
import kr.co.cudo.authoring.preset.entity.LsLabelPreset;
import kr.co.cudo.authoring.preset.entity.LsLabelPreset.LabelCodeSpec;
import kr.co.cudo.authoring.preset.repository.LsLabelPresetRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 3 — 오토라벨 경로 마스터 형태 정합.
 *
 * <p>resolve 는 프리셋 코드의 {@code labelId} 를 라벨 마스터로 배치 조회(N+1 금지)하여
 * <b>마스터 검출유형(DTCT_TYPE_CD, COCO 축 정규화)</b> 키의 토글 맵을 만든다. 토글의 bbox/polygon 은
 * 마스터 {@code LBL_TYPE_CD} 에서 {@link kr.co.cudo.authoring.label.domain.LabelGeometry} 로 강제 파생한다.
 *
 * <ul>
 *   <li>BBOX → bbox only, POLYGON → polygon only, POINT/SKELETON → 둘 다 비활성.</li>
 *   <li>labelId null(미연결) · 마스터 미존재/soft-delete 참조 코드 → 토글에서 제외.</li>
 * </ul>
 *
 * <p>Phase 4a — 프리셋은 관제 categoryKey 로 저장되고 영상 이벤트는 상세 EV-코드다.
 * resolve 는 EV-코드를 {@link EventTypeService#filterKeyOf(String)} 로 변환 후 조회한다.
 */
class PresetLabelLookupServiceTest {

    /** 영상의 상세 EV-코드(침수 카테고리 소속). */
    private static final String VIDEO_EV_CODE = "EV01000102";
    /** 위 EV-코드가 속한 카테고리 키(프리셋 저장 단위). */
    private static final String CATEGORY_KEY = "010001";

    private LsLabelPresetRepository presetRepository;
    private EventTypeService eventTypeService;
    private LabelMasterService labelMasterService;
    private PresetLabelLookupService service;
    private ListAppender<ILoggingEvent> logAppender;
    private Logger serviceLogger;

    @BeforeEach
    void setUp() {
        presetRepository = mock(LsLabelPresetRepository.class);
        eventTypeService = mock(EventTypeService.class);
        labelMasterService = mock(LabelMasterService.class);
        // 영상 EV-코드 → categoryKey 변환 기본 스텁(테스트별로 미사용이면 lenient).
        lenient().when(eventTypeService.filterKeyOf(VIDEO_EV_CODE))
                .thenReturn(Optional.of(CATEGORY_KEY));
        service = new PresetLabelLookupService(presetRepository, eventTypeService, labelMasterService);

        serviceLogger = (Logger) LoggerFactory.getLogger(PresetLabelLookupService.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        serviceLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        if (serviceLogger != null && logAppender != null) {
            serviceLogger.detachAppender(logAppender);
            logAppender.stop();
        }
    }

    /** labelId 연결 코드 스펙 (LBL_CD=null). */
    private static LabelCodeSpec idSpec(long labelId) {
        return new LabelCodeSpec(labelId, null);
    }

    private static LabelMasterResponse master(long labelId, String name, String type) {
        // 검출축(DTCT_TYPE_CD)을 라벨명에서 파생 — 기존 키 단언(정규화 라벨명)과 동일 축을 유지한다.
        // 실제 축 검증(한글명 vs COCO)은 togglesKeyedByMasterDtctType 등 전용 테스트가 담당.
        return master(labelId, name, type, name);
    }

    private static LabelMasterResponse master(long labelId, String name, String type, String dtctTypeCd) {
        return new LabelMasterResponse(labelId, name, "#112233", type, 0, "Y", dtctTypeCd);
    }

    private LsLabelPreset presetWithCodes(LabelCodeSpec... specs) {
        LsLabelPreset preset = LsLabelPreset.createWithOptions(List.of(specs), CATEGORY_KEY);
        when(presetRepository.findByEventTypeCd(CATEGORY_KEY)).thenReturn(Optional.of(preset));
        return preset;
    }

    @Test
    @DisplayName("프리셋_labelId코드의_토글이_마스터_검출유형_DTCT_TYPE_CD_키로_조회된다")
    void togglesKeyedByMasterDtctType() {
        // 한글 라벨명("사람"/"차량")과 COCO 검출유형("person"/"car")이 서로 다른 축임을 명확히 한다.
        presetWithCodes(idSpec(1L), idSpec(2L));
        when(labelMasterService.findActiveByIds(any())).thenReturn(Map.of(
                1L, master(1L, "사람", "BBOX", "person"),
                2L, master(2L, "차량", "POLYGON", "car")));

        PresetResolution result = service.resolve(VIDEO_EV_CODE);

        assertThat(result.isResolved()).isTrue();
        // 키는 마스터 검출유형(DTCT_TYPE_CD, COCO) 정규화 — 검출 라벨(d.label())과 동일 축.
        // 한글 라벨명이 아니라 COCO 영문명이 키여야 한다(라벨명축이면 "사람"/"차량"이 되어 검출 매칭 실패).
        assertThat(result.toggles().keySet()).containsExactlyInAnyOrder("person", "car");
    }

    @Test
    @DisplayName("미매핑_dtctTypeCd_null_라벨은_검출불가라_토글에서_제외된다")
    void unmappedDtctTypeExcludedFromToggles() {
        // dtctTypeCd=null(미매핑) 라벨은 애초에 AI 검출되지 않으므로 토글 맵에 null 키로 들어가면 안 된다.
        presetWithCodes(idSpec(1L), idSpec(2L));
        when(labelMasterService.findActiveByIds(any())).thenReturn(Map.of(
                1L, master(1L, "사람", "BBOX", "person"),
                2L, master(2L, "미매핑라벨", "BBOX", null)));

        Set<String> keys = service.resolve(VIDEO_EV_CODE).toggles().keySet();

        assertThat(keys).containsExactly("person");
    }

    @Test
    @DisplayName("프리셋_코드_labelId를_배치조회한다_findLabelIdByDtctType_반복금지_N플러스1_금지")
    void batchLookupNoNPlusOne() {
        presetWithCodes(idSpec(1L), idSpec(2L), idSpec(3L));
        when(labelMasterService.findActiveByIds(any())).thenReturn(Map.of(
                1L, master(1L, "Person", "BBOX"),
                2L, master(2L, "Vehicle", "POLYGON"),
                3L, master(3L, "Fallen", "BBOX")));

        service.resolve(VIDEO_EV_CODE);

        // 단건 findLabelIdByDtctType 반복 금지 — findActiveByIds 1회 배치 조회.
        verify(labelMasterService, times(1)).findActiveByIds(any());
        verify(labelMasterService, never()).findLabelIdByDtctType(anyString());
    }

    @Test
    @DisplayName("마스터_형태_BBOX면_토글은_bbox만_활성")
    void bboxMasterYieldsBboxOnlyToggle() {
        presetWithCodes(idSpec(1L));
        when(labelMasterService.findActiveByIds(any()))
                .thenReturn(Map.of(1L, master(1L, "Person", "BBOX")));

        AnnotationToggle toggle = service.resolve(VIDEO_EV_CODE).toggles().get("person");

        assertThat(toggle.bbox()).isTrue();
        assertThat(toggle.polygon()).isFalse();
    }

    @Test
    @DisplayName("마스터_형태_POLYGON이면_토글은_polygon만_활성")
    void polygonMasterYieldsPolygonOnlyToggle() {
        presetWithCodes(idSpec(2L));
        when(labelMasterService.findActiveByIds(any()))
                .thenReturn(Map.of(2L, master(2L, "Vehicle", "POLYGON")));

        AnnotationToggle toggle = service.resolve(VIDEO_EV_CODE).toggles().get("vehicle");

        assertThat(toggle.bbox()).isFalse();
        assertThat(toggle.polygon()).isTrue();
    }

    @Test
    @DisplayName("마스터_형태_POINT_SKELETON이면_토글은_bbox_polygon_둘다_비활성")
    void pointSkeletonMasterYieldsNoGeometryToggle() {
        presetWithCodes(idSpec(3L), idSpec(4L));
        when(labelMasterService.findActiveByIds(any())).thenReturn(Map.of(
                3L, master(3L, "Nose", "POINT"),
                4L, master(4L, "Pose", "SKELETON")));

        Map<String, AnnotationToggle> map = service.resolve(VIDEO_EV_CODE).toggles();

        assertThat(map.get("nose").bbox()).isFalse();
        assertThat(map.get("nose").polygon()).isFalse();
        assertThat(map.get("pose").bbox()).isFalse();
        assertThat(map.get("pose").polygon()).isFalse();
    }

    @Test
    @DisplayName("미연결_labelId_null_코드는_오토라벨_토글에서_제외된다")
    void unconnectedCodeExcludedFromToggles() {
        // labelId 연결 코드(1) + 미연결 레거시 코드("LEGACY", labelId=null) 혼재.
        presetWithCodes(idSpec(1L), new LabelCodeSpec("LEGACY"));
        when(labelMasterService.findActiveByIds(any()))
                .thenReturn(Map.of(1L, master(1L, "Person", "BBOX")));

        Set<String> keys = service.resolve(VIDEO_EV_CODE).toggles().keySet();

        // 연결된 Person 만 남고, 미연결 코드는 제외.
        assertThat(keys).containsExactly("person");
    }

    @Test
    @DisplayName("soft_delete된_마스터를_참조하는_코드는_토글에서_제외된다")
    void softDeletedMasterReferenceExcluded() {
        presetWithCodes(idSpec(1L), idSpec(9L));
        // 9L 은 soft-delete(USE_YN='N') → findActiveByIds 결과에 없음(활성만 반환).
        when(labelMasterService.findActiveByIds(any()))
                .thenReturn(Map.of(1L, master(1L, "Person", "BBOX")));

        Set<String> keys = service.resolve(VIDEO_EV_CODE).toggles().keySet();

        assertThat(keys).containsExactly("person");
    }

    // ─── 무음 드롭 가시화 (경고 로그) ───

    @Test
    @DisplayName("정규화_키_충돌시_먼저삽입_코드가_이기고_드롭된_코드는_WARN_로그로_가시화된다")
    void duplicateNormalizedKeyWarnsButFirstWins() {
        // "Person"(BBOX) 과 "PERSON"(POLYGON) 은 정규화하면 둘 다 "person" — 키 충돌.
        // 먼저 삽입된 코드(1L, BBOX)가 이기고, 나중 코드(2L)는 조용히 드롭 → WARN 로그로 가시화.
        presetWithCodes(idSpec(1L), idSpec(2L));
        when(labelMasterService.findActiveByIds(any())).thenReturn(Map.of(
                1L, master(1L, "Person", "BBOX"),
                2L, master(2L, "PERSON", "POLYGON")));

        Map<String, AnnotationToggle> map = service.resolve(VIDEO_EV_CODE).toggles();

        // 첫 코드 우선 동작 유지 — person 은 BBOX(bbox=true, polygon=false) 하나만.
        assertThat(map).containsOnlyKeys("person");
        assertThat(map.get("person").bbox()).isTrue();
        assertThat(map.get("person").polygon()).isFalse();

        // 드롭이 WARN 으로 가시화되고 라벨명 키가 포함된다(민감정보 없음).
        long warnCount = logAppender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .filter(e -> e.getFormattedMessage().contains("duplicate normalized label key"))
                .filter(e -> e.getFormattedMessage().contains("key=person"))
                .filter(e -> e.getFormattedMessage().contains("droppedLabelId=2"))
                .count();
        assertThat(warnCount).isGreaterThanOrEqualTo(1L);
    }

    @Test
    @DisplayName("마스터_형태가_미지원_문자열이면_코드제외되고_WARN_로그로_가시화된다")
    void unsupportedGeometryWarnsAndExcludes() {
        // 마스터 CRUD API 가 4값을 강제하므로 정상 유입은 불가하나, 오염 데이터의 무음 드롭을 방어적 가시화.
        presetWithCodes(idSpec(1L), idSpec(2L));
        when(labelMasterService.findActiveByIds(any())).thenReturn(Map.of(
                1L, master(1L, "Person", "BBOX"),
                2L, master(2L, "Corrupt", "TRIANGLE")));

        Map<String, AnnotationToggle> map = service.resolve(VIDEO_EV_CODE).toggles();

        // 미지원 형태 코드는 제외되고 정상 코드만 남는다.
        assertThat(map).containsOnlyKeys("person");

        long warnCount = logAppender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .filter(e -> e.getFormattedMessage().contains("unsupported label geometry"))
                .filter(e -> e.getFormattedMessage().contains("labelId=2"))
                .count();
        assertThat(warnCount).isGreaterThanOrEqualTo(1L);
    }

    @Test
    @DisplayName("모든_코드가_미연결이면_미연결_사유로_판정된다_보류축")
    void allUnconnectedReturnsUnlinked() {
        presetWithCodes(new LabelCodeSpec("A"), new LabelCodeSpec("B"));

        PresetResolution result = service.resolve(VIDEO_EV_CODE);

        assertThat(result.status()).isEqualTo(PresetResolutionStatus.PRESET_UNLINKED);
        assertThat(result.isWithheld()).isTrue();
        assertThat(result.toggles()).isEmpty();
        // labelId 가 하나도 없으면 마스터 배치 조회조차 하지 않는다.
        verify(labelMasterService, never()).findActiveByIds(any());
    }

    @Test
    @DisplayName("labelId는_있으나_활성_마스터가_하나도_없으면_미연결_사유로_판정된다")
    void noActiveMasterReturnsUnlinked() {
        presetWithCodes(idSpec(1L));
        when(labelMasterService.findActiveByIds(any())).thenReturn(Map.of());

        PresetResolution result = service.resolve(VIDEO_EV_CODE);

        assertThat(result.status()).isEqualTo(PresetResolutionStatus.PRESET_UNLINKED);
        assertThat(result.isWithheld()).isTrue();
    }

    @Test
    @DisplayName("프리셋매칭_영상_EV코드를_categoryKey로_변환해_프리셋을_찾는다")
    void togglesForConvertsEvCodeToCategoryKey() {
        presetWithCodes(idSpec(1L), idSpec(3L));
        when(labelMasterService.findActiveByIds(any())).thenReturn(Map.of(
                1L, master(1L, "PERSON", "BBOX"),
                3L, master(3L, "Fallen", "POLYGON")));

        Set<String> result = service.resolve(VIDEO_EV_CODE).toggles().keySet();

        assertThat(result).containsExactlyInAnyOrder("person", "fallen");
        // EV-코드로 직접 조회하지 않고 categoryKey 로만 조회해야 한다.
        verify(presetRepository).findByEventTypeCd(CATEGORY_KEY);
        verify(presetRepository, never()).findByEventTypeCd(VIDEO_EV_CODE);
    }

    @Test
    @DisplayName("배치조회는_프리셋_코드의_labelId_집합으로_수행된다")
    void batchLookupUsesPresetLabelIds() {
        presetWithCodes(idSpec(1L), idSpec(2L));
        when(labelMasterService.findActiveByIds(any())).thenReturn(Map.of(
                1L, master(1L, "Person", "BBOX"),
                2L, master(2L, "Vehicle", "POLYGON")));

        service.resolve(VIDEO_EV_CODE);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Collection<Long>> captor =
                ArgumentCaptor.forClass(java.util.Collection.class);
        verify(labelMasterService).findActiveByIds(captor.capture());
        assertThat(captor.getValue()).containsExactlyInAnyOrder(1L, 2L);
    }

    // ─── fail-safe 경계 (기존 계약 보존) ───

    @Test
    @DisplayName("프리셋매칭_미등록_EV코드는_이벤트유형_미등록_사유로_보류축이_된다")
    void unregisteredEvCodeReturnsEventTypeUnregistered() {
        when(eventTypeService.filterKeyOf("EV99999999")).thenReturn(Optional.empty());

        PresetResolution result = service.resolve("EV99999999");

        assertThat(result.status()).isEqualTo(PresetResolutionStatus.EVENT_TYPE_UNREGISTERED);
        assertThat(result.isWithheld()).isTrue();
        verify(presetRepository, never()).findByEventTypeCd(any());
    }

    @Test
    @DisplayName("eventTypeCd_null이면_변환_조회_없이_이벤트유형_미등록으로_판정된다")
    void nullEventReturnsEmptyWithoutQuery() {
        PresetResolution result = service.resolve(null);

        assertThat(result.status()).isEqualTo(PresetResolutionStatus.EVENT_TYPE_UNREGISTERED);
        assertThat(result.toggles()).isEmpty();
        verify(eventTypeService, never()).filterKeyOf(any());
        verify(presetRepository, never()).findByEventTypeCd(any());
    }

    @Test
    @DisplayName("eventTypeCd_blank이면_변환_조회_없이_이벤트유형_미등록으로_판정된다")
    void blankEventReturnsEmptyWithoutQuery() {
        assertThat(service.resolve("").status()).isEqualTo(PresetResolutionStatus.EVENT_TYPE_UNREGISTERED);
        assertThat(service.resolve("   ").status()).isEqualTo(PresetResolutionStatus.EVENT_TYPE_UNREGISTERED);
        verify(eventTypeService, never()).filterKeyOf(any());
        verify(presetRepository, never()).findByEventTypeCd(any());
    }

    @Test
    @DisplayName("변환된_categoryKey에_매핑된_프리셋이_없으면_프리셋없음_사유로_보류축이_된다")
    void unmappedCategoryReturnsPresetAbsent() {
        when(presetRepository.findByEventTypeCd(CATEGORY_KEY)).thenReturn(Optional.empty());

        PresetResolution result = service.resolve(VIDEO_EV_CODE);

        assertThat(result.status()).isEqualTo(PresetResolutionStatus.PRESET_ABSENT);
        assertThat(result.isWithheld()).isTrue();
    }

    @Test
    @DisplayName("★라벨을_하나도_담지_않은_프리셋은_보류가_아니라_오토라벨_제외_선언이다")
    void emptyCodesPresetIsAutolabelExclusion() {
        LsLabelPreset preset = LsLabelPreset.create(List.of(), CATEGORY_KEY);
        when(presetRepository.findByEventTypeCd(CATEGORY_KEY)).thenReturn(Optional.of(preset));

        PresetResolution result = service.resolve(VIDEO_EV_CODE);

        assertThat(result.status()).isEqualTo(PresetResolutionStatus.PRESET_EMPTY);
        assertThat(result.isAutolabelExcluded()).isTrue();
        // ★사고(미매핑)와 갈린다 — 제외는 보류가 아니므로 배치가 완료로 마감돼야 한다.
        assertThat(result.isWithheld()).isFalse();
    }

    @Test
    @DisplayName("★라벨은_담았으나_전부_검출클래스_미매핑이면_제외가_아니라_보류다")
    void allUnmappedPresetIsWithheldNotExcluded() {
        presetWithCodes(idSpec(1L), idSpec(2L));
        when(labelMasterService.findActiveByIds(any())).thenReturn(Map.of(
                1L, master(1L, "화재", "POLYGON", null),
                2L, master(2L, "연기", "POLYGON", null)));

        PresetResolution result = service.resolve(VIDEO_EV_CODE);

        assertThat(result.status()).isEqualTo(PresetResolutionStatus.PRESET_UNMAPPED);
        assertThat(result.isWithheld()).isTrue();
        assertThat(result.isAutolabelExcluded()).isFalse();
    }

    @Test
    @DisplayName("★fail_open_기본상수는_존재하지_않는다_AnnotationToggle_BOTH_되살리기_금지")
    void noFailOpenDefaultConstant() {
        // 구 상수 AnnotationToggle.BOTH 는 "프리셋을 특정하지 못하면 전 검출을 저장한다"는 fail-open 의
        // 실체였다. 되살리면 이 가드가 깨진다.
        boolean hasBothConstant = java.util.Arrays.stream(AnnotationToggle.class.getDeclaredFields())
                .anyMatch(f -> "BOTH".equals(f.getName()));
        assertThat(hasBothConstant).isFalse();
    }

    @Test
    @DisplayName("normalizeLabelKey_는_trim_소문자_정규화_null안전")
    void normalizeLabelKeyRules() {
        assertThat(PresetLabelLookupService.normalizeLabelKey("  Person ")).isEqualTo("person");
        assertThat(PresetLabelLookupService.normalizeLabelKey("VEHICLE")).isEqualTo("vehicle");
        assertThat(PresetLabelLookupService.normalizeLabelKey(null)).isNull();
    }
}
