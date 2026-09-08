package kr.co.cudo.authoring.batch.step;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.policy.PresetLabelLookupService;
import kr.co.cudo.authoring.batch.policy.PresetResolution;
import kr.co.cudo.authoring.batch.policy.PresetResolutionStatus;
import kr.co.cudo.authoring.batch.policy.PresetLabelLookupService.AnnotationToggle;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.client.AiServerClient;
import kr.co.cudo.authoring.common.client.AiWorkload;
import kr.co.cudo.authoring.common.client.dto.Sam2Request;
import kr.co.cudo.authoring.common.client.dto.Sam2Response;
import kr.co.cudo.authoring.common.config.DeployedEnvironmentDetector;
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
import org.springframework.mock.env.MockEnvironment;
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
import static org.mockito.ArgumentMatchers.eq;
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
        videoRepository = mock(VideoRepository.class);
        presetLabelLookup = mock(PresetLabelLookupService.class);
        labelMasterService = mock(LabelMasterService.class);
        // LabelMasterService 기본은 미매핑 (Optional.empty) — 개별 테스트가 필요 시 override.
        when(labelMasterService.findLabelIdByDtctType(anyString())).thenReturn(Optional.empty());
        // 저장 후 PK 를 읽는 검증(생산이력·트랙 매칭)이 있으므로 스텁이 lblSn 을 부여한다.
        //   V6 이전에는 save() 직후 그 PK 로 LS_DATA_LBL_AI_INFO 행을 만들었기 때문에 필수였다.
        when(lblRepository.save(any(LsDataLbl.class))).thenAnswer(inv -> {
            LsDataLbl arg = inv.getArgument(0);
            setField(arg, "lblSn", lblSnSeq.getAndIncrement());
            return arg;
        });
        // B-ISSUE-42 — 저장 경로가 개별 save() 에서 프레임 단위 saveAll() 로 바뀌었다. 본 스텁은 saveAll 을
        //   <원소별 save 위임>으로 모사해 기존 내용 단언(저장된 폴리곤의 좌표/타입 등)을 그대로 살린다.
        //   "정말 배치로 저장되는가" 는 아래 B-ISSUE-42 전용 테스트가 saveAll 호출로 직접 고정한다.
        when(lblRepository.saveAll(any())).thenAnswer(inv -> {
            Iterable<LsDataLbl> in = inv.getArgument(0);
            List<LsDataLbl> out = new java.util.ArrayList<>();
            for (LsDataLbl l : in) {
                out.add(lblRepository.save(l));
            }
            return out;
        });

        // dummy image files (Phase 4: srcSn 70/80 케이스 추가 — 범위 확장)
        Path rawDir = tempDir.resolve("raw");
        Files.createDirectories(rawDir);
        for (long i = 10L; i <= 90L; i++) {
            Files.write(rawDir.resolve(i + ".jpg"), new byte[]{(byte) 0xFF, (byte) 0xD8});
        }

        when(videoRepository.findById(anyLong())).thenReturn(Optional.empty());
        // ★CO-014 — 「프리셋 없음」은 더 이상 전 라벨 통과가 아니라 아무 라벨도 통과하지 않는다.
        //   프리셋 필터가 주제가 아닌 테스트는 실효 프리셋을 기본값으로 둔다.
        when(presetLabelLookup.resolve(any())).thenReturn(PresetResolution.resolved(
                java.util.Map.of("person", new AnnotationToggle(true, true), "car", new AnnotationToggle(true, true))));

        // NEW-H1 — 본 클래스는 <b>비배포(local)</b> 환경의 기존 동작을 고정한다. 배포 환경 fail-closed
        //   와 mock 응답 스킵은 Sam2SegmentStepMockGateTest 가 별도로 고정한다.
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("local");
        step = new Sam2SegmentStep(aiServerClient, srcRepository, lblRepository,
                videoRepository, presetLabelLookup, labelMasterService,
                new ObjectMapper(), rawDir.toString(), new DeployedEnvironmentDetector(env), mock(kr.co.cudo.authoring.aiserver.service.AiSrvrBatchAssignment.class));

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
        verify(aiServerClient, never()).segment(any(), any(AiWorkload.class), any());
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
        when(aiServerClient.segment(any(Sam2Request.class), eq(AiWorkload.BATCH), any()))
                .thenReturn(Mono.just(new Sam2Response(List.of(List.of(1.0, 2.0), List.of(3.0, 4.0)), 0.88)));

        // when
        int saved = step.run(21L, List.of());

        // then — nested POINT_CN 도 flat box prompt 로 변환되어 SAM2 가 호출되고 POLYGON 저장
        assertThat(saved).isEqualTo(1);
        ArgumentCaptor<Sam2Request> reqCaptor = ArgumentCaptor.forClass(Sam2Request.class);
        verify(aiServerClient, times(1)).segment(reqCaptor.capture(), eq(AiWorkload.BATCH), any());
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
        when(aiServerClient.segment(any(Sam2Request.class), eq(AiWorkload.BATCH), any()))
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
        when(aiServerClient.segment(any(Sam2Request.class), eq(AiWorkload.BATCH), any()))
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
    @DisplayName("★프리셋이_실효하지_않으면_DB_BBOX가_있어도_폴리곤을_만들지_않는다_구_전량통과_폐기")
    void ineffectivePresetPassesNoLabel() {
        // 라벨을 하나도 담지 않은 프리셋(오토라벨 제외 선언)이면 탐지 단계가 보류하지 않고 통과하므로
        //   이 단계가 실제로 돈다. 그때 구 fail-open 이 남아 있으면 DB 에 남은 BBOX 로 폴리곤을 만든다.
        LsDataRaw raw = rawWithEvent("EVT_FALL");
        when(videoRepository.findById(3L)).thenReturn(Optional.of(raw));
        when(presetLabelLookup.resolve("EVT_FALL"))
                .thenReturn(PresetResolution.of(PresetResolutionStatus.PRESET_EMPTY));
        when(srcRepository.findByRawSnOrderByFrameNoAsc(3L))
                .thenReturn(List.of(newSrc(30L)));
        when(lblRepository.findBySrcSnAndAutoLblYn(30L, "Y"))
                .thenReturn(List.of(newBbox(30L, "person", "[1.0,2.0,3.0,4.0]")));

        int saved = step.run(3L, List.of());

        assertThat(saved).isZero();
        verify(aiServerClient, never()).segment(any(), any(AiWorkload.class), any());
        verify(lblRepository, never()).save(any());
    }

    @Test
    @DisplayName("Sam2Step_polygonEnabled_false_라벨은_SAM2_호출_안_함_그리고_경고_로그")
    void polygonDisabledLabelSkipsSam2WithWarn() {
        LsDataRaw raw = rawWithEvent("EVT_FALL");
        when(videoRepository.findById(3L)).thenReturn(Optional.of(raw));
        // person 은 bbox-only (polygon=false)
        when(presetLabelLookup.resolve("EVT_FALL"))
                .thenReturn(PresetResolution.resolved(Map.of("person", new AnnotationToggle(true, false))));
        when(srcRepository.findByRawSnOrderByFrameNoAsc(3L))
                .thenReturn(List.of(newSrc(30L)));
        // YOLO 가 BBOX 를 저장해 둠 (BBOX_ONLY 라벨)
        when(lblRepository.findBySrcSnAndAutoLblYn(30L, "Y"))
                .thenReturn(List.of(newBbox(30L, "person", "[1.0,2.0,3.0,4.0]")));

        int saved = step.run(3L, List.of());

        // SAM2 미호출, POLYGON 미저장
        assertThat(saved).isZero();
        verify(aiServerClient, never()).segment(any(), any(AiWorkload.class), any());
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
        when(presetLabelLookup.resolve("EVT_FALL"))
                .thenReturn(PresetResolution.resolved(Map.of("person", new AnnotationToggle(false, true))));
        when(srcRepository.findByRawSnOrderByFrameNoAsc(4L))
                .thenReturn(List.of(newSrc(40L)));
        // DB BBOX 없음 (YOLO 가 BBOX 저장을 skip 했기 때문)
        when(lblRepository.findBySrcSnAndAutoLblYn(40L, "Y")).thenReturn(List.of());
        when(aiServerClient.segment(any(Sam2Request.class), eq(AiWorkload.BATCH), any()))
                .thenReturn(Mono.just(new Sam2Response(List.of(List.of(1.0, 2.0), List.of(3.0, 4.0)), 0.77)));

        List<BbHint> hints = List.of(new BbHint(40L, "person", List.of(1.0, 2.0, 3.0, 4.0), 0.92, null));
        int saved = step.run(4L, hints);

        assertThat(saved).isEqualTo(1);
        verify(aiServerClient, times(1)).segment(any(), eq(AiWorkload.BATCH), any());
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
        when(presetLabelLookup.resolve("EVT_FALL"))
                .thenReturn(PresetResolution.resolved(Map.of("person", new AnnotationToggle(true, true))));
        when(srcRepository.findByRawSnOrderByFrameNoAsc(5L))
                .thenReturn(List.of(newSrc(50L)));
        when(lblRepository.findBySrcSnAndAutoLblYn(50L, "Y"))
                .thenReturn(List.of(newBbox(50L, "person", "[1.0,2.0,3.0,4.0]")));
        when(aiServerClient.segment(any(Sam2Request.class), eq(AiWorkload.BATCH), any()))
                .thenReturn(Mono.just(new Sam2Response(List.of(List.of(1.0, 2.0)), 0.91)));

        // 동일 (srcSn=50, label="person") 의 hint 가 별도로 들어옴 — 중복
        List<BbHint> hints = List.of(new BbHint(50L, "person", List.of(1.0, 2.0, 3.0, 4.0), 0.92, null));
        int saved = step.run(5L, hints);

        // 한 번만 처리되어야 함 (DB BBOX 우선 또는 dedup) — SAM2 1회 호출, POLYGON 1건 저장
        assertThat(saved).isEqualTo(1);
        verify(aiServerClient, times(1)).segment(any(), eq(AiWorkload.BATCH), any());
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
        when(presetLabelLookup.resolve("EVT_FALL"))
                .thenReturn(PresetResolution.resolved(Map.of("person", new AnnotationToggle(true, true))));
        when(srcRepository.findByRawSnOrderByFrameNoAsc(31L))
                .thenReturn(List.of(newSrc(30L)));
        // 프리셋 토글 맵에 없는 "car" 라벨의 DB BBOX
        when(lblRepository.findBySrcSnAndAutoLblYn(30L, "Y"))
                .thenReturn(List.of(newBbox(30L, "car", "[1.0,2.0,3.0,4.0]")));

        int saved = step.run(31L, List.of());

        // 미포함 라벨은 SAM2 호출 없이 제거되어야 한다.
        assertThat(saved).isZero();
        verify(aiServerClient, never()).segment(any(), any(AiWorkload.class), any());
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
        when(presetLabelLookup.resolve("EVT_FALL"))
                .thenReturn(PresetResolution.resolved(Map.of("person", new AnnotationToggle(true, true))));
        when(srcRepository.findByRawSnOrderByFrameNoAsc(41L))
                .thenReturn(List.of(newSrc(40L)));
        when(lblRepository.findBySrcSnAndAutoLblYn(40L, "Y"))
                .thenReturn(List.of(newBbox(40L, "  Person ", "[1.0,2.0,3.0,4.0]")));
        when(aiServerClient.segment(any(Sam2Request.class), eq(AiWorkload.BATCH), any()))
                .thenReturn(Mono.just(new Sam2Response(List.of(List.of(1.0, 2.0), List.of(3.0, 4.0)), 0.88)));

        int saved = step.run(41L, List.of());

        // 정규화로 토글 매칭 성공 → SAM2 1회 호출, POLYGON 1건 저장.
        assertThat(saved).isEqualTo(1);
        verify(aiServerClient, times(1)).segment(any(), eq(AiWorkload.BATCH), any());
        verify(lblRepository, times(1)).save(any());
    }

    // ─── Phase 4: dedup 키에 trackId 반영 ───

    @Test
    @DisplayName("Sam2Step_dedup_은_label_과_trackId_조합_기준_다른_trackId_는_별도_처리")
    void dedupKeyIncludesTrackId() {
        LsDataRaw raw = rawWithEvent("EVT_FALL");
        when(videoRepository.findById(70L)).thenReturn(Optional.of(raw));
        when(presetLabelLookup.resolve("EVT_FALL"))
                .thenReturn(PresetResolution.resolved(Map.of("person", new AnnotationToggle(true, true))));
        when(srcRepository.findByRawSnOrderByFrameNoAsc(70L))
                .thenReturn(List.of(newSrc(70L)));
        // 같은 srcSn + 같은 라벨 "person" 이지만 trackId 가 1 인 DB BBOX 1건만 존재
        LsDataLbl bboxWithTrack = LsDataLbl.createAutoBbox(70L, null, "person", "[1.0,2.0,3.0,4.0]",
                BigDecimal.valueOf(0.9), "1");
        when(lblRepository.findBySrcSnAndAutoLblYn(70L, "Y"))
                .thenReturn(List.of(bboxWithTrack));
        when(aiServerClient.segment(any(Sam2Request.class), eq(AiWorkload.BATCH), any()))
                .thenReturn(Mono.just(new Sam2Response(List.of(List.of(1.0, 2.0)), 0.85)));

        // hint 는 같은 라벨 "person" 이지만 trackId=2 → 별도 객체이므로 별도 처리되어야 함
        List<BbHint> hints = List.of(new BbHint(70L, "person", List.of(5.0, 6.0, 7.0, 8.0), 0.81, 2));
        int saved = step.run(70L, hints);

        // SAM2 2회 호출, POLYGON 2건 저장 (트랙 분리)
        assertThat(saved).isEqualTo(2);
        verify(aiServerClient, times(2)).segment(any(), eq(AiWorkload.BATCH), any());
        verify(lblRepository, times(2)).save(any());
    }

    @Test
    @DisplayName("Sam2Step_trackId_null_시_기존_label_기준_dedup_으로_fallback")
    void dedupFallbackWhenTrackIdNull() {
        LsDataRaw raw = rawWithEvent("EVT_FALL");
        when(videoRepository.findById(80L)).thenReturn(Optional.of(raw));
        when(presetLabelLookup.resolve("EVT_FALL"))
                .thenReturn(PresetResolution.resolved(Map.of("person", new AnnotationToggle(true, true))));
        when(srcRepository.findByRawSnOrderByFrameNoAsc(80L))
                .thenReturn(List.of(newSrc(80L)));
        // DB BBOX 는 trackId=null (legacy/저신뢰 fallback)
        when(lblRepository.findBySrcSnAndAutoLblYn(80L, "Y"))
                .thenReturn(List.of(newBbox(80L, "person", "[1.0,2.0,3.0,4.0]")));
        when(aiServerClient.segment(any(Sam2Request.class), eq(AiWorkload.BATCH), any()))
                .thenReturn(Mono.just(new Sam2Response(List.of(List.of(1.0, 2.0)), 0.85)));

        // hint 도 trackId=null → 같은 (srcSn, label, null) 키로 dedup
        List<BbHint> hints = List.of(new BbHint(80L, "person", List.of(1.0, 2.0, 3.0, 4.0), 0.92, null));
        int saved = step.run(80L, hints);

        // 한 번만 처리 (트랙 null 이면 라벨 기준 dedup 으로 fallback)
        assertThat(saved).isEqualTo(1);
        verify(aiServerClient, times(1)).segment(any(), eq(AiWorkload.BATCH), any());
        verify(lblRepository, times(1)).save(any());
    }

    @Test
    @DisplayName("Sam2Step_DB_BBOX_와_upstreamHints_가_다른_라벨이면_둘_다_처리")
    void differentLabelsBothProcessed() {
        LsDataRaw raw = rawWithEvent("EVT_TRESPASS");
        when(videoRepository.findById(6L)).thenReturn(Optional.of(raw));
        // person=BOTH, car=polygon-only
        when(presetLabelLookup.resolve("EVT_TRESPASS"))
                .thenReturn(PresetResolution.resolved(Map.of(
                        "person", new AnnotationToggle(true, true),
                        "car", new AnnotationToggle(false, true)
                )));
        when(srcRepository.findByRawSnOrderByFrameNoAsc(6L))
                .thenReturn(List.of(newSrc(60L)));
        // DB BBOX: person 만 (BOTH → BBOX 저장됨)
        when(lblRepository.findBySrcSnAndAutoLblYn(60L, "Y"))
                .thenReturn(List.of(newBbox(60L, "person", "[1.0,2.0,3.0,4.0]")));
        when(aiServerClient.segment(any(Sam2Request.class), eq(AiWorkload.BATCH), any()))
                .thenReturn(Mono.just(new Sam2Response(List.of(List.of(1.0, 2.0)), 0.85)));

        // car 는 polygon-only → hint 로만 들어옴
        List<BbHint> hints = List.of(new BbHint(60L, "car", List.of(5.0, 6.0, 7.0, 8.0), 0.81, null));
        int saved = step.run(6L, hints);

        // SAM2 2회 호출, POLYGON 2건 저장
        assertThat(saved).isEqualTo(2);
        verify(aiServerClient, times(2)).segment(any(), eq(AiWorkload.BATCH), any());
        verify(lblRepository, times(2)).save(any());
    }

    // ─── Phase 6: LS_DATA_LBL_AI_INFO 분리 ───

    @Test
    @DisplayName("Sam2Step_POLYGON_저장_시_라벨행에_출처_SAM2와_자동라벨여부_Y가_함께_적재된다")
    void aiInfoPersistedAlongsidePolygon() {
        when(srcRepository.findByRawSnOrderByFrameNoAsc(90L))
                .thenReturn(List.of(newSrc(20L)));
        when(lblRepository.findBySrcSnAndAutoLblYn(20L, "Y"))
                .thenReturn(List.of(newBbox(20L, "person", "[1.0,2.0,3.0,4.0]")));
        when(aiServerClient.segment(any(Sam2Request.class), eq(AiWorkload.BATCH), any()))
                .thenReturn(Mono.just(new Sam2Response(List.of(List.of(1.0, 2.0), List.of(3.0, 4.0)), 0.88)));

        int saved = step.run(90L, List.of());

        // V6 — 생산이력이 라벨 행의 컬럼이라 그 라벨 자체를 검증한다.
        assertThat(saved).isEqualTo(1);
        ArgumentCaptor<LsDataLbl> lblCaptor = ArgumentCaptor.forClass(LsDataLbl.class);
        verify(lblRepository, times(1)).save(lblCaptor.capture());
        LsDataLbl persisted = lblCaptor.getValue();
        assertThat(persisted.getLblSrcCd()).isEqualTo(LsDataLbl.SRC_SAM2);
        assertThat(persisted.getAutoLblYn()).isEqualTo("Y");
    }

    // ─── Phase 6 — AutoLabel preset 매핑 (LS_LABEL.NAME → LABEL_ID) ───

    @Test
    @DisplayName("SAM2_person_응답_시_LABEL_ID_매칭됨")
    void sam2LabelIdMappedFromMaster() {
        when(srcRepository.findByRawSnOrderByFrameNoAsc(91L))
                .thenReturn(List.of(newSrc(20L)));
        when(lblRepository.findBySrcSnAndAutoLblYn(20L, "Y"))
                .thenReturn(List.of(newBbox(20L, "person", "[1.0,2.0,3.0,4.0]")));
        when(aiServerClient.segment(any(Sam2Request.class), eq(AiWorkload.BATCH), any()))
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
        when(aiServerClient.segment(any(Sam2Request.class), eq(AiWorkload.BATCH), any()))
                .thenReturn(Mono.just(new Sam2Response(List.of(List.of(1.0, 2.0)), 0.88)));
        // 프리셋에는 담겨 있으나 라벨 마스터 검출 매핑 조회가 비는 라벨 — 저장은 되고 labelId 만 null 이다.
        when(presetLabelLookup.resolve(any())).thenReturn(PresetResolution.resolved(
                java.util.Map.of("rare_label_unknown", new AnnotationToggle(true, true))));

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
        when(aiServerClient.segment(any(Sam2Request.class), eq(AiWorkload.BATCH), any()))
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
        when(aiServerClient.segment(any(Sam2Request.class), eq(AiWorkload.BATCH), any()))
                .thenReturn(Mono.just(new Sam2Response(List.of(List.of(5.0, 6.0)), 0.88)));

        step.run(80L, hints);

        ArgumentCaptor<Sam2Request> captor = ArgumentCaptor.forClass(Sam2Request.class);
        verify(aiServerClient).segment(captor.capture(), eq(AiWorkload.BATCH), any());
        byte[] decoded = java.util.Base64.getDecoder().decode(captor.getValue().imageB64());
        // 원본 프레임 내용과 동일해야 함 (비식별 아님)
        assertThat(decoded).isEqualTo(rawContent);
    }

    // ============ B-ISSUE-42 — 루프 내 개별 save() → 프레임 단위 saveAll() ============

    @Test
    @DisplayName("한_프레임의_폴리곤들은_라벨_saveAll_1회와_AI메타_saveAll_1회로_저장된다")
    void polygonsOfOneFramePersistedWithSingleSaveAll() {
        // given — 프레임 1개 × SegmentJob 2건(라벨 2종)
        when(srcRepository.findByRawSnOrderByFrameNoAsc(710L)).thenReturn(List.of(newSrc(10L)));
        when(lblRepository.findBySrcSnAndAutoLblYn(10L, "Y")).thenReturn(List.of());
        List<BbHint> hints = List.of(
                new BbHint(10L, "person", List.of(1.0, 2.0, 3.0, 4.0), 0.92, 1),
                new BbHint(10L, "car", List.of(5.0, 6.0, 7.0, 8.0), 0.81, 2));
        when(aiServerClient.segment(any(Sam2Request.class), eq(AiWorkload.BATCH), any()))
                .thenReturn(Mono.just(new Sam2Response(List.of(List.of(5.0, 6.0)), 0.88)));

        // when
        int saved = step.run(710L, hints);

        // then — 구 구현은 save 2회 + save 2회였다
        assertThat(saved).isEqualTo(2);
        ArgumentCaptor<Iterable<LsDataLbl>> lblCaptor = ArgumentCaptor.forClass(Iterable.class);
        verify(lblRepository, times(1)).saveAll(lblCaptor.capture());
        assertThat(lblCaptor.getValue()).hasSize(2);
        assertThat(lblCaptor.getValue()).hasSize(2);
    }

    @Test
    @DisplayName("폴리곤마다_자기_신뢰도와_출처를_들고_저장된다")
    void aiInfoRowsMatchTheirOwnPolygonPk() {
        // given — 신뢰도가 다른 SAM2 응답 2건
        when(srcRepository.findByRawSnOrderByFrameNoAsc(711L)).thenReturn(List.of(newSrc(10L)));
        when(lblRepository.findBySrcSnAndAutoLblYn(10L, "Y")).thenReturn(List.of());
        List<BbHint> hints = List.of(
                new BbHint(10L, "person", List.of(1.0, 2.0, 3.0, 4.0), 0.92, 1),
                new BbHint(10L, "car", List.of(5.0, 6.0, 7.0, 8.0), 0.81, 2));
        when(aiServerClient.segment(any(Sam2Request.class), eq(AiWorkload.BATCH), any()))
                .thenReturn(Mono.just(new Sam2Response(List.of(List.of(5.0, 6.0)), 0.11)))
                .thenReturn(Mono.just(new Sam2Response(List.of(List.of(7.0, 8.0)), 0.99)));

        // when
        step.run(711L, hints);

        // then
        // V6 — 구 검증은 "AI 메타 i 가 라벨 i 의 PK 를 갖는가"(두 리스트의 인덱스 대응)였다. 생산이력이
        //   같은 행이 되어 <b>어긋날 대상 자체가 없어졌으므로</b>, 각 폴리곤이 자기 신뢰도·출처를 들고
        //   저장되는지로 축을 옮긴다(신뢰도가 서로 다른 2건이라 뒤바뀌면 관측된다).
        ArgumentCaptor<Iterable<LsDataLbl>> lblCaptor = ArgumentCaptor.forClass(Iterable.class);
        verify(lblRepository).saveAll(lblCaptor.capture());

        List<LsDataLbl> labels = new java.util.ArrayList<>();
        lblCaptor.getValue().forEach(labels::add);

        assertThat(labels).hasSize(2);
        for (LsDataLbl l : labels) {
            assertThat(l.getLblSrcCd()).isEqualTo(LsDataLbl.SRC_SAM2);
            assertThat(l.getAutoLblYn()).isEqualTo("Y");
            assertThat(l.getConfScore()).isNotNull();
        }
        assertThat(labels).extracting(LsDataLbl::getConfScore)
                .extracting(java.math.BigDecimal::doubleValue)
                .containsExactly(0.11, 0.99);
    }

    @Test
    @DisplayName("SAM2_저장대상이_없는_프레임은_saveAll을_호출하지_않는다")
    void frameWithoutPolygonDoesNotCallSaveAll() {
        // given — SegmentJob 은 있으나 SAM2 응답 폴리곤이 없음
        when(srcRepository.findByRawSnOrderByFrameNoAsc(712L)).thenReturn(List.of(newSrc(10L)));
        when(lblRepository.findBySrcSnAndAutoLblYn(10L, "Y")).thenReturn(List.of());
        List<BbHint> hints = List.of(
                new BbHint(10L, "person", List.of(1.0, 2.0, 3.0, 4.0), 0.92, 1));
        when(aiServerClient.segment(any(Sam2Request.class), eq(AiWorkload.BATCH), any()))
                .thenReturn(Mono.just(new Sam2Response(null, 0.5)));

        // when
        int saved = step.run(712L, hints);

        // then
        assertThat(saved).isZero();
        verify(lblRepository, never()).saveAll(any());
    }

    // ── 재실행 멱등 (@req R1) — 자동 재시도가 파이프라인을 선두부터 다시 돌려도 중복 적재하지 않는다 ──

    /**
     * 시나리오 3(SAM2 축) — 이미 SAM2 폴리곤이 있는 프레임은 추론·적재를 건너뛴다. 사람이 그 폴리곤을
     * 수정했을 수 있으므로 <b>삭제 후 재삽입은 하지 않는다</b>.
     */
    @Test
    @DisplayName("이미_SAM2_폴리곤이_있는_프레임은_추론과_적재를_건너뛰고_기존_폴리곤을_지우지_않는다")
    void skipsFramesThatAlreadyHaveSam2Polygons() {
        // given — 프레임 10 에는 이미 SAM2 폴리곤이 있고, 11 에는 없다.
        when(srcRepository.findByRawSnOrderByFrameNoAsc(801L))
                .thenReturn(List.of(newSrc(10L), newSrc(11L)));
        when(lblRepository.findDistinctSrcSnsByRawSnAndLblSrcCd(801L, LsDataLbl.SRC_SAM2))
                .thenReturn(List.of(10L));
        when(lblRepository.findBySrcSnAndAutoLblYn(anyLong(), anyString())).thenReturn(List.of());
        when(aiServerClient.segment(any(Sam2Request.class), eq(AiWorkload.BATCH), any()))
                .thenReturn(Mono.just(new Sam2Response(List.of(List.of(1.0, 2.0), List.of(3.0, 4.0)), 0.9)));
        List<BbHint> hints = List.of(
                new BbHint(10L, "person", List.of(1.0, 2.0, 3.0, 4.0), 0.92, 1),
                new BbHint(11L, "person", List.of(1.0, 2.0, 3.0, 4.0), 0.92, 2));

        // when
        int saved = step.run(801L, hints);

        // then — 외부 추론은 프레임 11 에 대해서만 1회.
        assertThat(saved).isEqualTo(1);
        verify(aiServerClient, times(1)).segment(any(Sam2Request.class), eq(AiWorkload.BATCH), any());
        // 건너뛴 프레임은 DB BBOX 조회조차 하지 않는다(추론 전 단계에서 끊는다).
        verify(lblRepository, never()).findBySrcSnAndAutoLblYn(org.mockito.ArgumentMatchers.eq(10L), anyString());
        // ★ 사람의 수정 보호 — 삭제 경로가 없어야 한다.
        verify(lblRepository, never()).deleteAllByIdInBatch(any());
        // V6 — 멱등 skip 은 <b>아무것도 지우지 않는다</b>. 생산이력이 같은 행이 되어 구 검증 축
        //   (AI 메타 삭제 미호출)이 사라졌으므로 라벨 삭제 미호출로 옮긴다.
        verify(lblRepository, never()).deleteAllByIdInBatch(any());
    }

    /**
     * 시나리오 6(회귀 — 가장 중요) — 멱등 가드가 <b>정상 최초 실행</b>을 막아서는 안 된다.
     */
    @Test
    @DisplayName("기존_SAM2_폴리곤이_0건이면_멱등_가드가_최초_전량_분할을_막지_않는다")
    void firstRunNotBlockedByIdempotencyGuard() {
        when(srcRepository.findByRawSnOrderByFrameNoAsc(802L))
                .thenReturn(List.of(newSrc(10L), newSrc(11L)));
        when(lblRepository.findDistinctSrcSnsByRawSnAndLblSrcCd(802L, LsDataLbl.SRC_SAM2))
                .thenReturn(List.of());
        when(lblRepository.findBySrcSnAndAutoLblYn(anyLong(), anyString())).thenReturn(List.of());
        when(aiServerClient.segment(any(Sam2Request.class), eq(AiWorkload.BATCH), any()))
                .thenReturn(Mono.just(new Sam2Response(List.of(List.of(1.0, 2.0), List.of(3.0, 4.0)), 0.9)));
        List<BbHint> hints = List.of(
                new BbHint(10L, "person", List.of(1.0, 2.0, 3.0, 4.0), 0.92, 1),
                new BbHint(11L, "person", List.of(1.0, 2.0, 3.0, 4.0), 0.92, 2));

        int saved = step.run(802L, hints);

        assertThat(saved).isEqualTo(2);
        verify(aiServerClient, times(2)).segment(any(Sam2Request.class), eq(AiWorkload.BATCH), any());
    }
}
