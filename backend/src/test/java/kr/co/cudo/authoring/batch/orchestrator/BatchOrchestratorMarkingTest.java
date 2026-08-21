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
import kr.co.cudo.authoring.marking.dto.MarkItem;
import kr.co.cudo.authoring.marking.entity.LsMarking;
import kr.co.cudo.authoring.marking.repository.LsMarkingRepository;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import kr.co.cudo.authoring.batch.status.VlmDefaultSkipMarker;

/**
 * Phase 3: BatchOrchestrator MARKING 단계 삽입 테스트.
 *
 * <p>V2.0: 마킹 필수 — 마킹이 없으면 INVALID_INPUT 으로 FAILED 처리.
 */
class BatchOrchestratorMarkingTest {

    private VlmTimeseriesStep vlmTimeseriesStep;
    private FfmpegFrameExtractor frameExtractor;
    private YoloAutolabelStep yoloStep;
    private Sam2SegmentStep sam2Step;
    private TrackInterpolationStep trackInterpolationStep;
    private BatchStatusService statusService;
    private BatchRetryQueue retryQueue;
    private VideoRepository videoRepository;
    private LsMarkingRepository markingRepository;
    private BatchTransitionService transitionService;
    private BatchOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        vlmTimeseriesStep = mock(VlmTimeseriesStep.class);
        frameExtractor = mock(FfmpegFrameExtractor.class);
        yoloStep = mock(YoloAutolabelStep.class);
        sam2Step = mock(Sam2SegmentStep.class);
        trackInterpolationStep = mock(TrackInterpolationStep.class);
        statusService = mock(BatchStatusService.class);
        retryQueue = new kr.co.cudo.authoring.batch.retry.InMemoryBatchRetryQueueDouble(3, 60);
        videoRepository = mock(VideoRepository.class);
        markingRepository = mock(LsMarkingRepository.class);
        transitionService = mock(BatchTransitionService.class);

        BatchPipeline pipeline = PipelineTestSupport.pipeline(
                markingRepository, vlmTimeseriesStep, frameExtractor,
                yoloStep, sam2Step, trackInterpolationStep);

        orchestrator = new BatchOrchestrator(
                pipeline, statusService, transitionService, retryQueue, videoRepository,
                mock(VlmDefaultSkipMarker.class));

        // given: 기본 mock 설정
        when(frameExtractor.extractByMarks(any(LsDataRaw.class), any()))
                .thenReturn(List.of(mock(LsDataSrc.class)));
    }

    private LsDataRaw newRaw(Long rawSn) {
        LsDataRaw raw = LsDataRaw.createFromIngest(
                "clip-" + rawSn, "cctv-1", "EVT", "GOV",
                LsDataRaw.PRVC_TYPE_ANONY, "raw/path.mp4", null, 60);
        setField(raw, "rawSn", rawSn);
        when(videoRepository.findById(rawSn)).thenReturn(Optional.of(raw));
        return raw;
    }

    private LsMarking newMarking(Long rawSn) {
        return LsMarking.createAuto(rawSn, "fire", 5,
                "raw/path.mp4", "[{\"frameIndex\":0,\"timestamp\":0.0}]", 1L);
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
    @DisplayName("마킹_있는_영상_MARKING_단계_실행_후_VLM_호출")
    void markingExists_markingStageBeforeVlm_runWithMarkingInvoked() {
        // given
        newRaw(501L);
        LsMarking marking = newMarking(501L);
        when(markingRepository.findByRawSnOrderByRegDtDescMarkingSnDesc(501L))
                .thenReturn(List.of(marking));

        // when
        BatchStage result = orchestrator.process(501L);

        // then
        assertThat(result).isEqualTo(BatchStage.COMPLETED);

        // MARKING 단계가 VLM 이전에 마킹되는지 확인
        InOrder order = inOrder(statusService, vlmTimeseriesStep);
        order.verify(statusService).markStage(501L, BatchStage.MARKING);
        order.verify(statusService).markStage(501L, BatchStage.VLM);
        order.verify(vlmTimeseriesStep).runWithMarking(eq(501L), eq(marking));
    }

    @Test
    @DisplayName("V2_마킹_없는_영상_FAILED_INVALID_INPUT")
    void noMarking_failsWithInvalidInput() {
        // given
        newRaw(502L);
        when(markingRepository.findByRawSnOrderByRegDtDescMarkingSnDesc(502L))
                .thenReturn(Collections.emptyList());

        // when
        BatchStage result = orchestrator.process(502L);

        // then
        assertThat(result).isEqualTo(BatchStage.FAILED);
        verify(frameExtractor, never()).extractByMarks(any(), any());
        verify(yoloStep, never()).run(any());
        verify(statusService).markFailed(eq(502L), any(RuntimeException.class));
    }

    @Test
    @DisplayName("마킹_데이터가_VLM_요청에_포함됨_최신_마킹_사용")
    void latestMarkingPassedToVlmStep() {
        // given
        newRaw(503L);
        LsMarking latest = newMarking(503L);
        LsMarking older = newMarking(503L);
        // findByRawSnOrderByRegDtDescMarkingSnDesc 결과는 최신 먼저
        when(markingRepository.findByRawSnOrderByRegDtDescMarkingSnDesc(503L))
                .thenReturn(List.of(latest, older));

        // when
        BatchStage result = orchestrator.process(503L);

        // then
        assertThat(result).isEqualTo(BatchStage.COMPLETED);
        // 최신 마킹(리스트 첫 번째)이 전달되어야 함
        verify(vlmTimeseriesStep).runWithMarking(eq(503L), eq(latest));
        verify(vlmTimeseriesStep, never()).run(503L);
    }

    // --- Phase 4 V2.0: 마킹 기반 프레임 추출 ---

    @Test
    @DisplayName("V2_마킹_있을때_extractByMarks_호출")
    void markingExists_extractByMarksInvoked() {
        // given
        newRaw(504L);
        LsMarking marking = newMarking(504L);
        when(markingRepository.findByRawSnOrderByRegDtDescMarkingSnDesc(504L))
                .thenReturn(List.of(marking));

        // when
        BatchStage result = orchestrator.process(504L);

        // then
        assertThat(result).isEqualTo(BatchStage.COMPLETED);
        verify(frameExtractor).extractByMarks(any(LsDataRaw.class), any());
    }

    @Test
    @DisplayName("V2_마킹_marks_JSON이_extractByMarks에_MarkItem_리스트로_전달")
    void markingMarksJsonParsedToMarkItems() {
        // given
        newRaw(506L);
        LsMarking marking = LsMarking.createAuto(506L, "fire", 5,
                "raw/path.mp4",
                "[{\"frameIndex\":0,\"timestamp\":\"00:00\"},{\"frameIndex\":150,\"timestamp\":\"00:05\"}]",
                1L);
        when(markingRepository.findByRawSnOrderByRegDtDescMarkingSnDesc(506L))
                .thenReturn(List.of(marking));

        // when
        BatchStage result = orchestrator.process(506L);

        // then
        assertThat(result).isEqualTo(BatchStage.COMPLETED);
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<java.util.List<MarkItem>> captor =
                org.mockito.ArgumentCaptor.forClass(java.util.List.class);
        verify(frameExtractor).extractByMarks(any(LsDataRaw.class), captor.capture());
        java.util.List<MarkItem> marks = captor.getValue();
        assertThat(marks).hasSize(2);
        assertThat(marks.get(0).frameIndex()).isEqualTo(0);
        assertThat(marks.get(1).frameIndex()).isEqualTo(150);
    }
}
