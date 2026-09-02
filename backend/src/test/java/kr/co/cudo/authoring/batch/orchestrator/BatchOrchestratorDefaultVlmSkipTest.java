package kr.co.cudo.authoring.batch.orchestrator;

import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.pipeline.BatchPipeline;
import kr.co.cudo.authoring.batch.retry.BatchRetryQueue;
import kr.co.cudo.authoring.batch.status.BatchStatusService;
import kr.co.cudo.authoring.batch.status.BatchTransitionService;
import kr.co.cudo.authoring.batch.status.LsBatchProcLog;
import kr.co.cudo.authoring.batch.status.ManualStageSkip;
import kr.co.cudo.authoring.batch.status.VlmDefaultSkipMarker;
import kr.co.cudo.authoring.batch.step.FfmpegFrameExtractor;
import kr.co.cudo.authoring.batch.step.Sam2SegmentStep;
import kr.co.cudo.authoring.batch.step.TrackInterpolationStep;
import kr.co.cudo.authoring.batch.step.VlmTimeseriesStep;
import kr.co.cudo.authoring.batch.step.YoloAutolabelStep;
import kr.co.cudo.authoring.marking.entity.LsMarking;
import kr.co.cudo.authoring.marking.repository.LsMarkingRepository;
import kr.co.cudo.authoring.sysconfig.ConfigKeys;
import kr.co.cudo.authoring.sysconfig.service.SystemConfigService;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 전체 건너뛰기 설정이 켜져 있으면 배치가 시계열 위탁 단계에 <b>진입하기 직전</b> 자동으로 표식을
 * 세우고, 기존 스킵 게이트가 그 묶음을 건너뛴다. [@design ADR-050] [@design SEQ-001]
 *
 * <h3>고정하는 것</h3>
 * <ul>
 *   <li><b>외부 벤더 호출이 0회</b>다 — 위탁했다가 실패시키는 것이 아니라 아예 보내지 않는다.</li>
 *   <li>파이프라인은 멈추지 않는다 — 프레임 추출·오토라벨은 그대로 돌고 완주한다.</li>
 *   <li>스위치가 꺼져 있으면 <b>종전 동작 그대로</b>다.</li>
 * </ul>
 *
 * <p>표식 기록기와 게이트는 실제 {@link VlmDefaultSkipMarker} 를 태우고 상태 저장소만 대역으로
 * 둔다 — 두 축을 각각 mock 으로 두면 "표식을 세웠는데 게이트가 안 읽는" 배선 결함을 놓친다.
 */
class BatchOrchestratorDefaultVlmSkipTest {

    private VlmTimeseriesStep vlmTimeseriesStep;
    private FfmpegFrameExtractor frameExtractor;
    private YoloAutolabelStep yoloStep;
    private Sam2SegmentStep sam2Step;
    private TrackInterpolationStep trackInterpolationStep;
    private BatchStatusService statusService;
    private SystemConfigService systemConfigService;
    private VideoRepository videoRepository;
    private BatchOrchestrator orchestrator;

    /** 대역 상태 저장소 — 기록된 표식이 곧바로 게이트 판정에 반영되는지까지 확인한다. */
    private final Set<BatchStageBundle> markedBundles = EnumSet.noneOf(BatchStageBundle.class);

    @BeforeEach
    void setUp() {
        vlmTimeseriesStep = mock(VlmTimeseriesStep.class);
        frameExtractor = mock(FfmpegFrameExtractor.class);
        yoloStep = mock(YoloAutolabelStep.class);
        sam2Step = mock(Sam2SegmentStep.class);
        trackInterpolationStep = mock(TrackInterpolationStep.class);
        statusService = mock(BatchStatusService.class);
        systemConfigService = mock(SystemConfigService.class);
        videoRepository = mock(VideoRepository.class);
        LsMarkingRepository markingRepository = mock(LsMarkingRepository.class);
        BatchRetryQueue retryQueue =
                new kr.co.cudo.authoring.batch.retry.InMemoryBatchRetryQueueDouble(3, 60);
        BatchTransitionService transitionService = mock(BatchTransitionService.class);

        wireStatusServiceDouble();
        switchValue(null);
        reasonValue(null);

        BatchPipeline pipeline = PipelineTestSupport.pipeline(
                markingRepository, vlmTimeseriesStep, frameExtractor,
                yoloStep, sam2Step, trackInterpolationStep);
        orchestrator = new BatchOrchestrator(
                pipeline, statusService, transitionService, retryQueue, videoRepository,
                new VlmDefaultSkipMarker(statusService, systemConfigService));

        when(markingRepository.findByRawSnOrderByRegDtDescMarkingSnDesc(any()))
                .thenReturn(List.of(newMarking()));
        when(frameExtractor.extractByMarks(any(LsDataRaw.class), any()))
                .thenReturn(List.of(mock(LsDataSrc.class)));
    }

    /** 표식 기록 → 게이트 판정이 같은 저장소를 보도록 묶는다. */
    private void wireStatusServiceDouble() {
        doAnswer(inv -> {
            markedBundles.add(inv.getArgument(1));
            return null;
        }).when(statusService).recordManualStageSkipInNewTx(anyLong(), any(), anyString(), anyString());

        when(statusService.latestManualSkipMarker(anyLong(), any())).thenAnswer(inv -> {
            BatchStageBundle bundle = inv.getArgument(1);
            if (!markedBundles.contains(bundle)) {
                return Optional.empty();
            }
            LsBatchProcLog row = mock(LsBatchProcLog.class);
            when(row.getErrorCd()).thenReturn(ManualStageSkip.ERR_CD_SKIPPED);
            return Optional.of(row);
        });

        when(statusService.isStageManuallySkipped(anyLong(), any())).thenAnswer(inv -> {
            BatchStage stage = inv.getArgument(1);
            return BatchStageBundle.containing(stage).map(markedBundles::contains).orElse(false);
        });
    }

    private void switchValue(String value) {
        when(systemConfigService.findString(ConfigKeys.BATCH_VLM_SKIP_BY_DEFAULT))
                .thenReturn(Optional.ofNullable(value));
    }

    private void reasonValue(String value) {
        when(systemConfigService.findString(ConfigKeys.BATCH_VLM_SKIP_BY_DEFAULT_REASON))
                .thenReturn(Optional.ofNullable(value));
    }

    private void enableSkipByDefault() {
        switchValue("true");
        reasonValue("외부 시계열 벤더 미연동 구간");
    }

    private LsDataRaw newRaw(Long rawSn) {
        LsDataRaw raw = LsDataRaw.createFromIngest(
                "clip-" + rawSn, "cctv-1", "EVT", "GOV",
                LsDataRaw.PRVC_TYPE_PRVC, "raw/path.mp4", null, 60);
        setField(raw, "rawSn", rawSn);
        when(videoRepository.findById(rawSn)).thenReturn(Optional.of(raw));
        return raw;
    }

    private LsMarking newMarking() {
        return LsMarking.createAuto(1L, 5, "[{\"frameIndex\":0,\"timestamp\":\"00:00\"}]", "1");
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
    @DisplayName("★전체_건너뛰기가_켜져_있으면_외부_시계열_위탁이_한_번도_호출되지_않는다")
    void noVendorCallWhenSkipByDefaultIsOn() {
        // given
        newRaw(301L);
        enableSkipByDefault();

        // when
        orchestrator.process(301L);

        // then — «실패로 기록됐다»가 아니라 «부르지 않았다»여야 한다.
        verify(vlmTimeseriesStep, never()).run(anyLong());
        verify(vlmTimeseriesStep, never()).runWithMarking(anyLong(), any());
        verify(statusService).recordManualStageSkipInNewTx(
                eq(301L), eq(BatchStageBundle.VLM), anyString(), anyString());
    }

    @Test
    @DisplayName("★전체_건너뛰기가_켜져_있어도_프레임추출과_오토라벨은_그대로_진행되어_완주한다")
    void pipelineStillCompletesWhenSkipByDefaultIsOn() {
        // given — 이 표식은 파이프라인 진행을 제어하는 장치가 아니다.
        newRaw(302L);
        enableSkipByDefault();

        // when
        BatchStage result = orchestrator.process(302L);

        // then
        assertThat(result).isEqualTo(BatchStage.COMPLETED);
        verify(frameExtractor).extractByMarks(any(LsDataRaw.class), any());
        verify(yoloStep).run(302L);
        verify(sam2Step).run(eq(302L), any());
        verify(trackInterpolationStep).run(302L);
    }

    @Test
    @DisplayName("★전체_건너뛰기가_켜져도_오토라벨_묶음에는_표식을_세우지_않는다")
    void autolabelBundleIsNeverMarked() {
        // given
        newRaw(303L);
        enableSkipByDefault();

        // when
        orchestrator.process(303L);

        // then
        verify(statusService, never()).recordManualStageSkipInNewTx(
                anyLong(), eq(BatchStageBundle.AUTOLABEL), anyString(), anyString());
    }

    @Test
    @DisplayName("전체_건너뛰기가_꺼져_있으면_종전대로_위탁한다_회귀")
    void keepsExistingBehaviourWhenSwitchIsOff() {
        // given
        newRaw(304L);
        switchValue("false");
        reasonValue("사유");

        // when
        BatchStage result = orchestrator.process(304L);

        // then
        assertThat(result).isEqualTo(BatchStage.COMPLETED);
        verify(vlmTimeseriesStep).runWithMarking(eq(304L), any());
        verify(statusService, never()).recordManualStageSkipInNewTx(anyLong(), any(), anyString(), anyString());
    }

    @Test
    @DisplayName("설정_키가_아예_없으면_종전대로_위탁한다_기본은_꺼짐")
    void keepsExistingBehaviourWhenConfigAbsent() {
        // given — 시드하지 않는 키라 «행 없음»이 정상 상태다.
        newRaw(305L);

        // when
        orchestrator.process(305L);

        // then
        verify(vlmTimeseriesStep).runWithMarking(eq(305L), any());
    }

    @Test
    @DisplayName("★같은_영상에_배치가_두_번_진입해도_표식은_한_번만_적재된다_멱등")
    void markerIsWrittenOnlyOnce() {
        // given
        newRaw(306L);
        enableSkipByDefault();

        // when
        orchestrator.process(306L);
        orchestrator.process(306L);

        // then
        verify(statusService, times(1)).recordManualStageSkipInNewTx(
                eq(306L), eq(BatchStageBundle.VLM), anyString(), anyString());
        verify(vlmTimeseriesStep, never()).runWithMarking(anyLong(), any());
    }

    @Test
    @DisplayName("★사람이_해제한_영상은_전체_건너뛰기가_켜져_있어도_다시_건너뛰지_않는다")
    void humanClearedVideoIsNotReSkipped() {
        // given — 사람이 건너뛰기를 해제해 되살린 영상(마지막 표식 행이 «해제»).
        newRaw(307L);
        enableSkipByDefault();
        when(statusService.latestManualSkipMarker(eq(307L), eq(BatchStageBundle.VLM)))
                .thenAnswer(inv -> {
                    LsBatchProcLog row = mock(LsBatchProcLog.class);
                    when(row.getErrorCd()).thenReturn(ManualStageSkip.ERR_CD_CLEARED);
                    return Optional.of(row);
                });

        // when
        orchestrator.process(307L);

        // then — 덮으면 사람이 되살린 영상이 조용히 다시 건너뛰어진다.
        verify(statusService, never()).recordManualStageSkipInNewTx(anyLong(), any(), anyString(), anyString());
        verify(vlmTimeseriesStep).runWithMarking(eq(307L), any());
    }
}
