package kr.co.cudo.authoring.batch.orchestrator;

import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.retry.BatchRetryQueue;
import kr.co.cudo.authoring.batch.status.BatchStatusService;
import kr.co.cudo.authoring.batch.step.DeidentifyStep;
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
import org.mockito.InOrder;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 3: BatchOrchestrator MARKING 단계 삽입 테스트.
 *
 * <p>마킹 데이터가 있으면 runWithMarking(), 없으면 기존 run() 호출을 검증한다.
 */
class BatchOrchestratorMarkingTest {

    private VlmTimeseriesStep vlmTimeseriesStep;
    private FfmpegFrameExtractor frameExtractor;
    private DeidentifyStep deidentifyStep;
    private YoloAutolabelStep yoloStep;
    private Sam2SegmentStep sam2Step;
    private TrackInterpolationStep trackInterpolationStep;
    private BatchStatusService statusService;
    private BatchRetryQueue retryQueue;
    private VideoRepository videoRepository;
    private LsMarkingRepository markingRepository;
    private BatchOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        vlmTimeseriesStep = mock(VlmTimeseriesStep.class);
        frameExtractor = mock(FfmpegFrameExtractor.class);
        deidentifyStep = mock(DeidentifyStep.class);
        yoloStep = mock(YoloAutolabelStep.class);
        sam2Step = mock(Sam2SegmentStep.class);
        trackInterpolationStep = mock(TrackInterpolationStep.class);
        statusService = mock(BatchStatusService.class);
        retryQueue = new BatchRetryQueue(3, 60);
        videoRepository = mock(VideoRepository.class);
        markingRepository = mock(LsMarkingRepository.class);

        orchestrator = new BatchOrchestrator(
                vlmTimeseriesStep, frameExtractor, deidentifyStep, yoloStep, sam2Step,
                trackInterpolationStep, statusService, retryQueue, videoRepository,
                markingRepository);

        // given: 기본 mock 설정
        when(frameExtractor.extractBoth(any(LsDataRaw.class), nullable(String.class)))
                .thenReturn(List.of(mock(LsDataSrc.class)));
        when(deidentifyStep.run(any(LsDataRaw.class))).thenReturn(null);
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
        when(markingRepository.findByRawSnOrderByCreatedAtDesc(501L))
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
    @DisplayName("마킹_없는_영상_MARKING_단계_후_기존_VLM_호출")
    void noMarking_fallbackToExistingRun() {
        // given
        newRaw(502L);
        when(markingRepository.findByRawSnOrderByCreatedAtDesc(502L))
                .thenReturn(Collections.emptyList());

        // when
        BatchStage result = orchestrator.process(502L);

        // then
        assertThat(result).isEqualTo(BatchStage.COMPLETED);
        verify(vlmTimeseriesStep).run(502L);
        verify(vlmTimeseriesStep, never()).runWithMarking(any(), any());
    }

    @Test
    @DisplayName("마킹_데이터가_VLM_요청에_포함됨_최신_마킹_사용")
    void latestMarkingPassedToVlmStep() {
        // given
        newRaw(503L);
        LsMarking latest = newMarking(503L);
        LsMarking older = newMarking(503L);
        // findByRawSnOrderByCreatedAtDesc 결과는 최신 먼저
        when(markingRepository.findByRawSnOrderByCreatedAtDesc(503L))
                .thenReturn(List.of(latest, older));

        // when
        BatchStage result = orchestrator.process(503L);

        // then
        assertThat(result).isEqualTo(BatchStage.COMPLETED);
        // 최신 마킹(리스트 첫 번째)이 전달되어야 함
        verify(vlmTimeseriesStep).runWithMarking(eq(503L), eq(latest));
        verify(vlmTimeseriesStep, never()).run(503L);
    }
}
