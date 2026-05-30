package kr.co.cudo.authoring.batch.orchestrator;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
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

import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BatchOrchestrator LsRawDataStatus 상태 전이 테스트.
 *
 * <p>process() 시작 시 PROCESSING, 완료 시 COMPLETED, 실패 시 FAILED 전이를 검증한다.
 */
class BatchOrchestratorStatusTransitionTest {

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
    private LsRawDataStatusRepository rawDataStatusRepository;
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
        rawDataStatusRepository = mock(LsRawDataStatusRepository.class);

        orchestrator = new BatchOrchestrator(
                vlmTimeseriesStep, frameExtractor, deidentifyStep, yoloStep, sam2Step,
                trackInterpolationStep, statusService, retryQueue, videoRepository,
                markingRepository, new ObjectMapper(), rawDataStatusRepository);

        // V2.0: 마킹 필수 -- 기본 마킹 데이터 제공
        when(markingRepository.findByRawSnOrderByRegDtDesc(any()))
                .thenReturn(List.of(newMarking()));

        // 기본: extractByMarks(raw, deidVideoPath, marks) 가 1 프레임 반환
        when(frameExtractor.extractByMarks(any(LsDataRaw.class), nullable(String.class), any()))
                .thenReturn(List.of(mock(LsDataSrc.class)));
        // 기본: deidentifyStep.run 는 null 반환
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
    @DisplayName("process_시작시_LsRawDataStatus_PROCESSING_전이")
    void process_시작시_LsRawDataStatus_PROCESSING_전이() {
        // given
        newRaw(601L);
        LsRawDataStatus stts = mock(LsRawDataStatus.class);
        when(rawDataStatusRepository.findById(601L)).thenReturn(Optional.of(stts));

        // when
        orchestrator.process(601L);

        // then
        verify(stts).transitionTo(LsRawDataStatus.STTS_PROCESSING);
    }

    @Test
    @DisplayName("process_완료시_LsRawDataStatus_COMPLETED_전이")
    void process_완료시_LsRawDataStatus_COMPLETED_전이() {
        // given
        newRaw(602L);
        LsRawDataStatus stts = mock(LsRawDataStatus.class);
        when(rawDataStatusRepository.findById(602L)).thenReturn(Optional.of(stts));

        // when
        BatchStage result = orchestrator.process(602L);

        // then
        assertThat(result).isEqualTo(BatchStage.COMPLETED);
        verify(stts).transitionTo(LsRawDataStatus.STTS_COMPLETED);
    }

    @Test
    @DisplayName("process_실패시_LsRawDataStatus_FAILED_전이")
    void process_실패시_LsRawDataStatus_FAILED_전이() {
        // given
        newRaw(603L);
        LsRawDataStatus stts = mock(LsRawDataStatus.class);
        when(rawDataStatusRepository.findById(603L)).thenReturn(Optional.of(stts));
        doThrow(new RuntimeException("yolo failure")).when(yoloStep).run(any());

        // when
        BatchStage result = orchestrator.process(603L);

        // then
        assertThat(result).isEqualTo(BatchStage.FAILED);
        verify(stts).transitionTo(LsRawDataStatus.STTS_FAILED);
    }
}
