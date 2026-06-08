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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BatchOrchestrator LS_RAW_DATA_STATUS 상태 전이 영속 테스트 (이슈 A 회귀).
 *
 * <p>process() 는 NOT_SUPPORTED(비트랜잭션) 라 엔티티 직접 변경은 dirty checking 으로 영속되지
 * 않는다. 상태 전이는 {@link BatchTransitionService} 의 REQUIRES_NEW public 메서드로 위임되어야
 * DB 에 영속된다. 본 테스트는 orchestrator 가 transitionService 의 적절한 메서드를 호출하는지
 * 검증한다 (자체 트랜잭션에서 load + save 하는 책임은 BatchTransitionServiceTest 가 검증).
 */
class BatchOrchestratorStatusTransitionTest {

    private VlmTimeseriesStep vlmTimeseriesStep;
    private FfmpegFrameExtractor frameExtractor;
    private YoloAutolabelStep yoloStep;
    private Sam2SegmentStep sam2Step;
    private TrackInterpolationStep trackInterpolationStep;
    private BatchStatusService statusService;
    private BatchTransitionService transitionService;
    private BatchRetryQueue retryQueue;
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
        transitionService = mock(BatchTransitionService.class);
        retryQueue = new BatchRetryQueue(3, 60);
        videoRepository = mock(VideoRepository.class);
        markingRepository = mock(LsMarkingRepository.class);

        BatchPipeline pipeline = PipelineTestSupport.pipeline(
                markingRepository, vlmTimeseriesStep, frameExtractor,
                yoloStep, sam2Step, trackInterpolationStep);

        orchestrator = new BatchOrchestrator(
                pipeline, statusService, transitionService, retryQueue, videoRepository);

        // V2.0: 마킹 필수 -- 기본 마킹 데이터 제공
        when(markingRepository.findByRawSnOrderByRegDtDesc(any()))
                .thenReturn(List.of(newMarking()));

        // 기본: extractByMarks(raw, marks) 가 1 프레임 반환
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

    private LsMarking newMarking() {
        return LsMarking.createAuto(1L, "fire", 5,
                "raw/path.mp4", "[{\"frameIndex\":0,\"timestamp\":\"00:00\"}]", 1L);
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
    @DisplayName("process_시작시_transitionService_markRawDataProcessing_호출로_PROCESSING_영속")
    void process_시작시_PROCESSING_영속() {
        // given
        newRaw(601L);

        // when
        orchestrator.process(601L);

        // then — 전이가 별도 트랜잭션 빈으로 위임되어야 DB 영속됨
        verify(transitionService).markRawDataProcessing(601L);
    }

    @Test
    @DisplayName("process_완료시_transitionService_markRawDataCompleted_호출로_COMPLETED_영속")
    void process_완료시_COMPLETED_영속() {
        // given
        newRaw(602L);

        // when
        BatchStage result = orchestrator.process(602L);

        // then
        assertThat(result).isEqualTo(BatchStage.COMPLETED);
        verify(transitionService).markRawDataCompleted(602L);
        verify(transitionService, never()).markRawDataFailed(any());
    }

    @Test
    @DisplayName("process_실패시_transitionService_markRawDataFailed_호출로_FAILED_영속")
    void process_실패시_FAILED_영속() {
        // given
        newRaw(603L);
        doThrow(new RuntimeException("yolo failure")).when(yoloStep).run(any());

        // when
        BatchStage result = orchestrator.process(603L);

        // then
        assertThat(result).isEqualTo(BatchStage.FAILED);
        verify(transitionService).markRawDataFailed(603L);
        verify(transitionService, never()).markRawDataCompleted(any());
    }
}
