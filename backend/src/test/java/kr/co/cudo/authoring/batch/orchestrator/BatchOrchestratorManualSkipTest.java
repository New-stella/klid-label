package kr.co.cudo.authoring.batch.orchestrator;

import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.pipeline.BatchPipeline;
import kr.co.cudo.authoring.batch.retry.BatchRetryQueue;
import kr.co.cudo.authoring.batch.status.BatchStatusService;
import kr.co.cudo.authoring.batch.status.BatchTransitionService;
import kr.co.cudo.authoring.batch.step.FfmpegFrameExtractor;
import kr.co.cudo.authoring.batch.step.Sam2SegmentStep;
import kr.co.cudo.authoring.batch.step.TrackInterpolationStep;
import kr.co.cudo.authoring.batch.step.VlmTimeseriesStep;
import kr.co.cudo.authoring.batch.step.YoloAutolabelStep;
import kr.co.cudo.authoring.marking.entity.LsMarking;
import kr.co.cudo.authoring.marking.repository.LsMarkingRepository;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import kr.co.cudo.authoring.batch.status.VlmDefaultSkipMarker;

/**
 * 수동 스킵 표식이 선 단계는 <b>재기동에서 실행되지 않고 통과</b>한다. [@design API-198]
 *
 * <p>스킵된 단계는 {@code markStage} 도 타지 않는다 — 표식은 진행 축(단계 표시)을 건드리지 않는다.
 * 나머지 단계는 정상 실행되어 파이프라인이 {@code COMPLETED} 로 완주해야 한다(스킵의 목적 자체가
 * "그 단계 때문에 영상 전체가 FAILED 로 고착되는 것"을 푸는 것이다).
 */
class BatchOrchestratorManualSkipTest {

    private VlmTimeseriesStep vlmTimeseriesStep;
    private FfmpegFrameExtractor frameExtractor;
    private YoloAutolabelStep yoloStep;
    private Sam2SegmentStep sam2Step;
    private TrackInterpolationStep trackInterpolationStep;
    private BatchStatusService statusService;
    private VideoRepository videoRepository;
    private LsMarkingRepository markingRepository;
    private BatchOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        vlmTimeseriesStep = mock(VlmTimeseriesStep.class);
        frameExtractor = mock(FfmpegFrameExtractor.class);
        yoloStep = mock(YoloAutolabelStep.class);
        sam2Step = mock(Sam2SegmentStep.class);
        trackInterpolationStep = mock(TrackInterpolationStep.class);
        statusService = mock(BatchStatusService.class);
        BatchRetryQueue retryQueue = new kr.co.cudo.authoring.batch.retry.InMemoryBatchRetryQueueDouble(3, 60);
        videoRepository = mock(VideoRepository.class);
        markingRepository = mock(LsMarkingRepository.class);
        BatchTransitionService transitionService = mock(BatchTransitionService.class);

        BatchPipeline pipeline = PipelineTestSupport.pipeline(
                markingRepository, vlmTimeseriesStep, frameExtractor,
                yoloStep, sam2Step, trackInterpolationStep);
        orchestrator = new BatchOrchestrator(
                pipeline, statusService, transitionService, retryQueue, videoRepository,
                mock(VlmDefaultSkipMarker.class));

        when(markingRepository.findByRawSnOrderByRegDtDescMarkingSnDesc(any()))
                .thenReturn(List.of(newMarking()));
        when(frameExtractor.extractByMarks(any(LsDataRaw.class), any()))
                .thenReturn(List.of(mock(LsDataSrc.class)));
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
    @DisplayName("★수동_스킵된_VLM_단계는_실행되지_않고_나머지는_완주한다")
    void manuallySkippedVlmIsNotExecuted() {
        // given — VLM 만 수동 스킵 표식이 서 있다.
        newRaw(201L);
        when(statusService.isStageManuallySkipped(anyLong(), any())).thenReturn(false);
        when(statusService.isStageManuallySkipped(201L, BatchStage.VLM)).thenReturn(true);

        // when
        BatchStage result = orchestrator.process(201L);

        // then — VLM 은 호출되지 않고, 나머지 단계는 정상 실행되어 완주한다.
        assertThat(result).isEqualTo(BatchStage.COMPLETED);
        verify(vlmTimeseriesStep, never()).runWithMarking(anyLong(), any());
        verify(vlmTimeseriesStep, never()).run(anyLong());
        verify(frameExtractor).extractByMarks(any(LsDataRaw.class), any());
        verify(yoloStep).run(201L);
        verify(sam2Step).run(eq(201L), any());
        verify(trackInterpolationStep).run(201L);
    }

    @Test
    @DisplayName("★건너뛴_단계는_markStage도_타지_않는다_진행축_비오염")
    void skippedStageIsNotMarked() {
        // given — 시계열 묶음이 건너뛰어진 상태(게이트는 단계 단위로 물어보되 판정은 묶음 축이다).
        newRaw(202L);
        when(statusService.isStageManuallySkipped(anyLong(), any())).thenReturn(false);
        when(statusService.isStageManuallySkipped(202L, BatchStage.VLM)).thenReturn(true);

        // when
        orchestrator.process(202L);

        // then — VLM 단계로의 진행 마킹이 없어야 한다(표식이 화면 단계 표시를 바꾸지 않는다).
        verify(statusService, never()).markStage(eq(202L), eq(BatchStage.VLM));
        verify(statusService).markStage(eq(202L), eq(BatchStage.SAM2));
        verify(vlmTimeseriesStep, never()).run(anyLong());
    }

    @Test
    @DisplayName("★★오토라벨_묶음을_건너뛰면_보간까지_함께_건너뛰고_파이프라인은_완주한다")
    void skippingAutolabelBundleAlsoSkipsInterpolation() {
        // given — 묶음 스킵의 실제 형상: 게이트가 구성 단계 셋 모두에서 참이 된다.
        //   ★보간이 함께 꺼지는 것이 이 반전의 핵심이다 — 구 동작(YOLO/SAM2 만 스킵)에서는 보간이
        //   그대로 돌아 오토라벨 산출물 없이 보간만 재계산되는 어긋남이 남았다.
        newRaw(203L);
        when(statusService.isStageManuallySkipped(anyLong(), any())).thenReturn(false);
        when(statusService.isStageManuallySkipped(203L, BatchStage.YOLO)).thenReturn(true);
        when(statusService.isStageManuallySkipped(203L, BatchStage.SAM2)).thenReturn(true);
        when(statusService.isStageManuallySkipped(203L, BatchStage.INTERPOLATE)).thenReturn(true);

        // when
        BatchStage result = orchestrator.process(203L);

        // then
        assertThat(result).isEqualTo(BatchStage.COMPLETED);
        verify(yoloStep, never()).run(anyLong());
        verify(sam2Step, never()).run(anyLong(), any());
        verify(trackInterpolationStep, never()).run(anyLong());
        // 전제 단계는 그대로 돈다(묶음 밖이라 건너뛸 수 없다).
        verify(frameExtractor).extractByMarks(any(LsDataRaw.class), any());
    }

    @Test
    @DisplayName("표식이_없으면_모든_단계가_기존대로_실행된다_동작보존")
    void noMarkerKeepsExistingBehaviour() {
        // given — 이 기능 도입 전 동작이 그대로 유지되는지 고정한다.
        newRaw(204L);
        when(statusService.isStageManuallySkipped(anyLong(), any())).thenReturn(false);

        // when
        BatchStage result = orchestrator.process(204L);

        // then
        assertThat(result).isEqualTo(BatchStage.COMPLETED);
        verify(vlmTimeseriesStep).runWithMarking(eq(204L), any());
        verify(yoloStep).run(204L);
        verify(sam2Step).run(eq(204L), any());
    }
}
