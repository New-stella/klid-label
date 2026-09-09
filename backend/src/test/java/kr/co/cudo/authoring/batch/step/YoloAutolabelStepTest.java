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
import kr.co.cudo.authoring.batch.status.BatchStatusService;
import kr.co.cudo.authoring.batch.policy.PresetLabelLookupService.AnnotationToggle;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.client.AiServerClient;
import kr.co.cudo.authoring.common.client.AiWorkload;
import kr.co.cudo.authoring.common.client.dto.YoloResponse;
import kr.co.cudo.authoring.common.client.dto.YoloTrackRequest;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.eventtype.service.EventTypeService;
import kr.co.cudo.authoring.label.dto.LabelMasterResponse;
import kr.co.cudo.authoring.label.service.LabelMasterService;
import kr.co.cudo.authoring.preset.entity.LsLabelPreset;
import kr.co.cudo.authoring.preset.repository.LsLabelPresetRepository;
import kr.co.cudo.authoring.sysconfig.ConfigKeys;
import kr.co.cudo.authoring.sysconfig.service.SystemConfigService;
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
import java.util.LinkedHashMap;
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
import static org.mockito.Mockito.when;

class YoloAutolabelStepTest {

    private AiServerClient aiServerClient;
    private LsDataSrcRepository srcRepository;
    private LsDataLblRepository lblRepository;
    private VideoRepository videoRepository;
    private PresetLabelLookupService presetLabelLookup;
    private BatchStatusService batchStatusService;
    private SystemConfigService systemConfigService;
    private LabelMasterService labelMasterService;
    /** C-ISSUE-41 — 저장 전 좌표 clamp 기준(프레임 실측 해상도). */
    private kr.co.cudo.authoring.label.service.FrameBoundsResolver frameBoundsResolver;
    private YoloAutolabelStep step;
    /** Phase 6 — lblRepository.save() mock 이 생성된 라벨에 부여할 단조 증가 ID. */
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
        batchStatusService = mock(BatchStatusService.class);
        systemConfigService = mock(SystemConfigService.class);
        labelMasterService = mock(LabelMasterService.class);
        frameBoundsResolver = mock(kr.co.cudo.authoring.label.service.FrameBoundsResolver.class);
        // C-ISSUE-41 — 온라인 경로와 동일한 1280x720 실측 해상도를 기준으로 clamp 한다.
        when(frameBoundsResolver.resolve(any())).thenReturn(Optional.of(new int[]{1280, 720}));
        // SystemConfigService 기본은 모든 키 조회 시 null 반환 → fallback 기본값(40, 1280, 50) 사용.
        when(systemConfigService.getInt(any())).thenReturn(null);
        // LabelMasterService 기본은 미매핑 (Optional.empty) — 개별 테스트가 필요 시 override.
        when(labelMasterService.findLabelIdByDtctType(anyString())).thenReturn(Optional.empty());
        // 저장 후 PK 를 읽는 검증(생산이력·트랙 매칭)이 있으므로 스텁이 lblSn 을 부여한다.
        //   V6 이전에는 save() 직후 그 PK 로 LS_DATA_LBL_AI_INFO 행을 만들었기 때문에 필수였다.
        when(lblRepository.save(any(LsDataLbl.class))).thenAnswer(inv -> {
            LsDataLbl arg = inv.getArgument(0);
            try {
                Field f = LsDataLbl.class.getDeclaredField("lblSn");
                f.setAccessible(true);
                f.set(arg, lblSnSeq.getAndIncrement());
            } catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
            return arg;
        });
        // B-ISSUE-42 — 프로덕션 저장 경로가 save() 개별 호출에서 프레임 단위 saveAll() 로 바뀌었다.
        //   본 스텁은 saveAll 을 <원소별 save 위임>으로 모사한다. 목적은 기존 <내용 단언>(저장된 라벨의
        //   좌표/타입/신뢰도 등을 save 캡처로 검증하는 30여 개 테스트)을 그대로 살리는 것이며,
        //   "정말 배치로 저장되는가" 는 별도 테스트(saveAll 호출 횟수·PK 매칭)가 직접 고정한다.
        when(lblRepository.saveAll(any())).thenAnswer(inv -> {
            Iterable<LsDataLbl> in = inv.getArgument(0);
            java.util.List<LsDataLbl> out = new java.util.ArrayList<>();
            for (LsDataLbl l : in) {
                out.add(lblRepository.save(l));
            }
            return out;
        });

        // Create temp directory and dummy image files
        Path rawDir = tempDir.resolve("raw");
        Files.createDirectories(rawDir);
        Files.write(rawDir.resolve("10.jpg"), new byte[]{(byte) 0xFF, (byte) 0xD8}); // JPEG header
        Files.write(rawDir.resolve("11.jpg"), new byte[]{(byte) 0xFF, (byte) 0xD8});
        Files.write(rawDir.resolve("20.jpg"), new byte[]{(byte) 0xFF, (byte) 0xD8});
        Files.write(rawDir.resolve("30.jpg"), new byte[]{(byte) 0xFF, (byte) 0xD8});
        Files.write(rawDir.resolve("40.jpg"), new byte[]{(byte) 0xFF, (byte) 0xD8});
        Files.write(rawDir.resolve("50.jpg"), new byte[]{(byte) 0xFF, (byte) 0xD8});

        // 기본은 fail-safe (필터 미적용) 동작을 위해 빈 Optional
        when(videoRepository.findById(anyLong())).thenReturn(Optional.empty());
        // PresetLabelLookupService 기본 동작: <b>실효 프리셋 보유</b>.
        //   ★fail-open 폐기(CO-014) 이후 "프리셋 없음" 은 더 이상 전체 통과가 아니라 보류다.
        //   따라서 프리셋 필터가 주제가 아닌 테스트는 이 시험군이 쓰는 검출 라벨을 전부 담은 실효
        //   프리셋을 기본값으로 둔다(필터를 검증하는 테스트는 각자 override 한다).
        when(presetLabelLookup.resolve(any())).thenReturn(defaultResolution());

        step = new YoloAutolabelStep(aiServerClient, srcRepository, lblRepository,
                videoRepository, presetLabelLookup, batchStatusService, systemConfigService, labelMasterService,
                frameBoundsResolver, new ObjectMapper(), rawDir.toString(), devEnvironment(), mock(kr.co.cudo.authoring.aiserver.service.AiSrvrBatchAssignment.class));

        // Logback ListAppender 부착 — mock 응답 감지 시 WARN 로그를 검증
        stepLogger = (Logger) LoggerFactory.getLogger(YoloAutolabelStep.class);
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

    /**
     * 본 클래스는 <b>기존 동작(WARN-only)</b> 을 검증하므로 비배포(dev) 환경으로 고정한다.
     * 배포 환경(stg/prd)의 mock 차단 정책은 {@link YoloAutolabelStepMockGateTest} 가 담당한다.
     */
    private static kr.co.cudo.authoring.common.config.DeployedEnvironmentDetector devEnvironment() {
        org.springframework.mock.env.MockEnvironment env = new org.springframework.mock.env.MockEnvironment();
        env.setActiveProfiles("dev");
        return new kr.co.cudo.authoring.common.config.DeployedEnvironmentDetector(env);
    }

    /**
     * 이 시험군이 쓰는 검출 라벨 전부를 담은 <b>실효 프리셋</b> — 프리셋 필터가 주제가 아닌 테스트의 기본값.
     *
     * <p>구 기본값은 {@code Optional.empty()}(=fail-safe 전체 통과)였다. CO-014 로 그 폴백이 폐기돼
     * 「프리셋 없음」은 보류가 되었으므로, 필터를 검증하지 않는 테스트는 명시적으로 실효 프리셋을 준다.
     */
    private static PresetResolution defaultResolution() {
        java.util.LinkedHashMap<String, AnnotationToggle> toggles = new java.util.LinkedHashMap<>();
        for (String label : List.of("person", "car", "bus", "dog", "motorcycle", "trash",
                "rare_label_unknown")) {
            toggles.put(label, new AnnotationToggle(true, true));
        }
        return PresetResolution.resolved(toggles);
    }

    private LsDataSrc newSrc(Long srcSn) {
        LsDataSrc src = LsDataSrc.create(1L, srcSn.intValue(), srcSn + ".jpg", null);
        try {
            Field f = LsDataSrc.class.getDeclaredField("srcSn");
            f.setAccessible(true);
            f.set(src, srcSn);
        } catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
        return src;
    }

    private LsDataRaw rawWithEvent(String evntTypeCd) {
        LsDataRaw raw = mock(LsDataRaw.class);
        when(raw.getEvntTypeCd()).thenReturn(evntTypeCd);
        return raw;
    }

    @Test
    @DisplayName("YOLO_검출_결과는_AUTO_LBL_YN_Y_+_BBOX_타입_+_score_0_1_저장")
    void detectionsSavedAsAutoBbox() {
        when(srcRepository.findByRawSnOrderByFrameNoAsc(1L))
                .thenReturn(List.of(newSrc(10L), newSrc(11L)));
        when(aiServerClient.predictYoloTrack(any(YoloTrackRequest.class), eq(AiWorkload.BATCH), any()))
                .thenReturn(Mono.just(new YoloResponse(List.of(
                        new YoloResponse.Detection("person", List.of(1.0, 2.0, 3.0, 4.0), 0.92),
                        new YoloResponse.Detection("car", List.of(5.0, 6.0, 7.0, 8.0), 0.81)
                ))));

        List<BbHint> hints = step.run(1L);

        // fail-safe (togglesFor empty) → BOTH 라벨로 처리. 2 frames × 2 detections = 4 BBOX save + 4 hints.
        assertThat(hints).hasSize(4);

        ArgumentCaptor<LsDataLbl> captor = ArgumentCaptor.forClass(LsDataLbl.class);
        org.mockito.Mockito.verify(lblRepository, org.mockito.Mockito.times(4)).save(captor.capture());
        captor.getAllValues().forEach(lbl -> {
            assertThat(lbl.getAutoLblYn()).isEqualTo("Y");
            assertThat(lbl.getLblTypeCd()).isEqualTo("BBOX");
            assertThat(lbl.getConfScore()).isBetween(BigDecimal.ZERO, BigDecimal.ONE);
        });
    }

    @Test
    @DisplayName("YOLO_bbox_오토라벨_저장시_POINT_CN이_nested형식")
    void autoBboxPointCnIsNested() {
        when(srcRepository.findByRawSnOrderByFrameNoAsc(1L))
                .thenReturn(List.of(newSrc(10L)));
        when(aiServerClient.predictYoloTrack(any(YoloTrackRequest.class), eq(AiWorkload.BATCH), any()))
                .thenReturn(Mono.just(new YoloResponse(List.of(
                        new YoloResponse.Detection("person", List.of(1.0, 2.0, 3.0, 4.0), 0.92)
                ))));

        step.run(1L);

        ArgumentCaptor<LsDataLbl> captor = ArgumentCaptor.forClass(LsDataLbl.class);
        org.mockito.Mockito.verify(lblRepository, org.mockito.Mockito.times(1)).save(captor.capture());
        // 수동 라벨과 동일한 정규형 nested [[x1,y1],[x2,y2]] 로 저장되어야 한다.
        assertThat(captor.getValue().getPointCn()).isEqualTo("[[1.0,2.0],[3.0,4.0]]");
    }

    // ── C-ISSUE-41: 배치 저장 좌표도 온라인과 동일 규칙으로 정규화 ────────────────────

    @Test
    @DisplayName("배치_저장_좌표도_음수는_0으로_clamp되어_DB에_음수가_남지_않는다")
    void batchClampsNegativeCoordinates() {
        // given — 실모델 YOLO 실측값(rawSn=26, src=446 bus). 구 구현은 음수 검증이 전혀 없어
        //         LS_DATA_LBL 에 음수 좌표가 그대로 적재됐다(온라인만 400 인 정책 비대칭).
        when(srcRepository.findByRawSnOrderByFrameNoAsc(1L))
                .thenReturn(List.of(newSrc(10L)));
        when(aiServerClient.predictYoloTrack(any(YoloTrackRequest.class), eq(AiWorkload.BATCH), any()))
                .thenReturn(Mono.just(new YoloResponse(List.of(
                        new YoloResponse.Detection("bus",
                                List.of(-1.5731448368773044, 2.5, 1261.5, 707.5), 0.92)
                ))));

        step.run(1L);

        ArgumentCaptor<LsDataLbl> captor = ArgumentCaptor.forClass(LsDataLbl.class);
        org.mockito.Mockito.verify(lblRepository, org.mockito.Mockito.times(1)).save(captor.capture());
        assertThat(captor.getValue().getPointCn()).isEqualTo("[[0.0,2.5],[1261.5,707.5]]");
    }

    @Test
    @DisplayName("배치_저장_좌표도_이미지_상한을_넘으면_경계로_clamp된다")
    void batchClampsAboveUpperBound() {
        when(srcRepository.findByRawSnOrderByFrameNoAsc(1L))
                .thenReturn(List.of(newSrc(10L)));
        when(aiServerClient.predictYoloTrack(any(YoloTrackRequest.class), eq(AiWorkload.BATCH), any()))
                .thenReturn(Mono.just(new YoloResponse(List.of(
                        new YoloResponse.Detection("person", List.of(10.0, 10.0, 1300.0, 721.3), 0.92)
                ))));

        step.run(1L);

        ArgumentCaptor<LsDataLbl> captor = ArgumentCaptor.forClass(LsDataLbl.class);
        org.mockito.Mockito.verify(lblRepository, org.mockito.Mockito.times(1)).save(captor.capture());
        assertThat(captor.getValue().getPointCn()).isEqualTo("[[10.0,10.0],[1280.0,720.0]]");
    }

    @Test
    @DisplayName("배치에서_이미지_전체밖_퇴화박스는_저장을_건너뛰고_나머지는_저장된다")
    void batchSkipsDegenerateBoxWithoutFailingFrame() {
        // 배치는 영상 단위 처리라 퇴화 박스 1건으로 전체를 실패시키면 그 영상의 오토라벨이 통째로
        // 날아간다 — 해당 검출만 스킵한다(온라인 경로와 동일 정책).
        when(srcRepository.findByRawSnOrderByFrameNoAsc(1L))
                .thenReturn(List.of(newSrc(10L)));
        when(aiServerClient.predictYoloTrack(any(YoloTrackRequest.class), eq(AiWorkload.BATCH), any()))
                .thenReturn(Mono.just(new YoloResponse(List.of(
                        new YoloResponse.Detection("person", List.of(-40.0, 10.0, -5.0, 60.0), 0.92),
                        new YoloResponse.Detection("car", List.of(300.0, 300.0, 400.0, 400.0), 0.81)
                ))));

        step.run(1L);

        ArgumentCaptor<LsDataLbl> captor = ArgumentCaptor.forClass(LsDataLbl.class);
        org.mockito.Mockito.verify(lblRepository, org.mockito.Mockito.times(1)).save(captor.capture());
        assertThat(captor.getValue().getPointCn()).isEqualTo("[[300.0,300.0],[400.0,400.0]]");
    }

    // ── DEV_FIX(M-2): 형식 위반은 검출 단위 드롭 — 영상 1건의 배치를 통째로 실패시키지 않는다 ──

    @Test
    @DisplayName("배치는_형식위반_검출만_드롭하고_같은_프레임의_정상검출은_저장한다")
    void malformedPointsDropDetectionOnlyNotWholeVideo() {
        // given — 외부 ai-server 가 홀수 길이 좌표를 반환(비정상 입력) + 같은 프레임에 정상 검출 1건.
        //   구 구현은 예외가 run() 밖으로 전파되어 그 영상의 YOLO 단계 전체가 실패(FAILED)하고
        //   앞서 저장된 라벨만 남는 부분 상태가 됐다(같은 클래스의 퇴화 박스는 스킵인데 NaN 만 전체 실패
        //   — 정책 자기모순).
        when(srcRepository.findByRawSnOrderByFrameNoAsc(1L))
                .thenReturn(List.of(newSrc(10L)));
        when(aiServerClient.predictYoloTrack(any(YoloTrackRequest.class), eq(AiWorkload.BATCH), any()))
                .thenReturn(Mono.just(new YoloResponse(List.of(
                        new YoloResponse.Detection("person", List.of(1.0, 2.0, 3.0), 0.92),
                        new YoloResponse.Detection("car", List.of(300.0, 300.0, 400.0, 400.0), 0.81)
                ))));

        // when — 예외 없이 완주한다.
        List<BbHint> hints = step.run(1L);

        // then — 정상 검출만 저장/발행되고, 드롭은 WARN 으로 관측 가능하다.
        ArgumentCaptor<LsDataLbl> captor = ArgumentCaptor.forClass(LsDataLbl.class);
        org.mockito.Mockito.verify(lblRepository, org.mockito.Mockito.times(1)).save(captor.capture());
        assertThat(captor.getValue().getPointCn()).isEqualTo("[[300.0,300.0],[400.0,400.0]]");
        assertThat(hints).hasSize(1);
        assertThat(hints.get(0).label()).isEqualTo("car");
        assertThat(logAppender.list).anyMatch(e -> e.getLevel() == Level.WARN
                && e.getFormattedMessage().contains("malformed coordinates"));
    }

    @Test
    @DisplayName("배치는_NaN_좌표_검출만_드롭하고_영상전체를_실패시키지_않는다")
    void nanPointsDropDetectionOnly() {
        // given — NaN 은 (NaN < 0)==false 라 음수 검사를 통과하던 값. DetectionBoxNormalizer 의
        //   isFinite 가드가 잡고, 배치는 그 검출만 드롭한다.
        when(srcRepository.findByRawSnOrderByFrameNoAsc(1L))
                .thenReturn(List.of(newSrc(10L)));
        when(aiServerClient.predictYoloTrack(any(YoloTrackRequest.class), eq(AiWorkload.BATCH), any()))
                .thenReturn(Mono.just(new YoloResponse(List.of(
                        new YoloResponse.Detection("person",
                                List.of(Double.NaN, 2.0, 3.0, 4.0), 0.92)
                ))));

        List<BbHint> hints = step.run(1L);

        org.mockito.Mockito.verify(lblRepository, org.mockito.Mockito.never()).save(any());
        assertThat(hints).isEmpty();
        assertThat(logAppender.list).anyMatch(e -> e.getLevel() == Level.WARN
                && e.getFormattedMessage().contains("malformed coordinates"));
    }

    // ── DEV_FIX(M-1): polygon hint 도 clamp 된 좌표를 싣는다(SAM 프롬프트 오염 차단) ──

    @Test
    @DisplayName("폴리곤전용_프리셋에서_퇴화박스는_BBOX도_hint도_발행되지_않는다")
    void polygonOnlyPresetEmitsNoHintForDegenerateBox() {
        // given — bbox=false(폴리곤 전용) 라 DB BBOX 가 애초에 없다. 구 구현은 hint 에 미clamp 원본을
        //   실었고, Sam2SegmentStep.buildJobs 는 DB BBOX 가 없으면 hint 를 채택하므로 이미지 완전 밖
        //   좌표가 SAM box 프롬프트로 나가고 그 산출 폴리곤이 LS_DATA_LBL 에 저장됐다(학습데이터 오염).
        LsDataRaw rawMock = rawWithEvent("EVT_FALL");
        when(videoRepository.findById(14L)).thenReturn(Optional.of(rawMock));
        when(presetLabelLookup.resolve("EVT_FALL"))
                .thenReturn(PresetResolution.resolved(Map.of("person", new AnnotationToggle(false, true))));
        when(srcRepository.findByRawSnOrderByFrameNoAsc(14L))
                .thenReturn(List.of(newSrc(10L)));
        when(aiServerClient.predictYoloTrack(any(YoloTrackRequest.class), eq(AiWorkload.BATCH), any()))
                .thenReturn(Mono.just(new YoloResponse(List.of(
                        new YoloResponse.Detection("person", List.of(-40.0, 10.0, -5.0, 60.0), 0.92)
                ))));

        List<BbHint> hints = step.run(14L);

        // then — 퇴화 검출은 bbox·polygon 둘 다 스킵.
        assertThat(hints).isEmpty();
        org.mockito.Mockito.verify(lblRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    @DisplayName("폴리곤전용_프리셋의_hint좌표는_이미지_경계로_clamp된_값이다")
    void polygonOnlyPresetHintCarriesClampedPoints() {
        // given — DB BBOX 가 없는 경로(bbox=false)라 hint 가 곧 SAM 프롬프트다.
        LsDataRaw rawMock = rawWithEvent("EVT_FALL");
        when(videoRepository.findById(15L)).thenReturn(Optional.of(rawMock));
        when(presetLabelLookup.resolve("EVT_FALL"))
                .thenReturn(PresetResolution.resolved(Map.of("person", new AnnotationToggle(false, true))));
        when(srcRepository.findByRawSnOrderByFrameNoAsc(15L))
                .thenReturn(List.of(newSrc(10L)));
        when(aiServerClient.predictYoloTrack(any(YoloTrackRequest.class), eq(AiWorkload.BATCH), any()))
                .thenReturn(Mono.just(new YoloResponse(List.of(
                        new YoloResponse.Detection("person",
                                List.of(-1.5731448368773044, 10.0, 1300.0, 721.3), 0.92)
                ))));

        List<BbHint> hints = step.run(15L);

        // then — 1280x720 실측 경계 기준 clamp 된 좌표(구 구현은 원본 -1.57…/1300/721.3 을 그대로 실었다).
        assertThat(hints).hasSize(1);
        assertThat(hints.get(0).points()).containsExactly(0.0, 10.0, 1280.0, 720.0);
    }

    @Test
    @DisplayName("BOTH_프리셋에서_퇴화박스는_BBOX_스킵과_함께_hint도_발행되지_않는다")
    void bothPresetSkipsHintWhenBboxDegenerate() {
        // given — bbox 가 퇴화로 스킵되면 DB BBOX 가 없어 Sam2SegmentStep 이 hint 를 채택한다.
        //   그래서 bbox 스킵과 hint 미발행은 반드시 함께 일어나야 한다.
        LsDataRaw rawMock = rawWithEvent("EVT_FALL");
        when(videoRepository.findById(16L)).thenReturn(Optional.of(rawMock));
        when(presetLabelLookup.resolve("EVT_FALL"))
                .thenReturn(PresetResolution.resolved(Map.of("person", new AnnotationToggle(true, true))));
        when(srcRepository.findByRawSnOrderByFrameNoAsc(16L))
                .thenReturn(List.of(newSrc(10L)));
        when(aiServerClient.predictYoloTrack(any(YoloTrackRequest.class), eq(AiWorkload.BATCH), any()))
                .thenReturn(Mono.just(new YoloResponse(List.of(
                        new YoloResponse.Detection("person", List.of(-40.0, 10.0, -5.0, 60.0), 0.92)
                ))));

        List<BbHint> hints = step.run(16L);

        assertThat(hints).isEmpty();
        org.mockito.Mockito.verify(lblRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    @DisplayName("YOLO_검출_결과_없으면_라벨_미저장")
    void noDetectionsNoSaves() {
        when(srcRepository.findByRawSnOrderByFrameNoAsc(2L))
                .thenReturn(List.of(newSrc(20L)));
        when(aiServerClient.predictYoloTrack(any(YoloTrackRequest.class), eq(AiWorkload.BATCH), any()))
                .thenReturn(Mono.just(new YoloResponse(List.of())));

        List<BbHint> hints = step.run(2L);

        assertThat(hints).isEmpty();
        org.mockito.Mockito.verify(lblRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    @DisplayName("rawSn_null이면_INVALID_INPUT")
    void nullRawSnRejected() {
        assertThatThrownBy(() -> step.run(null))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode().name())
                .isEqualTo("INVALID_INPUT");
    }

    @Test
    @DisplayName("ai_server_예외시_EXTERNAL_API_ERROR")
    void externalErrorWrapped() {
        when(srcRepository.findByRawSnOrderByFrameNoAsc(3L))
                .thenReturn(List.of(newSrc(30L)));
        when(aiServerClient.predictYoloTrack(any(YoloTrackRequest.class), eq(AiWorkload.BATCH), any()))
                .thenReturn(Mono.error(new RuntimeException("ai-server down")));

        assertThatThrownBy(() -> step.run(3L))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode().name())
                .isEqualTo("EXTERNAL_API_ERROR");
    }

    @Test
    @DisplayName("EVT_FALL_영상은_person만_저장_car는_필터링")
    void evtFallFiltersToPersonOnly() {
        LsDataRaw rawMock = rawWithEvent("EVT_FALL");
        when(videoRepository.findById(4L)).thenReturn(Optional.of(rawMock));
        // Phase 2: 토글 맵으로 매핑된 라벨(person=BOTH)만 통과
        when(presetLabelLookup.resolve("EVT_FALL"))
                .thenReturn(PresetResolution.resolved(Map.of("person", new AnnotationToggle(true, true))));
        when(srcRepository.findByRawSnOrderByFrameNoAsc(4L))
                .thenReturn(List.of(newSrc(40L)));
        when(aiServerClient.predictYoloTrack(any(YoloTrackRequest.class), eq(AiWorkload.BATCH), any()))
                .thenReturn(Mono.just(new YoloResponse(List.of(
                        new YoloResponse.Detection("person", List.of(1.0, 2.0, 3.0, 4.0), 0.92),
                        new YoloResponse.Detection("car", List.of(5.0, 6.0, 7.0, 8.0), 0.81),
                        new YoloResponse.Detection("Person", List.of(9.0, 10.0, 11.0, 12.0), 0.75)
                ))));

        List<BbHint> hints = step.run(4L);

        // person 2건 통과 (대소문자 정규화), car 1건 필터링 — BOTH 라벨이므로 BBOX 저장 + hint 발행
        assertThat(hints).hasSize(2);

        ArgumentCaptor<LsDataLbl> captor = ArgumentCaptor.forClass(LsDataLbl.class);
        org.mockito.Mockito.verify(lblRepository, org.mockito.Mockito.times(2)).save(captor.capture());
        captor.getAllValues().forEach(lbl ->
                assertThat(lbl.getLabelNm().toLowerCase()).isEqualTo("person"));
    }

    @Test
    @DisplayName("EVT_ACCIDENT_영상은_차량_사람_오토바이_통과")
    void evtAccidentAllowsVehiclesAndPerson() {
        LsDataRaw rawMock = rawWithEvent("EVT_ACCIDENT");
        when(videoRepository.findById(5L)).thenReturn(Optional.of(rawMock));
        // Phase 2: 토글 맵으로 매핑된 라벨(car/motorcycle/person/truck/bus/bicycle=BOTH)만 통과
        Map<String, AnnotationToggle> allowed = new LinkedHashMap<>();
        for (String label : List.of("car", "motorcycle", "person", "truck", "bus", "bicycle")) {
            allowed.put(label, new AnnotationToggle(true, true));
        }
        when(presetLabelLookup.resolve("EVT_ACCIDENT")).thenReturn(PresetResolution.resolved(allowed));
        when(srcRepository.findByRawSnOrderByFrameNoAsc(5L))
                .thenReturn(List.of(newSrc(50L)));
        when(aiServerClient.predictYoloTrack(any(YoloTrackRequest.class), eq(AiWorkload.BATCH), any()))
                .thenReturn(Mono.just(new YoloResponse(List.of(
                        new YoloResponse.Detection("car", List.of(1.0, 2.0, 3.0, 4.0), 0.92),
                        new YoloResponse.Detection("motorcycle", List.of(5.0, 6.0, 7.0, 8.0), 0.81),
                        new YoloResponse.Detection("person", List.of(9.0, 10.0, 11.0, 12.0), 0.75),
                        new YoloResponse.Detection("trash", List.of(13.0, 14.0, 15.0, 16.0), 0.55)
                ))));

        List<BbHint> hints = step.run(5L);

        // car/motorcycle/person 통과 (BOTH), trash 필터링
        assertThat(hints).hasSize(3);
    }

    @Test
    @DisplayName("ai_server_mock_응답_감지시_WARN_로그_출력_및_파이프라인_계속_진행")
    void mockResponseTriggersWarnLog() {
        when(srcRepository.findByRawSnOrderByFrameNoAsc(7L))
                .thenReturn(List.of(newSrc(10L)));
        // mock=true, source="mock", mock_reason="env_mock" 으로 응답
        YoloResponse mockResp = new YoloResponse(
                List.of(new YoloResponse.Detection("person", List.of(1.0, 2.0, 3.0, 4.0), 0.92)),
                true,
                "mock",
                "env_mock"
        );
        when(aiServerClient.predictYoloTrack(any(YoloTrackRequest.class), eq(AiWorkload.BATCH), any()))
                .thenReturn(Mono.just(mockResp));

        List<BbHint> hints = step.run(7L);

        // mock 응답이라도 파이프라인은 진행 — 라벨 1건 저장됨
        assertThat(hints).hasSize(1);

        // WARN 로그가 최소 1회 출력되고 메시지에 "mock response detected" 포함
        long warnCount = logAppender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .filter(e -> e.getFormattedMessage().contains("mock response detected"))
                .count();
        assertThat(warnCount).isGreaterThanOrEqualTo(1L);
    }

    @Test
    @DisplayName("ai_server_정상_응답시_mock_WARN_로그_없음")
    void realResponseNoWarnLog() {
        when(srcRepository.findByRawSnOrderByFrameNoAsc(8L))
                .thenReturn(List.of(newSrc(10L)));
        // mock=false (실제 모델 응답)
        YoloResponse realResp = new YoloResponse(
                List.of(new YoloResponse.Detection("person", List.of(1.0, 2.0, 3.0, 4.0), 0.92)),
                false,
                "model",
                null
        );
        when(aiServerClient.predictYoloTrack(any(YoloTrackRequest.class), eq(AiWorkload.BATCH), any()))
                .thenReturn(Mono.just(realResp));

        step.run(8L);

        long mockWarnCount = logAppender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .filter(e -> e.getFormattedMessage().contains("mock response detected"))
                .count();
        assertThat(mockWarnCount).isZero();
    }

    @Test
    @DisplayName("★이벤트유형을_특정할_수_없으면_전체통과가_아니라_추론없이_끝난다_구_fail_safe_폐기")
    void unregisteredEventTypeNoLongerPassesEverything() {
        // videoRepository.findById -> Optional.empty() (setUp 기본값) → 이벤트 유형을 특정할 수 없다.
        when(presetLabelLookup.resolve(null))
                .thenReturn(PresetResolution.of(PresetResolutionStatus.EVENT_TYPE_UNREGISTERED));
        when(srcRepository.findByRawSnOrderByFrameNoAsc(6L))
                .thenReturn(List.of(newSrc(10L)));

        List<BbHint> hints = step.run(6L);

        // 구 동작: 전 검출을 BOTH 로 저장(hint 3건). 지금: 추론조차 시작하지 않는다.
        assertThat(hints).isEmpty();
        org.mockito.Mockito.verify(aiServerClient, org.mockito.Mockito.never())
                .predictYoloTrack(any(YoloTrackRequest.class), eq(AiWorkload.BATCH), any());
        org.mockito.Mockito.verify(lblRepository, org.mockito.Mockito.never()).save(any());
    }

    // ─── Phase 2: 라벨별 BBOX/POLYGON 토글 분기 ───

    @Test
    @DisplayName("YoloStep_bboxEnabled_false_라벨은_LS_DATA_LBL_에_BBOX_저장하지_않음")
    void bboxDisabledLabelDoesNotPersist() {
        LsDataRaw rawMock = rawWithEvent("EVT_FALL");
        when(videoRepository.findById(10L)).thenReturn(Optional.of(rawMock));
        // person 은 polygon-only (bbox=false, polygon=true) — BBOX 저장은 skip, hint 만 발행
        when(presetLabelLookup.resolve("EVT_FALL"))
                .thenReturn(PresetResolution.resolved(Map.of("person", new AnnotationToggle(false, true))));
        when(srcRepository.findByRawSnOrderByFrameNoAsc(10L))
                .thenReturn(List.of(newSrc(10L)));
        when(aiServerClient.predictYoloTrack(any(YoloTrackRequest.class), eq(AiWorkload.BATCH), any()))
                .thenReturn(Mono.just(new YoloResponse(List.of(
                        new YoloResponse.Detection("person", List.of(1.0, 2.0, 3.0, 4.0), 0.92)
                ))));

        List<BbHint> hints = step.run(10L);

        // BBOX 저장은 skip — lblRepository.save 호출 0회
        org.mockito.Mockito.verify(lblRepository, org.mockito.Mockito.never()).save(any());
        // polygon=true 이므로 hint 는 발행되어야 함
        assertThat(hints).hasSize(1);
        assertThat(hints.get(0).label()).isEqualTo("person");
    }

    @Test
    @DisplayName("YoloStep_polygonEnabled_true_라벨은_BbHint_로_반환")
    void polygonEnabledLabelReturnsHint() {
        LsDataRaw rawMock = rawWithEvent("EVT_FALL");
        when(videoRepository.findById(11L)).thenReturn(Optional.of(rawMock));
        when(presetLabelLookup.resolve("EVT_FALL"))
                .thenReturn(PresetResolution.resolved(Map.of("person", new AnnotationToggle(true, true))));
        when(srcRepository.findByRawSnOrderByFrameNoAsc(11L))
                .thenReturn(List.of(newSrc(10L)));
        when(aiServerClient.predictYoloTrack(any(YoloTrackRequest.class), eq(AiWorkload.BATCH), any()))
                .thenReturn(Mono.just(new YoloResponse(List.of(
                        new YoloResponse.Detection("person", List.of(1.0, 2.0, 3.0, 4.0), 0.92)
                ))));

        List<BbHint> hints = step.run(11L);

        assertThat(hints).hasSize(1);
        BbHint h = hints.get(0);
        assertThat(h.srcSn()).isEqualTo(10L);
        assertThat(h.label()).isEqualTo("person");
        assertThat(h.points()).containsExactly(1.0, 2.0, 3.0, 4.0);
        assertThat(h.score()).isEqualTo(0.92);
    }

    @Test
    @DisplayName("YoloStep_polygonEnabled_false_라벨은_BbHint_미발행")
    void polygonDisabledLabelOmitsHint() {
        LsDataRaw rawMock = rawWithEvent("EVT_FALL");
        when(videoRepository.findById(12L)).thenReturn(Optional.of(rawMock));
        // person 은 bbox-only (bbox=true, polygon=false) — BBOX 저장만, hint 없음
        when(presetLabelLookup.resolve("EVT_FALL"))
                .thenReturn(PresetResolution.resolved(Map.of("person", new AnnotationToggle(true, false))));
        when(srcRepository.findByRawSnOrderByFrameNoAsc(12L))
                .thenReturn(List.of(newSrc(10L)));
        when(aiServerClient.predictYoloTrack(any(YoloTrackRequest.class), eq(AiWorkload.BATCH), any()))
                .thenReturn(Mono.just(new YoloResponse(List.of(
                        new YoloResponse.Detection("person", List.of(1.0, 2.0, 3.0, 4.0), 0.92)
                ))));

        List<BbHint> hints = step.run(12L);

        // BBOX 1건 저장
        org.mockito.Mockito.verify(lblRepository, org.mockito.Mockito.times(1)).save(any());
        // hint 발행 없음
        assertThat(hints).isEmpty();
    }

    @Test
    @DisplayName("YoloStep_BOTH_라벨은_BBOX_저장_그리고_BbHint_도_반환")
    void bothToggleSavesBboxAndReturnsHint() {
        LsDataRaw rawMock = rawWithEvent("EVT_FALL");
        when(videoRepository.findById(13L)).thenReturn(Optional.of(rawMock));
        when(presetLabelLookup.resolve("EVT_FALL"))
                .thenReturn(PresetResolution.resolved(Map.of("person", new AnnotationToggle(true, true))));
        when(srcRepository.findByRawSnOrderByFrameNoAsc(13L))
                .thenReturn(List.of(newSrc(10L)));
        when(aiServerClient.predictYoloTrack(any(YoloTrackRequest.class), eq(AiWorkload.BATCH), any()))
                .thenReturn(Mono.just(new YoloResponse(List.of(
                        new YoloResponse.Detection("person", List.of(1.0, 2.0, 3.0, 4.0), 0.92)
                ))));

        List<BbHint> hints = step.run(13L);

        // BBOX 1건 저장 + hint 1건 발행 (둘 다)
        org.mockito.Mockito.verify(lblRepository, org.mockito.Mockito.times(1)).save(any());
        assertThat(hints).hasSize(1);
    }

    @Test
    @DisplayName("YoloAutolabelStep_은_SystemConfigService_의_conf_iou_를_읽어_AiServerClient_에_전달")
    void systemConfigValuesPassedToAiServer() {
        when(systemConfigService.getInt(ConfigKeys.YOLO_CONF_THRESHOLD)).thenReturn(55);
        when(systemConfigService.getInt(ConfigKeys.YOLO_IOU)).thenReturn(60);
        when(srcRepository.findByRawSnOrderByFrameNoAsc(20L))
                .thenReturn(List.of(newSrc(10L)));
        when(aiServerClient.predictYoloTrack(any(YoloTrackRequest.class), eq(AiWorkload.BATCH), any()))
                .thenReturn(Mono.just(new YoloResponse(List.of(
                        new YoloResponse.Detection("person", List.of(1.0, 2.0, 3.0, 4.0), 0.92)
                ))));

        step.run(20L);

        ArgumentCaptor<YoloTrackRequest> captor = ArgumentCaptor.forClass(YoloTrackRequest.class);
        org.mockito.Mockito.verify(aiServerClient).predictYoloTrack(captor.capture(), eq(AiWorkload.BATCH), any());
        YoloTrackRequest sent = captor.getValue();
        // 55/100=0.55, 60/100=0.60
        assertThat(sent.confThreshold()).isEqualTo(0.55);
        assertThat(sent.iou()).isEqualTo(0.60);
        // imgsz 는 설정과 무관하게 항상 상수다 — 구 키 YOLO_IMGSZ 는 폐지됐고(ai-server 로더가
        // 640 고정이라 조정이 무효였다) 호출부는 DEFAULT_IMGSZ 를 싣는다. 요청 필드 자체는 남으므로
        // "설정을 바꿔도 이 값은 안 흔들린다"를 여기서 고정한다.
        assertThat(sent.imgsz()).isEqualTo(YoloAutolabelStep.DEFAULT_IMGSZ);
    }

    @Test
    @DisplayName("YoloAutolabelStep_은_SystemConfig_미설정_시_기본값_0_4_1280_0_5_사용")
    void fallbackDefaultsWhenSystemConfigMissing() {
        // setUp 의 기본값(getInt -> null) 유지 — fallback 경로 검증
        when(srcRepository.findByRawSnOrderByFrameNoAsc(21L))
                .thenReturn(List.of(newSrc(10L)));
        when(aiServerClient.predictYoloTrack(any(YoloTrackRequest.class), eq(AiWorkload.BATCH), any()))
                .thenReturn(Mono.just(new YoloResponse(List.of(
                        new YoloResponse.Detection("person", List.of(1.0, 2.0, 3.0, 4.0), 0.92)
                ))));

        step.run(21L);

        ArgumentCaptor<YoloTrackRequest> captor = ArgumentCaptor.forClass(YoloTrackRequest.class);
        org.mockito.Mockito.verify(aiServerClient).predictYoloTrack(captor.capture(), eq(AiWorkload.BATCH), any());
        YoloTrackRequest sent = captor.getValue();
        assertThat(sent.confThreshold()).isEqualTo(0.4);
        assertThat(sent.imgsz()).isEqualTo(1280);
        assertThat(sent.iou()).isEqualTo(0.5);
    }

    @Test
    @DisplayName("YoloAutolabelStep_은_SystemConfig_조회_실패시_기본값_0_4_1280_0_5_사용")
    void fallbackDefaultsWhenSystemConfigThrows() {
        when(systemConfigService.getInt(any()))
                .thenThrow(new RuntimeException("DB down"));
        when(srcRepository.findByRawSnOrderByFrameNoAsc(22L))
                .thenReturn(List.of(newSrc(10L)));
        when(aiServerClient.predictYoloTrack(any(YoloTrackRequest.class), eq(AiWorkload.BATCH), any()))
                .thenReturn(Mono.just(new YoloResponse(List.of(
                        new YoloResponse.Detection("person", List.of(1.0, 2.0, 3.0, 4.0), 0.92)
                ))));

        step.run(22L);

        ArgumentCaptor<YoloTrackRequest> captor = ArgumentCaptor.forClass(YoloTrackRequest.class);
        org.mockito.Mockito.verify(aiServerClient).predictYoloTrack(captor.capture(), eq(AiWorkload.BATCH), any());
        YoloTrackRequest sent = captor.getValue();
        assertThat(sent.confThreshold()).isEqualTo(0.4);
        assertThat(sent.imgsz()).isEqualTo(1280);
        assertThat(sent.iou()).isEqualTo(0.5);
    }

    // ─── Phase 4: track 호출 전환 + trackId 저장 + frame_index 누적 ───

    @Test
    @DisplayName("YoloStep_predictYoloTrack_을_호출하고_trackId_를_LsDataLbl_에_저장")
    void predictYoloTrackCalledAndTrackIdPersisted() {
        when(srcRepository.findByRawSnOrderByFrameNoAsc(30L))
                .thenReturn(List.of(newSrc(10L)));
        // track 응답: trackId=7 (Integer)
        YoloResponse resp = new YoloResponse(List.of(
                new YoloResponse.Detection("person", List.of(1.0, 2.0, 3.0, 4.0), 0.92, 7)
        ));
        when(aiServerClient.predictYoloTrack(any(YoloTrackRequest.class), eq(AiWorkload.BATCH), any()))
                .thenReturn(Mono.just(resp));

        List<BbHint> hints = step.run(30L);

        // BBOX 저장 — trackId 가 LsDataLbl.trackId 에 "7" 문자열로 저장
        ArgumentCaptor<LsDataLbl> captor = ArgumentCaptor.forClass(LsDataLbl.class);
        org.mockito.Mockito.verify(lblRepository, org.mockito.Mockito.times(1)).save(captor.capture());
        assertThat(captor.getValue().getTrackId()).isEqualTo("7");
        // BbHint 에도 Integer trackId 그대로 흘러감
        assertThat(hints).hasSize(1);
        assertThat(hints.get(0).trackId()).isEqualTo(7);
    }

    @Test
    @DisplayName("YoloStep_frame_순서대로_frame_index_0_부터_누적_전달")
    void frameIndexAccumulatesFromZero() {
        // 같은 영상의 3프레임 — clipId="40", frameIndex 는 0,1,2 순서로 전달되어야 함
        when(srcRepository.findByRawSnOrderByFrameNoAsc(40L))
                .thenReturn(List.of(newSrc(10L), newSrc(11L), newSrc(20L)));
        when(aiServerClient.predictYoloTrack(any(YoloTrackRequest.class), eq(AiWorkload.BATCH), any()))
                .thenReturn(Mono.just(new YoloResponse(List.of())));

        step.run(40L);

        ArgumentCaptor<YoloTrackRequest> captor = ArgumentCaptor.forClass(YoloTrackRequest.class);
        org.mockito.Mockito.verify(aiServerClient, org.mockito.Mockito.times(3))
                .predictYoloTrack(captor.capture(), eq(AiWorkload.BATCH), any());
        List<YoloTrackRequest> calls = captor.getAllValues();
        // clipId 는 rawSn 문자열, frameIndex 는 0,1,2 순차
        assertThat(calls).hasSize(3);
        assertThat(calls.get(0).clipId()).isEqualTo("40");
        assertThat(calls.get(0).frameIndex()).isZero();
        assertThat(calls.get(1).clipId()).isEqualTo("40");
        assertThat(calls.get(1).frameIndex()).isEqualTo(1);
        assertThat(calls.get(2).clipId()).isEqualTo("40");
        assertThat(calls.get(2).frameIndex()).isEqualTo(2);
    }

    @Test
    @DisplayName("YoloStep_trackId_null_detection_도_정상_저장_fallback")
    void trackIdNullDetectionStillPersisted() {
        when(srcRepository.findByRawSnOrderByFrameNoAsc(50L))
                .thenReturn(List.of(newSrc(10L)));
        // 4-arg Detection — trackId=null (저신뢰 fallback)
        when(aiServerClient.predictYoloTrack(any(YoloTrackRequest.class), eq(AiWorkload.BATCH), any()))
                .thenReturn(Mono.just(new YoloResponse(List.of(
                        new YoloResponse.Detection("person", List.of(1.0, 2.0, 3.0, 4.0), 0.92)
                ))));

        List<BbHint> hints = step.run(50L);

        ArgumentCaptor<LsDataLbl> captor = ArgumentCaptor.forClass(LsDataLbl.class);
        org.mockito.Mockito.verify(lblRepository, org.mockito.Mockito.times(1)).save(captor.capture());
        assertThat(captor.getValue().getTrackId()).isNull();
        assertThat(hints).hasSize(1);
        assertThat(hints.get(0).trackId()).isNull();
    }

    // ─── V6: 생산이력은 라벨 행의 컬럼이다 ───

    @Test
    @DisplayName("YoloStep_BBOX_저장_시_라벨행에_출처_YOLO와_자동라벨여부_Y가_함께_적재된다")
    void aiInfoPersistedAlongsideBbox() {
        when(srcRepository.findByRawSnOrderByFrameNoAsc(60L))
                .thenReturn(List.of(newSrc(10L)));
        when(aiServerClient.predictYoloTrack(any(YoloTrackRequest.class), eq(AiWorkload.BATCH), any()))
                .thenReturn(Mono.just(new YoloResponse(List.of(
                        new YoloResponse.Detection("person", List.of(1.0, 2.0, 3.0, 4.0), 0.92)
                ))));

        step.run(60L);

        ArgumentCaptor<LsDataLbl> persistedCaptor = ArgumentCaptor.forClass(LsDataLbl.class);
        org.mockito.Mockito.verify(lblRepository, org.mockito.Mockito.times(1)).save(persistedCaptor.capture());
        LsDataLbl persisted = persistedCaptor.getValue();
        assertThat(persisted.getLblSrcCd()).isEqualTo(LsDataLbl.SRC_YOLO);
        assertThat(persisted.getAutoLblYn()).isEqualTo("Y");
    }

    @Test
    @DisplayName("BBOX_저장_skip_시_라벨_자체가_저장되지_않는다")
    void aiInfoNotPersistedWhenBboxSkipped() {
        LsDataRaw rawMock = rawWithEvent("EVT_FALL");
        when(videoRepository.findById(61L)).thenReturn(Optional.of(rawMock));
        // polygon-only: BBOX 저장 skip
        when(presetLabelLookup.resolve("EVT_FALL"))
                .thenReturn(PresetResolution.resolved(Map.of("person", new AnnotationToggle(false, true))));
        when(srcRepository.findByRawSnOrderByFrameNoAsc(61L))
                .thenReturn(List.of(newSrc(10L)));
        when(aiServerClient.predictYoloTrack(any(YoloTrackRequest.class), eq(AiWorkload.BATCH), any()))
                .thenReturn(Mono.just(new YoloResponse(List.of(
                        new YoloResponse.Detection("person", List.of(1.0, 2.0, 3.0, 4.0), 0.92)
                ))));

        step.run(61L);

        // BBOX 저장 0회 → AI Info 도 0회
        org.mockito.Mockito.verify(lblRepository, org.mockito.Mockito.never()).save(any());
        org.mockito.Mockito.verify(lblRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    @DisplayName("★실효_프리셋의_토글맵에_없는_검출은_저장하지_않는다_구_BOTH_fail_safe_폐기")
    void detectionOutsidePresetIsNotPersisted() {
        // 프리셋은 person 만 담았는데 ai-server 가 car 를 검출한 경우.
        // 구 동작: 토글 맵에 없으면 BOTH 로 폴백해 저장. 지금: 저장 기준이 없으므로 버린다.
        LsDataRaw rawMock = rawWithEvent("EVT_FALL");
        when(videoRepository.findById(14L)).thenReturn(Optional.of(rawMock));
        when(presetLabelLookup.resolve("EVT_FALL")).thenReturn(PresetResolution.resolved(
                Map.of("person", new AnnotationToggle(true, true))));
        when(srcRepository.findByRawSnOrderByFrameNoAsc(14L))
                .thenReturn(List.of(newSrc(10L)));
        when(aiServerClient.predictYoloTrack(any(YoloTrackRequest.class), eq(AiWorkload.BATCH), any()))
                .thenReturn(Mono.just(new YoloResponse(List.of(
                        new YoloResponse.Detection("car", List.of(1.0, 2.0, 3.0, 4.0), 0.92)
                ))));

        List<BbHint> hints = step.run(14L);

        org.mockito.Mockito.verify(lblRepository, org.mockito.Mockito.never()).save(any());
        assertThat(hints).isEmpty();
    }

    // ─── Phase 6 — AutoLabel preset 매핑 (LS_LABEL.NAME → LABEL_ID) ───

    @Test
    @DisplayName("YOLO_person_응답_시_LABEL_ID_매칭됨")
    void yoloLabelIdMappedFromMaster() {
        when(srcRepository.findByRawSnOrderByFrameNoAsc(100L))
                .thenReturn(List.of(newSrc(10L)));
        when(aiServerClient.predictYoloTrack(any(YoloTrackRequest.class), eq(AiWorkload.BATCH), any()))
                .thenReturn(Mono.just(new YoloResponse(List.of(
                        new YoloResponse.Detection("person", List.of(1.0, 2.0, 3.0, 4.0), 0.92)
                ))));
        when(labelMasterService.findLabelIdByDtctType("person")).thenReturn(Optional.of(1L));

        step.run(100L);

        ArgumentCaptor<LsDataLbl> captor = ArgumentCaptor.forClass(LsDataLbl.class);
        org.mockito.Mockito.verify(lblRepository, org.mockito.Mockito.times(1)).save(captor.capture());
        assertThat(captor.getValue().getLabelId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("YOLO_unknown_label_응답_시_LABEL_ID_null")
    void yoloUnknownLabelIdRemainsNull() {
        when(srcRepository.findByRawSnOrderByFrameNoAsc(101L))
                .thenReturn(List.of(newSrc(10L)));
        when(aiServerClient.predictYoloTrack(any(YoloTrackRequest.class), eq(AiWorkload.BATCH), any()))
                .thenReturn(Mono.just(new YoloResponse(List.of(
                        new YoloResponse.Detection("rare_label_unknown", List.of(1.0, 2.0, 3.0, 4.0), 0.92)
                ))));
        // 기본 stub: findLabelIdByDtctType(anyString()) → Optional.empty() (setUp 에서 설정)

        step.run(101L);

        ArgumentCaptor<LsDataLbl> captor = ArgumentCaptor.forClass(LsDataLbl.class);
        org.mockito.Mockito.verify(lblRepository, org.mockito.Mockito.times(1)).save(captor.capture());
        assertThat(captor.getValue().getLabelId()).isNull();
    }

    // ─── Phase 4 V2.0: YOLO 원본 프레임 전용 ───

    @Test
    @DisplayName("V2_YOLO_원본_프레임만_실행_비식별_경로_존재해도_원본_사용")
    void yoloUsesRawImagePathNotDeid() throws IOException {
        // 원본 이미지와 비식별 이미지를 다른 내용으로 생성
        Path rawDir = tempDir.resolve("raw");
        byte[] rawContent = {(byte) 0xFF, (byte) 0xD8, 0x01};
        byte[] deidContent = {(byte) 0xFF, (byte) 0xD8, 0x02};
        Files.write(rawDir.resolve("raw-frame.jpg"), rawContent);
        Path deidDir = rawDir.resolve("deid");
        Files.createDirectories(deidDir);
        Files.write(deidDir.resolve("deid-frame.jpg"), deidContent);

        // LsDataSrc 에 filePath + deidFilePath 모두 설정
        LsDataSrc srcWithDeid = LsDataSrc.create(1L, 0, "raw-frame.jpg", null);
        try {
            Field f = LsDataSrc.class.getDeclaredField("srcSn");
            f.setAccessible(true);
            f.set(srcWithDeid, 10L);
        } catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
        srcWithDeid.attachDeidPath("deid/deid-frame.jpg");

        when(srcRepository.findByRawSnOrderByFrameNoAsc(70L))
                .thenReturn(List.of(srcWithDeid));
        when(aiServerClient.predictYoloTrack(any(YoloTrackRequest.class), eq(AiWorkload.BATCH), any()))
                .thenReturn(Mono.just(new YoloResponse(List.of(
                        new YoloResponse.Detection("person", List.of(1.0, 2.0, 3.0, 4.0), 0.92)
                ))));

        step.run(70L);

        // ai-server 에 전달된 이미지가 원본(rawContent) 인지 검증
        ArgumentCaptor<YoloTrackRequest> captor = ArgumentCaptor.forClass(YoloTrackRequest.class);
        org.mockito.Mockito.verify(aiServerClient).predictYoloTrack(captor.capture(), eq(AiWorkload.BATCH), any());
        String sentB64 = captor.getValue().imageB64();
        byte[] decoded = java.util.Base64.getDecoder().decode(sentB64);
        // 원본 프레임 내용과 동일해야 함 (비식별 아님)
        assertThat(decoded).isEqualTo(rawContent);
    }

    // ─── Phase 3: 마스터 형태 파생 토글 → 오토라벨 생성 분기 (AC5) ───

    @Test
    @DisplayName("마스터_형태_BBOX면_오토라벨은_bbox만_생성한다")
    void masterBboxCreatesBboxOnly() {
        // 마스터(person)=BBOX → 서비스가 파생한 토글 (bbox=true, polygon=false)
        LsDataRaw rawMock = rawWithEvent("EVT_FALL");
        when(videoRepository.findById(200L)).thenReturn(Optional.of(rawMock));
        when(presetLabelLookup.resolve("EVT_FALL"))
                .thenReturn(PresetResolution.resolved(Map.of("person", new AnnotationToggle(true, false))));
        when(srcRepository.findByRawSnOrderByFrameNoAsc(200L)).thenReturn(List.of(newSrc(10L)));
        when(aiServerClient.predictYoloTrack(any(YoloTrackRequest.class), eq(AiWorkload.BATCH), any()))
                .thenReturn(Mono.just(new YoloResponse(List.of(
                        new YoloResponse.Detection("person", List.of(1.0, 2.0, 3.0, 4.0), 0.92)
                ))));

        List<BbHint> hints = step.run(200L);

        // BBOX 1건 저장, polygon hint 미발행
        org.mockito.Mockito.verify(lblRepository, org.mockito.Mockito.times(1)).save(any());
        assertThat(hints).isEmpty();
    }

    @Test
    @DisplayName("마스터_형태_POLYGON이면_polygon만_생성한다")
    void masterPolygonCreatesPolygonOnly() {
        // 마스터(person)=POLYGON → 파생 토글 (bbox=false, polygon=true)
        LsDataRaw rawMock = rawWithEvent("EVT_FALL");
        when(videoRepository.findById(201L)).thenReturn(Optional.of(rawMock));
        when(presetLabelLookup.resolve("EVT_FALL"))
                .thenReturn(PresetResolution.resolved(Map.of("person", new AnnotationToggle(false, true))));
        when(srcRepository.findByRawSnOrderByFrameNoAsc(201L)).thenReturn(List.of(newSrc(10L)));
        when(aiServerClient.predictYoloTrack(any(YoloTrackRequest.class), eq(AiWorkload.BATCH), any()))
                .thenReturn(Mono.just(new YoloResponse(List.of(
                        new YoloResponse.Detection("person", List.of(1.0, 2.0, 3.0, 4.0), 0.92)
                ))));

        List<BbHint> hints = step.run(201L);

        // BBOX 미저장, polygon hint 1건 발행 (SAM2 단계로 전달)
        org.mockito.Mockito.verify(lblRepository, org.mockito.Mockito.never()).save(any());
        assertThat(hints).hasSize(1);
        assertThat(hints.get(0).label()).isEqualTo("person");
    }

    @Test
    @DisplayName("마스터_형태_POINT_SKELETON이면_bbox_polygon_오토라벨을_생성하지_않는다")
    void masterPointSkeletonCreatesNothing() {
        // 마스터(person)=POINT/SKELETON → 파생 토글 (false, false) → 도형 오토라벨 미생성
        LsDataRaw rawMock = rawWithEvent("EVT_FALL");
        when(videoRepository.findById(202L)).thenReturn(Optional.of(rawMock));
        when(presetLabelLookup.resolve("EVT_FALL"))
                .thenReturn(PresetResolution.resolved(Map.of("person", new AnnotationToggle(false, false))));
        when(srcRepository.findByRawSnOrderByFrameNoAsc(202L)).thenReturn(List.of(newSrc(10L)));
        when(aiServerClient.predictYoloTrack(any(YoloTrackRequest.class), eq(AiWorkload.BATCH), any()))
                .thenReturn(Mono.just(new YoloResponse(List.of(
                        new YoloResponse.Detection("person", List.of(1.0, 2.0, 3.0, 4.0), 0.92)
                ))));

        List<BbHint> hints = step.run(202L);

        // bbox/polygon 어느 것도 만들지 않음
        org.mockito.Mockito.verify(lblRepository, org.mockito.Mockito.never()).save(any());
        assertThat(hints).isEmpty();
    }

    @Test
    @DisplayName("검출라벨이_COCO_검출축에서_토글매칭되어_labelId가_부여된다")
    void detectionMatchesDtctTypeAxisForLabelId() {
        // 검출 라벨 "Person" 이 COCO 검출축(정규화)에서 토글 조회 + labelId 부여되는 회귀 보존.
        LsDataRaw rawMock = rawWithEvent("EVT_FALL");
        when(videoRepository.findById(203L)).thenReturn(Optional.of(rawMock));
        when(presetLabelLookup.resolve("EVT_FALL"))
                .thenReturn(PresetResolution.resolved(Map.of("person", new AnnotationToggle(true, false))));
        when(srcRepository.findByRawSnOrderByFrameNoAsc(203L)).thenReturn(List.of(newSrc(10L)));
        when(aiServerClient.predictYoloTrack(any(YoloTrackRequest.class), eq(AiWorkload.BATCH), any()))
                .thenReturn(Mono.just(new YoloResponse(List.of(
                        new YoloResponse.Detection("Person", List.of(1.0, 2.0, 3.0, 4.0), 0.92)
                ))));
        when(labelMasterService.findLabelIdByDtctType("Person")).thenReturn(Optional.of(1L));

        step.run(203L);

        ArgumentCaptor<LsDataLbl> captor = ArgumentCaptor.forClass(LsDataLbl.class);
        org.mockito.Mockito.verify(lblRepository, org.mockito.Mockito.times(1)).save(captor.capture());
        assertThat(captor.getValue().getLabelId()).isEqualTo(1L);
    }

    // ─── Phase 4 DEV_FIX: 배치 오토라벨 preset toggle 축을 COCO(DTCT_TYPE_CD)로 정렬 (통합) ───

    @Test
    @DisplayName("한글마스터명_사람_dtctType_person_preset구성시_배치가_검출을_정상저장하고_labelId귀속_및_SAM2hint전파")
    void koreanMasterWithCocoDtctTypePresetSavesDetectionsEndToEnd() {
        // given — 실제 PresetLabelLookupService 로 togglesFor 축 계산까지 관통 검증(mock 우회 금지).
        // 한글 마스터명("사람"/"차량") + COCO 매핑(dtctType person/car) + preset 구성.
        String evCode = "EV01000102";
        String categoryKey = "010001";
        LsLabelPresetRepository presetRepository = mock(LsLabelPresetRepository.class);
        EventTypeService eventTypeService = mock(EventTypeService.class);
        when(eventTypeService.filterKeyOf(evCode)).thenReturn(Optional.of(categoryKey));
        // 사람=BBOX(dtctType person), 차량=POLYGON(dtctType car) 두 코드로 프리셋 구성.
        LsLabelPreset preset = LsLabelPreset.createWithOptions(
                List.of(new LsLabelPreset.LabelCodeSpec(1L, null),
                        new LsLabelPreset.LabelCodeSpec(2L, null)),
                categoryKey);
        when(presetRepository.findByEventTypeCd(categoryKey)).thenReturn(Optional.of(preset));
        when(labelMasterService.findActiveByIds(any())).thenReturn(Map.of(
                1L, new LabelMasterResponse(1L, "사람", "#112233", "BBOX", 0, "Y", "person"),
                2L, new LabelMasterResponse(2L, "차량", "#112233", "POLYGON", 0, "Y", "car")));
        PresetLabelLookupService realLookup =
                new PresetLabelLookupService(presetRepository, eventTypeService, labelMasterService);

        YoloAutolabelStep realStep = new YoloAutolabelStep(aiServerClient, srcRepository, lblRepository,
                videoRepository, realLookup, batchStatusService, systemConfigService, labelMasterService,
                frameBoundsResolver, new ObjectMapper(), tempDir.resolve("raw").toString(),
                devEnvironment(), mock(kr.co.cudo.authoring.aiserver.service.AiSrvrBatchAssignment.class));

        LsDataRaw rawMock = rawWithEvent(evCode);
        when(videoRepository.findById(300L)).thenReturn(Optional.of(rawMock));
        when(srcRepository.findByRawSnOrderByFrameNoAsc(300L)).thenReturn(List.of(newSrc(10L)));
        when(aiServerClient.predictYoloTrack(any(YoloTrackRequest.class), eq(AiWorkload.BATCH), any()))
                .thenReturn(Mono.just(new YoloResponse(List.of(
                        new YoloResponse.Detection("person", List.of(1.0, 2.0, 3.0, 4.0), 0.92),
                        new YoloResponse.Detection("car", List.of(5.0, 6.0, 7.0, 8.0), 0.81)
                ))));
        when(labelMasterService.findLabelIdByDtctType("person")).thenReturn(Optional.of(1L));
        when(labelMasterService.findLabelIdByDtctType("car")).thenReturn(Optional.of(2L));

        // when
        List<BbHint> hints = realStep.run(300L);

        // then — 수정 전(라벨명축)엔 togglesFor 키가 "사람"/"차량"이라 검출 person/car 가 map miss →
        // 전량 무음 드롭(save 0건, hints 0건)으로 RED. 수정 후(COCO축)엔 정상 저장/전파.
        // 사람=BBOX → BBOX 1건 저장(labelId=1), 차량=POLYGON → SAM2 hint 전파(car).
        ArgumentCaptor<LsDataLbl> captor = ArgumentCaptor.forClass(LsDataLbl.class);
        org.mockito.Mockito.verify(lblRepository, org.mockito.Mockito.times(1)).save(captor.capture());
        assertThat(captor.getValue().getLabelId()).isEqualTo(1L);
        assertThat(captor.getValue().getLblTypeCd()).isEqualTo("BBOX");
        // SAM2 로 전달되는 POLYGON 힌트(차량=car) 전파 확인.
        assertThat(hints).hasSize(1);
        assertThat(hints.get(0).label()).isEqualTo("car");
    }

    @Test
    @DisplayName("YOLO_매핑_로그_검증")
    void yoloMappingLogged() {
        when(srcRepository.findByRawSnOrderByFrameNoAsc(102L))
                .thenReturn(List.of(newSrc(10L)));
        when(aiServerClient.predictYoloTrack(any(YoloTrackRequest.class), eq(AiWorkload.BATCH), any()))
                .thenReturn(Mono.just(new YoloResponse(List.of(
                        new YoloResponse.Detection("person", List.of(1.0, 2.0, 3.0, 4.0), 0.92)
                ))));
        when(labelMasterService.findLabelIdByDtctType("person")).thenReturn(Optional.of(1L));

        step.run(102L);

        // 로그 메시지 검증: [Batch][Yolo] mapped label name=person labelId=1
        long matchedLogs = logAppender.list.stream()
                .filter(e -> e.getFormattedMessage().contains("[Batch][Yolo] mapped label"))
                .filter(e -> e.getFormattedMessage().contains("name=person"))
                .filter(e -> e.getFormattedMessage().contains("labelId=1"))
                .count();
        assertThat(matchedLogs).isGreaterThanOrEqualTo(1L);
    }

    // ============ B-ISSUE-42 — 루프 내 개별 save() → 프레임 단위 saveAll() ============

    @Test
    @DisplayName("한_프레임의_검출들은_라벨_saveAll_1회와_AI메타_saveAll_1회로_저장된다")
    void detectionsOfOneFramePersistedWithSingleSaveAll() {
        // given — 프레임 1개 × 검출 3건
        when(srcRepository.findByRawSnOrderByFrameNoAsc(700L)).thenReturn(List.of(newSrc(10L)));
        when(aiServerClient.predictYoloTrack(any(YoloTrackRequest.class), eq(AiWorkload.BATCH), any()))
                .thenReturn(Mono.just(new YoloResponse(List.of(
                        new YoloResponse.Detection("person", List.of(1.0, 2.0, 3.0, 4.0), 0.92),
                        new YoloResponse.Detection("car", List.of(5.0, 6.0, 7.0, 8.0), 0.81),
                        new YoloResponse.Detection("dog", List.of(9.0, 10.0, 11.0, 12.0), 0.55)
                ))));

        // when
        step.run(700L);

        // then — 검출 3건이 saveAll 1회로 묶인다(구 구현은 save 3회 + save 3회였다)
        ArgumentCaptor<Iterable<LsDataLbl>> lblCaptor = ArgumentCaptor.forClass(Iterable.class);
        org.mockito.Mockito.verify(lblRepository, org.mockito.Mockito.times(1)).saveAll(lblCaptor.capture());
        assertThat(lblCaptor.getValue()).hasSize(3);
    }

    @Test
    @DisplayName("라벨마다_자기_신뢰도와_출처를_들고_저장된다")
    void aiInfoRowsMatchTheirOwnLabelPk() {
        // given — 신뢰도가 서로 다른 검출 3건(순서가 뒤섞이면 매칭이 깨지는 것을 관측 가능하게)
        when(srcRepository.findByRawSnOrderByFrameNoAsc(701L)).thenReturn(List.of(newSrc(10L)));
        when(aiServerClient.predictYoloTrack(any(YoloTrackRequest.class), eq(AiWorkload.BATCH), any()))
                .thenReturn(Mono.just(new YoloResponse(List.of(
                        new YoloResponse.Detection("person", List.of(1.0, 2.0, 3.0, 4.0), 0.11),
                        new YoloResponse.Detection("car", List.of(5.0, 6.0, 7.0, 8.0), 0.55),
                        new YoloResponse.Detection("dog", List.of(9.0, 10.0, 11.0, 12.0), 0.99)
                ))));

        // when
        step.run(701L);

        // V6 — 구 검증은 "AI 메타 i 가 라벨 i 의 PK 를 갖는가"(두 리스트의 인덱스 대응)였다. 생산이력이
        //   같은 행이 되어 <b>어긋날 대상 자체가 없어졌으므로</b>, 각 라벨이 자기 신뢰도·출처를 들고
        //   저장되는지로 축을 옮긴다(신뢰도가 서로 다른 3건이라 뒤바뀌면 관측된다).
        ArgumentCaptor<Iterable<LsDataLbl>> lblCaptor = ArgumentCaptor.forClass(Iterable.class);
        org.mockito.Mockito.verify(lblRepository).saveAll(lblCaptor.capture());

        List<LsDataLbl> labels = new java.util.ArrayList<>();
        lblCaptor.getValue().forEach(labels::add);

        assertThat(labels).hasSize(3);
        for (LsDataLbl l : labels) {
            assertThat(l.getLblSrcCd()).isEqualTo(LsDataLbl.SRC_YOLO);
            assertThat(l.getAutoLblYn()).isEqualTo("Y");
        }
        assertThat(labels).extracting(LsDataLbl::getConfScore)
                .extracting(java.math.BigDecimal::doubleValue)
                .containsExactly(0.11, 0.55, 0.99);
    }

    @Test
    @DisplayName("프레임마다_saveAll이_분리_호출된다")
    void saveAllIsInvokedPerFrame() {
        // given — 프레임 2개 × 검출 1건
        when(srcRepository.findByRawSnOrderByFrameNoAsc(702L))
                .thenReturn(List.of(newSrc(10L), newSrc(11L)));
        when(aiServerClient.predictYoloTrack(any(YoloTrackRequest.class), eq(AiWorkload.BATCH), any()))
                .thenReturn(Mono.just(new YoloResponse(List.of(
                        new YoloResponse.Detection("person", List.of(1.0, 2.0, 3.0, 4.0), 0.92)
                ))));

        // when
        step.run(702L);

        // then — 프레임 단위 flush (메모리 상한 확보)
        org.mockito.Mockito.verify(lblRepository, org.mockito.Mockito.times(2)).saveAll(any());
        org.mockito.Mockito.verify(lblRepository, org.mockito.Mockito.times(2)).saveAll(any());
    }

    @Test
    @DisplayName("저장할_검출이_없는_프레임은_saveAll을_호출하지_않는다")
    void emptyFrameDoesNotCallSaveAll() {
        // given — 검출 0건
        when(srcRepository.findByRawSnOrderByFrameNoAsc(703L)).thenReturn(List.of(newSrc(10L)));
        when(aiServerClient.predictYoloTrack(any(YoloTrackRequest.class), eq(AiWorkload.BATCH), any()))
                .thenReturn(Mono.just(new YoloResponse(List.of())));

        // when
        step.run(703L);

        // then — 빈 saveAll 로도 왕복하지 않는다
        org.mockito.Mockito.verify(lblRepository, org.mockito.Mockito.never()).saveAll(any());
        org.mockito.Mockito.verify(lblRepository, org.mockito.Mockito.never()).saveAll(any());
    }

    @Test
    @DisplayName("퇴화_검출은_배치에서_빠지고_나머지는_그대로_저장된다")
    void degenerateDetectionIsExcludedFromBatchWhileOthersPersist() {
        // given — 이미지(1280x720) 완전 밖 검출 1건 + 정상 검출 1건.
        //   B-ISSUE-42 리팩터링 후에도 "퇴화·형식위반은 스킵하고 나머지는 저장" 시맨틱이 유지돼야 한다.
        when(srcRepository.findByRawSnOrderByFrameNoAsc(704L)).thenReturn(List.of(newSrc(10L)));
        when(aiServerClient.predictYoloTrack(any(YoloTrackRequest.class), eq(AiWorkload.BATCH), any()))
                .thenReturn(Mono.just(new YoloResponse(List.of(
                        new YoloResponse.Detection("person", List.of(-50.0, -40.0, -10.0, -5.0), 0.92),
                        new YoloResponse.Detection("car", List.of(5.0, 6.0, 7.0, 8.0), 0.81)
                ))));

        // when
        step.run(704L);

        // then — 배치에는 정상 1건만 담긴다
        ArgumentCaptor<Iterable<LsDataLbl>> lblCaptor = ArgumentCaptor.forClass(Iterable.class);
        org.mockito.Mockito.verify(lblRepository, org.mockito.Mockito.times(1)).saveAll(lblCaptor.capture());
        List<LsDataLbl> labels = new java.util.ArrayList<>();
        lblCaptor.getValue().forEach(labels::add);
        assertThat(labels).hasSize(1);
        assertThat(labels.get(0).getLabelNm()).isEqualTo("car");
    }

    // ── 재실행 멱등 (@req R1) — 자동 재시도가 파이프라인을 선두부터 다시 돌려도 중복 적재하지 않는다 ──

    /**
     * 시나리오 3 — YOLO 성공 → 사람이 그 라벨을 수정 → 뒷단계(SAM2) 실패 → 재시도.
     *
     * <p>이미 YOLO 자동 라벨이 있는 프레임은 <b>적재만</b> 건너뛴다. 중복 적재도 없고, 무엇보다
     * <b>삭제도 없다</b> — 사람이 이미 수정한 라벨을 지우면 작업 결과가 파괴되기 때문이다.
     *
     * <p>⚠ <b>이름·단언 정정(DEV_FIX)</b>: 구 테스트명은 "추론과 적재를 건너뛰고" 였고 추론 1회·힌트 1건을
     * 단언했다. 그 동작은 폐기됐다 — 추론까지 건너뛰면 그 프레임의 {@link BbHint} 가 재발행되지 않아
     * SAM2 가 조용히 빈손으로 완주한다. 지금은 추론 2회·힌트 2건이 <b>정상</b>이며 적재만 1건이다.
     */
    @Test
    @DisplayName("이미_YOLO_자동라벨이_있는_프레임은_적재만_건너뛰고_기존_라벨을_지우지_않는다")
    void skipsOnlyPersistenceForFramesThatAlreadyHaveYoloLabels() {
        // given — 프레임 10 에는 이미 YOLO 자동 라벨이 있고, 11 에는 없다.
        when(srcRepository.findByRawSnOrderByFrameNoAsc(801L))
                .thenReturn(List.of(newSrc(10L), newSrc(11L)));
        when(lblRepository.findDistinctSrcSnsByRawSnAndLblSrcCd(801L, LsDataLbl.SRC_YOLO))
                .thenReturn(List.of(10L));
        when(aiServerClient.predictYoloTrack(any(YoloTrackRequest.class), eq(AiWorkload.BATCH), any()))
                .thenReturn(Mono.just(new YoloResponse(List.of(
                        new YoloResponse.Detection("person", List.of(1.0, 2.0, 3.0, 4.0), 0.92)))));

        // when
        List<BbHint> hints = step.run(801L);

        // then — 추론은 두 프레임 모두 수행(다음 단계 입력 재현), 적재는 프레임 11 것 1건만.
        org.mockito.Mockito.verify(aiServerClient, org.mockito.Mockito.times(2))
                .predictYoloTrack(any(YoloTrackRequest.class), eq(AiWorkload.BATCH), any());
        org.mockito.Mockito.verify(lblRepository, org.mockito.Mockito.times(1)).save(any(LsDataLbl.class));
        assertThat(hints).extracting(BbHint::srcSn).containsExactly(10L, 11L);
        // ★ 사람의 수정 보호 — 기존 자동 라벨을 삭제하는 경로가 없어야 한다(보간 스텝과 다른 축).
        org.mockito.Mockito.verify(lblRepository, org.mockito.Mockito.never()).deleteByRawSnAutoLbl(anyLong());
        org.mockito.Mockito.verify(lblRepository, org.mockito.Mockito.never()).deleteAllByIdInBatch(any());
        // V6 — 멱등 skip 은 <b>아무것도 지우지 않는다</b>. 생산이력이 같은 행이 되어 구 검증 축
        //   (AI 메타 삭제 미호출)이 사라졌으므로 라벨 삭제 미호출로 옮긴다.
        org.mockito.Mockito.verify(lblRepository, org.mockito.Mockito.never()).deleteAllByIdInBatch(any());
        // 적재를 건너뛴 프레임은 라벨셋 버전도 올리지 않는다(편집 중 작업자를 409 로 밀어내지 않는다).
        ArgumentCaptor<java.util.Collection<Long>> bumpCap = ArgumentCaptor.forClass(java.util.Collection.class);
        org.mockito.Mockito.verify(srcRepository).bumpLabelVersionIn(bumpCap.capture());
        assertThat(bumpCap.getValue()).containsExactly(11L);
    }

    /**
     * 전 프레임이 이미 처리된 재시도 — <b>적재</b>가 0건이다(저장·bump 0건).
     *
     * <p>⚠ <b>단언 정정(DEV_FIX)</b>: 구 테스트는 같은 상황에서 "<b>외부 추론</b>이 0회"를 단언했다.
     * 그 단언은 이제 <b>틀렸다</b> — 추론을 0회로 만들면 {@link BbHint} 가 0건이 되어 SAM2 가 할 일 없이
     * 0 을 반환하고, 폴리곤 없이 배치가 COMPLETED 로 완주하는 조용한 손실이 된다. "외부 호출 0회"라는
     * 축은 <b>하위 단계에 입력을 주지 않는</b> SAM2 로 옮겼다
     * ({@code Sam2SegmentStepTest.이미_SAM2_폴리곤이_있는_프레임은_추론과_적재를_건너뛰고…}).
     * 여기서는 멱등의 본래 목적인 "중복 적재 0건"을 고정한다.
     */
    @Test
    @DisplayName("모든_프레임이_이미_처리됐으면_적재가_0건이다")
    void skipsAllPersistenceWhenAllFramesAlreadyLabeled() {
        when(srcRepository.findByRawSnOrderByFrameNoAsc(802L))
                .thenReturn(List.of(newSrc(10L), newSrc(11L)));
        when(lblRepository.findDistinctSrcSnsByRawSnAndLblSrcCd(802L, LsDataLbl.SRC_YOLO))
                .thenReturn(List.of(10L, 11L));
        when(aiServerClient.predictYoloTrack(any(YoloTrackRequest.class), eq(AiWorkload.BATCH), any()))
                .thenReturn(Mono.just(new YoloResponse(List.of(
                        new YoloResponse.Detection("person", List.of(1.0, 2.0, 3.0, 4.0), 0.92)))));

        List<BbHint> hints = step.run(802L);

        // 적재·bump 는 0건 — 중복 적재 차단이 멱등의 목적이다.
        org.mockito.Mockito.verify(lblRepository, org.mockito.Mockito.never()).saveAll(any());
        org.mockito.Mockito.verify(lblRepository, org.mockito.Mockito.never()).save(any(LsDataLbl.class));
        org.mockito.Mockito.verify(lblRepository, org.mockito.Mockito.never()).saveAll(any());
        org.mockito.Mockito.verify(srcRepository, org.mockito.Mockito.never()).bumpLabelVersionIn(any());
        // 그러면서도 다음 단계 입력은 살아 있어야 한다.
        assertThat(hints).extracting(BbHint::srcSn).containsExactly(10L, 11L);
    }

    /**
     * ★ DEV_FIX 핵심 가드 — 적재를 건너뛴 프레임도 <b>다음 단계 입력(hints)은 발행</b>해야 한다.
     *
     * <p>이 단언이 깨지면 "YOLO 성공 → SAM2 실패 → 재시도" 에서 SAM2 가 빈 hints 를 받아 예외 없이 0 을
     * 반환하고, 배치가 폴리곤 없이 COMPLETED 로 완주한다(오류 신호 0건의 조용한 손실).
     */
    @Test
    @DisplayName("이미_YOLO_라벨이_있어도_추론은_수행해_다음_단계_힌트를_발행한다")
    void emitsHintsEvenWhenPersistenceIsSkipped() {
        // given — 유일한 프레임이 이미 적재 완료 상태(재시도 회차).
        when(srcRepository.findByRawSnOrderByFrameNoAsc(804L)).thenReturn(List.of(newSrc(10L)));
        when(lblRepository.findDistinctSrcSnsByRawSnAndLblSrcCd(804L, LsDataLbl.SRC_YOLO))
                .thenReturn(List.of(10L));
        when(aiServerClient.predictYoloTrack(any(YoloTrackRequest.class), eq(AiWorkload.BATCH), any()))
                .thenReturn(Mono.just(new YoloResponse(List.of(
                        new YoloResponse.Detection("person", List.of(1.0, 2.0, 3.0, 4.0), 0.92)))));

        // when
        List<BbHint> hints = step.run(804L);

        // then — ①추론 ≥1회 ②적재 0건 ③힌트 >0
        org.mockito.Mockito.verify(aiServerClient, org.mockito.Mockito.atLeastOnce())
                .predictYoloTrack(any(YoloTrackRequest.class), eq(AiWorkload.BATCH), any());
        org.mockito.Mockito.verify(lblRepository, org.mockito.Mockito.never()).save(any(LsDataLbl.class));
        org.mockito.Mockito.verify(lblRepository, org.mockito.Mockito.never()).saveAll(any());
        assertThat(hints).isNotEmpty();
        assertThat(hints).extracting(BbHint::srcSn).containsExactly(10L);
        // 힌트 좌표도 최초 실행과 동일하게 clamp 된 값이어야 한다(SAM2 프롬프트 입력).
        assertThat(hints.get(0).points()).containsExactly(1.0, 2.0, 3.0, 4.0);
    }

    /**
     * 시나리오 6(회귀 — 가장 중요) — 멱등 가드가 <b>정상 최초 실행</b>을 막아서는 안 된다.
     * 기존 자동 라벨이 0건이면 종전과 동일하게 전 프레임을 추론·적재한다.
     */
    @Test
    @DisplayName("기존_자동라벨이_0건이면_멱등_가드가_최초_전량_추론을_막지_않는다")
    void firstRunNotBlockedByIdempotencyGuard() {
        when(srcRepository.findByRawSnOrderByFrameNoAsc(803L))
                .thenReturn(List.of(newSrc(10L), newSrc(11L)));
        when(lblRepository.findDistinctSrcSnsByRawSnAndLblSrcCd(803L, LsDataLbl.SRC_YOLO))
                .thenReturn(List.of());
        when(aiServerClient.predictYoloTrack(any(YoloTrackRequest.class), eq(AiWorkload.BATCH), any()))
                .thenReturn(Mono.just(new YoloResponse(List.of(
                        new YoloResponse.Detection("person", List.of(1.0, 2.0, 3.0, 4.0), 0.92)))));

        List<BbHint> hints = step.run(803L);

        assertThat(hints).hasSize(2);
        org.mockito.Mockito.verify(aiServerClient, org.mockito.Mockito.times(2)).predictYoloTrack(any(), eq(AiWorkload.BATCH), any());
        org.mockito.Mockito.verify(lblRepository, org.mockito.Mockito.times(2)).save(any(LsDataLbl.class));
    }
}
