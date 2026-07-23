package kr.co.cudo.authoring.batch.step;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataLblAiInfo;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.policy.PresetLabelLookupService;
import kr.co.cudo.authoring.batch.policy.PresetLabelLookupService.AnnotationToggle;
import kr.co.cudo.authoring.batch.repository.LsDataLblAiInfoRepository;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.client.AiServerClient;
import kr.co.cudo.authoring.common.client.dto.Sam2Request;
import kr.co.cudo.authoring.common.client.dto.Sam2Response;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.label.service.LabelMasterService;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 2 Sam2SegmentStep 단위 테스트.
 *
 * <p>검증 포인트:
 * <ul>
 *   <li>signature: {@code run(rawSn, List&lt;BbHint&gt; upstreamHints)}</li>
 *   <li>polygonEnabled=false 라벨은 SAM2 호출 skip + WARN 로그</li>
 *   <li>POLYGON_ONLY 라벨이 BbHint 로만 들어오면 SAM2 호출 후 POLYGON 저장</li>
 *   <li>DB BBOX 와 upstreamHints 둘 다 있을 때 (srcSn, label) 키로 중복 제거</li>
 * </ul>
 */
class Sam2SegmentStepTest {

    private AiServerClient aiServerClient;
    private LsDataSrcRepository srcRepository;
    private LsDataLblRepository lblRepository;
    private LsDataLblAiInfoRepository aiInfoRepository;
    private VideoRepository videoRepository;
    private PresetLabelLookupService presetLabelLookup;
    private LabelMasterService labelMasterService;
    private Sam2SegmentStep step;
    private final java.util.concurrent.atomic.AtomicLong lblSnSeq = new java.util.concurrent.atomic.AtomicLong(1);
    private ListAppender<ILoggingEvent> logAppender;
    private Logger stepLogger;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws IOException {
        aiServerClient = mock(AiServerClient.class);
        srcRepository = mock(LsDataSrcRepository.class);
        lblRepository = mock(LsDataLblRepository.class);
        aiInfoRepository = mock(LsDataLblAiInfoRepository.class);
        videoRepository = mock(VideoRepository.class);
        presetLabelLookup = mock(PresetLabelLookupService.class);
        labelMasterService = mock(LabelMasterService.class);
        // LabelMasterService 기본은 미매핑 (Optional.empty) — 개별 테스트가 필요 시 override.
        when(labelMasterService.findLabelIdByDtctType(anyString())).thenReturn(Optional.empty());
        // Phase 6 — save() 후 LsDataLblAiInfo.create(savedLabel.getLblSn(), ...) 호출되므로 lblSn 부여 필수.
        when(lblRepository.save(any(LsDataLbl.class))).thenAnswer(inv -> {
            LsDataLbl arg = inv.getArgument(0);
            setField(arg, "lblSn", lblSnSeq.getAndIncrement());
            return arg;
        });

        // dummy image files (Phase 4: srcSn 70/80 케이스 추가 — 범위 확장)
        Path rawDir = tempDir.resolve("raw");
        Files.createDirectories(rawDir);
        for (long i = 10L; i <= 90L; i++) {
            Files.write(rawDir.resolve(i + ".jpg"), new byte[]{(byte) 0xFF, (byte) 0xD8});
        }

        when(videoRepository.findById(anyLong())).thenReturn(Optional.empty());
        when(presetLabelLookup.togglesFor(any())).thenReturn(Optional.empty());

        step = new Sam2SegmentStep(aiServerClient, srcRepository, lblRepository, aiInfoRepository,
                videoRepository, presetLabelLookup, labelMasterService,
                new ObjectMapper(), rawDir.toString());

        stepLogger = (Logger) LoggerFactory.getLogger(Sam2SegmentStep.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        stepLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        if (stepLogger != null && logAppender != null) {
            stepLogger.detachAppender(logAppender);
            logAppender.stop();
        }
    }

    private LsDataSrc newSrc(Long srcSn) {
        LsDataSrc src = LsDataSrc.create(1L, srcSn.intValue(), srcSn + ".jpg", null);
        setField(src, "srcSn", srcSn);
        return src;
    }

    private LsDataLbl newBbox(Long srcSn, String label, String pointsJson) {
        LsDataLbl lbl = LsDataLbl.createAutoBbox(srcSn, null, label, pointsJson, BigDecimal.valueOf(0.9), null);
        return lbl;
    }

    private LsDataRaw rawWithEvent(String evntTypeCd) {
        LsDataRaw raw = mock(LsDataRaw.class);
        when(raw.getEvntTypeCd()).thenReturn(evntTypeCd);
        return raw;
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("rawSn_null이면_INVALID_INPUT")
    void nullRawSnRejected() {
        assertThatThrownBy(() -> step.run(null, List.of()))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode().name())
                .isEqualTo("INVALID_INPUT");
    }

    @Test
    @DisplayName("Sam2Step_DB_BBOX_없고_upstreamHints_도_없으면_SAM2_호출_안_함")
    void noBboxNoHintsSkipsSam2() {
        when(srcRepository.findByRawSnOrderByFrameNoAsc(1L))
                .thenReturn(List.of(newSrc(10L)));
        when(lblRepository.findBySrcSnAndAutoLblYn(10L, "Y")).thenReturn(List.of());

        int saved = step.run(1L, List.of());

        assertThat(saved).isZero();
        verify(aiServerClient, never()).segment(any());
        verify(lblRepository, never()).save(any());
    }

    @Test
    @DisplayName("Sam2Step_nested_POINT_CN_BBOX_라벨을_읽어_정상_SegmentJob_생성_DEV_FIX_회귀")
    void nestedBboxPointCnReadByParseBbox() {
        // given — Phase 1 좌표 정규화로 YOLO 가 nested [[x1,y1],[x2,y2]] 로 저장한 BBOX 라벨
        when(srcRepository.findByRawSnOrderByFrameNoAsc(21L))
                .thenReturn(List.of(newSrc(20L)));
        when(lblRepository.findBySrcSnAndAutoLblYn(20L, "Y"))
                .thenReturn(List.of(newBbox(20L, "person", "[[1.0,2.0],[3.0,4.0]]")));
        when(aiServerClient.segment(any(Sam2Request.class)))
                .thenReturn(Mono.just(new Sam2Response(List.of(List.of(1.0, 2.0), List.of(3.0, 4.0)), 0.88)));

        // when
        int saved = step.run(21L, List.of());

        // then — nested POINT_CN 도 flat box prompt 로 변환되어 SAM2 가 호출되고 POLYGON 저장
        assertThat(saved).isEqualTo(1);
        ArgumentCaptor<Sam2Request> reqCaptor = ArgumentCaptor.forClass(Sam2Request.class);
        verify(aiServerClient, times(1)).segment(reqCaptor.capture());
        // SAM2 box prompt 는 flat [x1,y1,x2,y2] 여야 한다.
        assertThat(reqCaptor.getValue().box()).containsExactly(1.0, 2.0, 3.0, 4.0);
        ArgumentCaptor<LsDataLbl> captor = ArgumentCaptor.forClass(LsDataLbl.class);
        verify(lblRepository, times(1)).save(captor.capture());
        assertThat(captor.getValue().getLblTypeCd()).isEqualTo("POLYGON");
        assertThat(captor.getValue().getLabelNm()).isEqualTo("person");
    }

    @Test
    @DisplayName("Sam2Step_DB_BBOX_있으면_SAM2_호출_후_POLYGON_저장")
    void dbBboxTriggersSam2() {
        when(srcRepository.findByRawSnOrderByFrameNoAsc(2L))
                .thenReturn(List.of(newSrc(20L)));
        when(lblRepository.findBySrcSnAndAutoLblYn(20L, "Y"))
                .thenReturn(List.of(newBbox(20L, "person", "[1.0,2.0,3.0,4.0]")));
        when(aiServerClient.segment(any(Sam2Request.class)))
                .thenReturn(Mono.just(new Sam2Response(List.of(List.of(1.0, 2.0), List.of(3.0, 4.0)), 0.88)));

        int saved = step.run(2L, List.of());

        assertThat(saved).isEqualTo(1);
        ArgumentCaptor<LsDataLbl> captor = ArgumentCaptor.forClass(LsDataLbl.class);
        verify(lblRepository, times(1)).save(captor.capture());
        LsDataLbl saved1 = captor.getValue();
        assertThat(saved1.getLblTypeCd()).isEqualTo("POLYGON");
        assertThat(saved1.getLabelNm()).isEqualTo("person");
        assertThat(saved1.getAutoLblYn()).isEqualTo("Y");
    }

    @Test
    @DisplayName("Sam2Step_4192점_응답_폴리곤은_저장전_1000점_이하로_simplify되어_저장된다")
    void oversizedSam2PolygonIsCappedBeforeSave() {
        when(srcRepository.findByRawSnOrderByFrameNoAsc(2L))
                .thenReturn(List.of(newSrc(20L)));
        when(lblRepository.findBySrcSnAndAutoLblYn(20L, "Y"))
                .thenReturn(List.of(newBbox(20L, "person", "[1.0,2.0,3.0,4.0]")));
        // SAM2 가 4192점 폐곡선(원 근사)을 응답 — 적재 시 무제한이던 회귀를 cap 으로 차단.
        java.util.List<java.util.List<Double>> dense = new java.util.ArrayList<>();
        for (int i = 0; i < 4192; i++) {
            double t = 2 * Math.PI * i / 4192;
            dense.add(List.of(1000 + Math.cos(t) * 500, 1000 + Math.sin(t) * 500));
        }
        when(aiServerClient.segment(any(Sam2Request.class)))
                .thenReturn(Mono.just(new Sam2Response(dense, 0.9)));

        int saved = step.run(2L, List.of());

        assertThat(saved).isEqualTo(1);
        ArgumentCaptor<LsDataLbl> captor = ArgumentCaptor.forClass(LsDataLbl.class);
        verify(lblRepository, times(1)).save(captor.capture());
        // 저장된 POLYGON 좌표가 라벨 저장 검증 상한 이하여야 한다.
        var saved1 = captor.getValue();
        var pts = kr.co.cudo.authoring.common.util.LabelPointSerializer.fromJson(
                saved1.getPointCn(), new ObjectMapper());
        assertThat(pts.size())
                .isLessThanOrEqualTo(kr.co.cudo.authoring.label.service.LabelService.MAX_POINTS_PER_LABEL);
        assertThat(pts.size()).isGreaterThanOrEqualTo(3);
    }

    @Test
    @DisplayName("Sam2Step_polygonEnabled_false_라벨은_SAM2_호출_안_함_그리고_경고_로그")
    void polygonDisabledLabelSkipsSam2WithWarn() {
        LsDataRaw raw = rawWithEvent("EVT_FALL");
        when(videoRepository.findById(3L)).thenReturn(Optional.of(raw));
        // person 은 bbox-only (polygon=false)
        when(presetLabelLookup.togglesFor("EVT_FALL"))
                .thenReturn(Optional.of(Map.of("person", new AnnotationToggle(true, false))));
        when(srcRepository.findByRawSnOrderByFrameNoAsc(3L))
                .thenReturn(List.of(newSrc(30L)));
        // YOLO 가 BBOX 를 저장해 둠 (BBOX_ONLY 라벨)
        when(lblRepository.findBySrcSnAndAutoLblYn(30L, "Y"))
                .thenReturn(List.of(newBbox(30L, "person", "[1.0,2.0,3.0,4.0]")));

        int saved = step.run(3L, List.of());

        // SAM2 미호출, POLYGON 미저장
        assertThat(saved).isZero();
        verify(aiServerClient, never()).segment(any());
        verify(lblRepository, never()).save(any());

        // WARN 로그가 출력되어야 함
        long warnCount = logAppender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .filter(e -> e.getFormattedMessage().contains("sam2 skipped")
                        && e.getFormattedMessage().contains("polygonDisabled"))
                .count();
        assertThat(warnCount).isGreaterThanOrEqualTo(1L);
    }

    @Test
    @DisplayName("Sam2Step_POLYGON_ONLY_라벨이_BbHint_로만_들어오면_SAM2_호출_후_POLYGON_저장")
    void polygonOnlyHintProcessed() {
        LsDataRaw raw = rawWithEvent("EVT_FALL");
        when(videoRepository.findById(4L)).thenReturn(Optional.of(raw));
        // person 은 polygon-only (bbox=false, polygon=true)
        when(presetLabelLookup.togglesFor("EVT_FALL"))
                .thenReturn(Optional.of(Map.of("person", new AnnotationToggle(false, true))));
        when(srcRepository.findByRawSnOrderByFrameNoAsc(4L))
                .thenReturn(List.of(newSrc(40L)));
        // DB BBOX 없음 (YOLO 가 BBOX 저장을 skip 했기 때문)
        when(lblRepository.findBySrcSnAndAutoLblYn(40L, "Y")).thenReturn(List.of());
        when(aiServerClient.segment(any(Sam2Request.class)))
                .thenReturn(Mono.just(new Sam2Response(List.of(List.of(1.0, 2.0), List.of(3.0, 4.0)), 0.77)));

        List<BbHint> hints = List.of(new BbHint(40L, "person", List.of(1.0, 2.0, 3.0, 4.0), 0.92, null));
        int saved = step.run(4L, hints);

        assertThat(saved).isEqualTo(1);
        verify(aiServerClient, times(1)).segment(any());
        ArgumentCaptor<LsDataLbl> captor = ArgumentCaptor.forClass(LsDataLbl.class);
        verify(lblRepository, times(1)).save(captor.capture());
        LsDataLbl saved1 = captor.getValue();
        assertThat(saved1.getLblTypeCd()).isEqualTo("POLYGON");
        assertThat(saved1.getLabelNm()).isEqualTo("person");
    }

    @Test
    @DisplayName("Sam2Step_DB_BBOX_와_upstreamHints_둘_다_있으면_중복_제거_후_처리")
    void duplicateBboxAndHintMergedOnce() {
        LsDataRaw raw = rawWithEvent("EVT_FALL");
        when(videoRepository.findById(5L)).thenReturn(Optional.of(raw));
        // person=BOTH 인 경우 DB BBOX 와 hint 가 동시에 들어옴 (오케스트레이션 일관성)
        when(presetLabelLookup.togglesFor("EVT_FALL"))
                .thenReturn(Optional.of(Map.of("person", AnnotationToggle.BOTH)));
        when(srcRepository.findByRawSnOrderByFrameNoAsc(5L))
                .thenReturn(List.of(newSrc(50L)));
        when(lblRepository.findBySrcSnAndAutoLblYn(50L, "Y"))
                .thenReturn(List.of(newBbox(50L, "person", "[1.0,2.0,3.0,4.0]")));
        when(aiServerClient.segment(any(Sam2Request.class)))
                .thenReturn(Mono.just(new Sam2Response(List.of(List.of(1.0, 2.0)), 0.91)));

        // 동일 (srcSn=50, label="person") 의 hint 가 별도로 들어옴 — 중복
        List<BbHint> hints = List.of(new BbHint(50L, "person", List.of(1.0, 2.0, 3.0, 4.0), 0.92, null));
        int saved = step.run(5L, hints);

        // 한 번만 처리되어야 함 (DB BBOX 우선 또는 dedup) — SAM2 1회 호출, POLYGON 1건 저장
        assertThat(saved).isEqualTo(1);
        verify(aiServerClient, times(1)).segment(any());
        verify(lblRepository, times(1)).save(any());
    }

    // ─── Phase 3: 토글 미포함 라벨 노이즈 제거 + 라벨명 정규화 (YOLO 와 대칭) ───

    @Test
    @DisplayName("Sam2Step_매핑에_없는_라벨은_SAM2_호출없이_노이즈제거된다")
    void unmappedLabelDroppedAsNoise() {
        // 도달 경로(실재): 프리셋 토글 맵에 "person" 만 존재하는데, DB 에는 프리셋에 없는 라벨명("car")의
        // 레거시/수동 BBOX 가 남아있거나, YOLO→SAM2 사이 프리셋 변경 레이스로 미포함 라벨이 유입된 경우.
        // resolveToggle 이 null 을 반환 → SAM2 미호출·POLYGON 미저장(노이즈 제거).
        // YoloAutolabelStepTest 의 "car 필터링"(evtFallFiltersToPersonOnly)과 대칭 분기.
        LsDataRaw raw = rawWithEvent("EVT_FALL");
        when(videoRepository.findById(31L)).thenReturn(Optional.of(raw));
        when(presetLabelLookup.togglesFor("EVT_FALL"))
                .thenReturn(Optional.of(Map.of("person", AnnotationToggle.BOTH)));
        when(srcRepository.findByRawSnOrderByFrameNoAsc(31L))
                .thenReturn(List.of(newSrc(30L)));
        // 프리셋 토글 맵에 없는 "car" 라벨의 DB BBOX
        when(lblRepository.findBySrcSnAndAutoLblYn(30L, "Y"))
                .thenReturn(List.of(newBbox(30L, "car", "[1.0,2.0,3.0,4.0]")));

        int saved = step.run(31L, List.of());

        // 미포함 라벨은 SAM2 호출 없이 제거되어야 한다.
        assertThat(saved).isZero();
        verify(aiServerClient, never()).segment(any());
        verify(lblRepository, never()).save(any());
    }

    @Test
    @DisplayName("Sam2Step_라벨명_대소문자공백_정규화되어_토글매칭된다")
    void labelNameNormalizedForToggleMatch() {
        // 경계: 토글 맵 키는 정규화된 "person" 이지만 DB BBOX 라벨명은 대소문자·공백 차이("  Person ").
        // normalizeLabelKey(trim + 소문자)로 검출/DB 라벨을 정규화해 토글 축을 일치시키므로 SAM2 호출·
        // POLYGON 저장이 이뤄진다. (YOLO 에는 대소문자 정규화 경계가 있으나 SAM2 엔 없던 갭.)
        LsDataRaw raw = rawWithEvent("EVT_FALL");
        when(videoRepository.findById(41L)).thenReturn(Optional.of(raw));
        when(presetLabelLookup.togglesFor("EVT_FALL"))
                .thenReturn(Optional.of(Map.of("person", AnnotationToggle.BOTH)));
        when(srcRepository.findByRawSnOrderByFrameNoAsc(41L))
                .thenReturn(List.of(newSrc(40L)));
        when(lblRepository.findBySrcSnAndAutoLblYn(40L, "Y"))
                .thenReturn(List.of(newBbox(40L, "  Person ", "[1.0,2.0,3.0,4.0]")));
        when(aiServerClient.segment(any(Sam2Request.class)))
                .thenReturn(Mono.just(new Sam2Response(List.of(List.of(1.0, 2.0), List.of(3.0, 4.0)), 0.88)));

        int saved = step.run(41L, List.of());

        // 정규화로 토글 매칭 성공 → SAM2 1회 호출, POLYGON 1건 저장.
        assertThat(saved).isEqualTo(1);
        verify(aiServerClient, times(1)).segment(any());
        verify(lblRepository, times(1)).save(any());
    }

    // ─── Phase 4: dedup 키에 trackId 반영 ───

    @Test
    @DisplayName("Sam2Step_dedup_은_label_과_trackId_조합_기준_다른_trackId_는_별도_처리")
    void dedupKeyIncludesTrackId() {
        LsDataRaw raw = rawWithEvent("EVT_FALL");
        when(videoRepository.findById(70L)).thenReturn(Optional.of(raw));
        when(presetLabelLookup.togglesFor("EVT_FALL"))
                .thenReturn(Optional.of(Map.of("person", AnnotationToggle.BOTH)));
        when(srcRepository.findByRawSnOrderByFrameNoAsc(70L))
                .thenReturn(List.of(newSrc(70L)));
        // 같은 srcSn + 같은 라벨 "person" 이지만 trackId 가 1 인 DB BBOX 1건만 존재
        LsDataLbl bboxWithTrack = LsDataLbl.createAutoBbox(70L, null, "person", "[1.0,2.0,3.0,4.0]",
                BigDecimal.valueOf(0.9), "1");
        when(lblRepository.findBySrcSnAndAutoLblYn(70L, "Y"))
                .thenReturn(List.of(bboxWithTrack));
        when(aiServerClient.segment(any(Sam2Request.class)))
                .thenReturn(Mono.just(new Sam2Response(List.of(List.of(1.0, 2.0)), 0.85)));

        // hint 는 같은 라벨 "person" 이지만 trackId=2 → 별도 객체이므로 별도 처리되어야 함
        List<BbHint> hints = List.of(new BbHint(70L, "person", List.of(5.0, 6.0, 7.0, 8.0), 0.81, 2));
        int saved = step.run(70L, hints);

        // SAM2 2회 호출, POLYGON 2건 저장 (트랙 분리)
        assertThat(saved).isEqualTo(2);
        verify(aiServerClient, times(2)).segment(any());
        verify(lblRepository, times(2)).save(any());
    }

    @Test
    @DisplayName("Sam2Step_trackId_null_시_기존_label_기준_dedup_으로_fallback")
    void dedupFallbackWhenTrackIdNull() {
        LsDataRaw raw = rawWithEvent("EVT_FALL");
        when(videoRepository.findById(80L)).thenReturn(Optional.of(raw));
        when(presetLabelLookup.togglesFor("EVT_FALL"))
                .thenReturn(Optional.of(Map.of("person", AnnotationToggle.BOTH)));
        when(srcRepository.findByRawSnOrderByFrameNoAsc(80L))
                .thenReturn(List.of(newSrc(80L)));
        // DB BBOX 는 trackId=null (legacy/저신뢰 fallback)
        when(lblRepository.findBySrcSnAndAutoLblYn(80L, "Y"))
                .thenReturn(List.of(newBbox(80L, "person", "[1.0,2.0,3.0,4.0]")));
        when(aiServerClient.segment(any(Sam2Request.class)))
                .thenReturn(Mono.just(new Sam2Response(List.of(List.of(1.0, 2.0)), 0.85)));

        // hint 도 trackId=null → 같은 (srcSn, label, null) 키로 dedup
        List<BbHint> hints = List.of(new BbHint(80L, "person", List.of(1.0, 2.0, 3.0, 4.0), 0.92, null));
        int saved = step.run(80L, hints);

        // 한 번만 처리 (트랙 null 이면 라벨 기준 dedup 으로 fallback)
        assertThat(saved).isEqualTo(1);
        verify(aiServerClient, times(1)).segment(any());
        verify(lblRepository, times(1)).save(any());
    }

    @Test
    @DisplayName("Sam2Step_DB_BBOX_와_upstreamHints_가_다른_라벨이면_둘_다_처리")
    void differentLabelsBothProcessed() {
        LsDataRaw raw = rawWithEvent("EVT_TRESPASS");
        when(videoRepository.findById(6L)).thenReturn(Optional.of(raw));
        // person=BOTH, car=polygon-only
        when(presetLabelLookup.togglesFor("EVT_TRESPASS"))
                .thenReturn(Optional.of(Map.of(
                        "person", AnnotationToggle.BOTH,
                        "car", new AnnotationToggle(false, true)
                )));
        when(srcRepository.findByRawSnOrderByFrameNoAsc(6L))
                .thenReturn(List.of(newSrc(60L)));
        // DB BBOX: person 만 (BOTH → BBOX 저장됨)
        when(lblRepository.findBySrcSnAndAutoLblYn(60L, "Y"))
                .thenReturn(List.of(newBbox(60L, "person", "[1.0,2.0,3.0,4.0]")));
        when(aiServerClient.segment(any(Sam2Request.class)))
                .thenReturn(Mono.just(new Sam2Response(List.of(List.of(1.0, 2.0)), 0.85)));

        // car 는 polygon-only → hint 로만 들어옴
        List<BbHint> hints = List.of(new BbHint(60L, "car", List.of(5.0, 6.0, 7.0, 8.0), 0.81, null));
        int saved = step.run(6L, hints);

        // SAM2 2회 호출, POLYGON 2건 저장
        assertThat(saved).isEqualTo(2);
        verify(aiServerClient, times(2)).segment(any());
        verify(lblRepository, times(2)).save(any());
    }

    // ─── Phase 6: LS_DATA_LBL_AI_INFO 분리 ───

    @Test
    @DisplayName("Phase6_Sam2Step_POLYGON_저장_시_LsDataLblAiInfo_SRC_SAM2_도_동시_저장")
    void aiInfoPersistedAlongsidePolygon() {
        when(srcRepository.findByRawSnOrderByFrameNoAsc(90L))
                .thenReturn(List.of(newSrc(20L)));
        when(lblRepository.findBySrcSnAndAutoLblYn(20L, "Y"))
                .thenReturn(List.of(newBbox(20L, "person", "[1.0,2.0,3.0,4.0]")));
        when(aiServerClient.segment(any(Sam2Request.class)))
                .thenReturn(Mono.just(new Sam2Response(List.of(List.of(1.0, 2.0), List.of(3.0, 4.0)), 0.88)));

        int saved = step.run(90L, List.of());

        assertThat(saved).isEqualTo(1);
        ArgumentCaptor<LsDataLblAiInfo> aiCaptor = ArgumentCaptor.forClass(LsDataLblAiInfo.class);
        verify(aiInfoRepository, times(1)).save(aiCaptor.capture());
        LsDataLblAiInfo info = aiCaptor.getValue();
        assertThat(info.getLblSrcCd()).isEqualTo(LsDataLblAiInfo.SRC_SAM2);
        assertThat(info.getAutoLblYn()).isEqualTo("Y");
        assertThat(info.getDataRawSn()).isEqualTo(90L);
        assertThat(info.getDataLblSn()).isNotNull();
    }

    // ─── Phase 6 — AutoLabel preset 매핑 (LS_LABEL.NAME → LABEL_ID) ───

    @Test
    @DisplayName("SAM2_person_응답_시_LABEL_ID_매칭됨")
    void sam2LabelIdMappedFromMaster() {
        when(srcRepository.findByRawSnOrderByFrameNoAsc(91L))
                .thenReturn(List.of(newSrc(20L)));
        when(lblRepository.findBySrcSnAndAutoLblYn(20L, "Y"))
                .thenReturn(List.of(newBbox(20L, "person", "[1.0,2.0,3.0,4.0]")));
        when(aiServerClient.segment(any(Sam2Request.class)))
                .thenReturn(Mono.just(new Sam2Response(List.of(List.of(1.0, 2.0)), 0.88)));
        when(labelMasterService.findLabelIdByDtctType("person")).thenReturn(Optional.of(1L));

        step.run(91L, List.of());

        ArgumentCaptor<LsDataLbl> captor = ArgumentCaptor.forClass(LsDataLbl.class);
        verify(lblRepository, times(1)).save(captor.capture());
        assertThat(captor.getValue().getLabelId()).isEqualTo(1L);
        assertThat(captor.getValue().getLblTypeCd()).isEqualTo("POLYGON");
    }

    @Test
    @DisplayName("SAM2_unknown_label_응답_시_LABEL_ID_null")
    void sam2UnknownLabelIdRemainsNull() {
        when(srcRepository.findByRawSnOrderByFrameNoAsc(92L))
                .thenReturn(List.of(newSrc(20L)));
        when(lblRepository.findBySrcSnAndAutoLblYn(20L, "Y"))
                .thenReturn(List.of(newBbox(20L, "rare_label_unknown", "[1.0,2.0,3.0,4.0]")));
        when(aiServerClient.segment(any(Sam2Request.class)))
                .thenReturn(Mono.just(new Sam2Response(List.of(List.of(1.0, 2.0)), 0.88)));
        // 기본 stub (Optional.empty)

        step.run(92L, List.of());

        ArgumentCaptor<LsDataLbl> captor = ArgumentCaptor.forClass(LsDataLbl.class);
        verify(lblRepository, times(1)).save(captor.capture());
        assertThat(captor.getValue().getLabelId()).isNull();
    }

    @Test
    @DisplayName("SAM2_매핑_로그_검증")
    void sam2MappingLogged() {
        when(srcRepository.findByRawSnOrderByFrameNoAsc(93L))
                .thenReturn(List.of(newSrc(20L)));
        when(lblRepository.findBySrcSnAndAutoLblYn(20L, "Y"))
                .thenReturn(List.of(newBbox(20L, "person", "[1.0,2.0,3.0,4.0]")));
        when(aiServerClient.segment(any(Sam2Request.class)))
                .thenReturn(Mono.just(new Sam2Response(List.of(List.of(1.0, 2.0)), 0.88)));
        when(labelMasterService.findLabelIdByDtctType("person")).thenReturn(Optional.of(1L));

        step.run(93L, List.of());

        long matchedLogs = logAppender.list.stream()
                .filter(e -> e.getFormattedMessage().contains("[Batch][Sam2] mapped label"))
                .filter(e -> e.getFormattedMessage().contains("name=person"))
                .filter(e -> e.getFormattedMessage().contains("labelId=1"))
                .count();
        assertThat(matchedLogs).isGreaterThanOrEqualTo(1L);
    }

    // ─── Phase 4 V2.0: SAM2 원본 프레임 전용 ───

    @Test
    @DisplayName("V2_SAM2_원본_프레임만_실행_비식별_경로_존재해도_원본_사용")
    void sam2UsesRawImagePathNotDeid() throws IOException {
        Path rawDir = tempDir.resolve("raw");
        byte[] rawContent = {(byte) 0xFF, (byte) 0xD8, 0x01};
        byte[] deidContent = {(byte) 0xFF, (byte) 0xD8, 0x02};
        Files.write(rawDir.resolve("raw-frame.jpg"), rawContent);
        Path deidDir = rawDir.resolve("deid");
        Files.createDirectories(deidDir);
        Files.write(deidDir.resolve("deid-frame.jpg"), deidContent);

        LsDataSrc srcWithDeid = LsDataSrc.create(1L, 0, "raw-frame.jpg", null);
        setField(srcWithDeid, "srcSn", 10L);
        srcWithDeid.attachDeidPath("deid/deid-frame.jpg");

        when(srcRepository.findByRawSnOrderByFrameNoAsc(80L))
                .thenReturn(List.of(srcWithDeid));
        when(lblRepository.findBySrcSnAndAutoLblYn(10L, "Y")).thenReturn(List.of());
        // upstream hint 로 BBOX 전달 — SAM2 호출 트리거
        List<BbHint> hints = List.of(
                new BbHint(10L, "person", List.of(1.0, 2.0, 3.0, 4.0), 0.92, null));
        when(aiServerClient.segment(any(Sam2Request.class)))
                .thenReturn(Mono.just(new Sam2Response(List.of(List.of(5.0, 6.0)), 0.88)));

        step.run(80L, hints);

        ArgumentCaptor<Sam2Request> captor = ArgumentCaptor.forClass(Sam2Request.class);
        verify(aiServerClient).segment(captor.capture());
        byte[] decoded = java.util.Base64.getDecoder().decode(captor.getValue().imageB64());
        // 원본 프레임 내용과 동일해야 함 (비식별 아님)
        assertThat(decoded).isEqualTo(rawContent);
    }
}
