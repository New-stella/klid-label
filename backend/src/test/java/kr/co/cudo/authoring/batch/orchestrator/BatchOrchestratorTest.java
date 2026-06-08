package kr.co.cudo.authoring.batch.orchestrator;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.pipeline.BatchContext;
import kr.co.cudo.authoring.batch.pipeline.BatchPipeline;
import kr.co.cudo.authoring.batch.pipeline.MarkingLoadStep;
import kr.co.cudo.authoring.batch.retry.BatchRetryQueue;
import kr.co.cudo.authoring.batch.status.BatchStatusService;
import kr.co.cudo.authoring.batch.status.BatchTransitionService;
import kr.co.cudo.authoring.batch.step.FfmpegFrameExtractor;
import kr.co.cudo.authoring.batch.step.Sam2SegmentStep;
import kr.co.cudo.authoring.batch.step.TrackInterpolationStep;
import kr.co.cudo.authoring.batch.step.VlmTimeseriesStep;
import kr.co.cudo.authoring.batch.step.YoloAutolabelStep;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.marking.entity.LsMarking;
import kr.co.cudo.authoring.marking.repository.LsMarkingRepository;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BatchOrchestrator V2.1 단위 테스트 (선언적 파이프라인 리팩토링 반영).
 *
 * <p>리팩토링 후 orchestrator 는 개별 step 빈 대신 {@link BatchPipeline} 1개를 주입받아
 * 순서대로 실행한다. 본 테스트는 실제 step 구현({@link MarkingLoadStep}) + 나머지 typed step mock 을
 * {@link BatchPipeline} 으로 구성해 주입한다. mock step 의 {@code execute(ctx)} 는 기존 orchestrator
 * 가 수행하던 책임(typed 메서드 호출 + ctx 읽기/쓰기)을 그대로 위임하도록 어댑터로 연결하여,
 * 기존 검증 의도(순서, 비식별 미호출, 마킹 분기, 상태전이, 실패 처리, 힌트 전달)를 모두 보존한다.
 *
 * <p>Phase 1 정책 파이프라인 순서 (마킹 필수):
 *   MARKING(로드+marks 파싱) → VLM(영상 단위 메타) → FRAME_EXTRACT(extractByMarks)
 *   → YOLO → SAM2 → INTERPOLATE → COMPLETED
 *
 * <p>비식별(DEIDENTIFY) 단계는 post-marking 시퀀스에서 제거됐다 (적재 직후 선두 단계로 이동 — Phase 2).
 */
class BatchOrchestratorTest {

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
        retryQueue = new BatchRetryQueue(3, 60);
        videoRepository = mock(VideoRepository.class);
        markingRepository = mock(LsMarkingRepository.class);
        transitionService = mock(BatchTransitionService.class);

        BatchPipeline pipeline = PipelineTestSupport.pipeline(
                markingRepository, vlmTimeseriesStep, frameExtractor,
                yoloStep, sam2Step, trackInterpolationStep);

        orchestrator = new BatchOrchestrator(
                pipeline, statusService, transitionService, retryQueue, videoRepository);

        // V2.0: 마킹 필수 — 기본 마킹 데이터 제공 (orchestrator 통과 보장)
        when(markingRepository.findByRawSnOrderByRegDtDesc(any()))
                .thenReturn(List.of(newMarking()));

        // 기본: extractByMarks(raw, marks) 가 1 프레임 반환
        when(frameExtractor.extractByMarks(any(LsDataRaw.class), any()))
                .thenReturn(List.of(mock(LsDataSrc.class)));
    }

    private LsDataRaw newRaw(Long rawSn, String prvcTypeCd) {
        LsDataRaw raw = LsDataRaw.createFromIngest(
                "clip-" + rawSn, "cctv-1", "EVT", "GOV",
                prvcTypeCd, "raw/path.mp4", null, 60);
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
    @DisplayName("배치_파이프라인이_비식별_단계를_호출하지_않는다")
    void pipelineDoesNotInvokeDeidentifyStage() {
        newRaw(101L, LsDataRaw.PRVC_TYPE_ANONY);

        BatchStage result = orchestrator.process(101L);

        assertThat(result).isEqualTo(BatchStage.COMPLETED);
        // 비식별 단계는 post-marking 시퀀스에서 제거됨 — markStage(DEIDENTIFY) 미호출.
        verify(statusService, never()).markStage(eq(101L), eq(BatchStage.DEIDENTIFY));
        verify(vlmTimeseriesStep).runWithMarking(eq(101L), any());
        verify(frameExtractor).extractByMarks(any(LsDataRaw.class), any());
        verify(yoloStep).run(101L);
        verify(sam2Step).run(eq(101L), any());
        verify(trackInterpolationStep).run(101L);
    }

    @Test
    @DisplayName("배치_순서가_VLM_프레임추출_YOLO_SAM2_보간이다")
    void pipelineOrderVlmFrameYoloSam2Interpolate() {
        newRaw(102L, LsDataRaw.PRVC_TYPE_PRVC);

        BatchStage result = orchestrator.process(102L);

        assertThat(result).isEqualTo(BatchStage.COMPLETED);
        InOrder order = inOrder(vlmTimeseriesStep, frameExtractor, yoloStep, sam2Step, trackInterpolationStep);
        order.verify(vlmTimeseriesStep).runWithMarking(eq(102L), any());
        order.verify(frameExtractor).extractByMarks(any(LsDataRaw.class), any());
        order.verify(yoloStep).run(102L);
        order.verify(sam2Step).run(eq(102L), any());
        order.verify(trackInterpolationStep).run(102L);
    }

    @Test
    @DisplayName("ANONY_영상도_VLM_FRAME_순서로_완료")
    void anonyPipelineOrder() {
        newRaw(131L, LsDataRaw.PRVC_TYPE_ANONY);

        BatchStage result = orchestrator.process(131L);

        assertThat(result).isEqualTo(BatchStage.COMPLETED);
        InOrder order = inOrder(vlmTimeseriesStep, frameExtractor, yoloStep, sam2Step, trackInterpolationStep);
        order.verify(vlmTimeseriesStep).runWithMarking(eq(131L), any());
        order.verify(frameExtractor).extractByMarks(any(LsDataRaw.class), any());
        order.verify(yoloStep).run(131L);
        order.verify(sam2Step).run(eq(131L), any());
        order.verify(trackInterpolationStep).run(131L);
    }

    @Test
    @DisplayName("BatchOrchestrator_VLM_META_실패_시_이후_모든_단계_호출_안_함_+_FAILED_마킹")
    void vlmMetaFailureFailsImmediately() {
        newRaw(132L, LsDataRaw.PRVC_TYPE_PRVC);
        doThrow(new RuntimeException("vlm-meta 5xx")).when(vlmTimeseriesStep).runWithMarking(eq(132L), any());

        BatchStage result = orchestrator.process(132L);

        assertThat(result).isEqualTo(BatchStage.FAILED);
        verify(frameExtractor, never()).extractByMarks(any(), any());
        verify(yoloStep, never()).run(any());
        verify(sam2Step, never()).run(any(), any());
        verify(trackInterpolationStep, never()).run(any());
        verify(statusService).markFailed(eq(132L), any(RuntimeException.class));
        assertThat(retryQueue.retryCount(132L)).isEqualTo(1);
    }

    @Test
    @DisplayName("V2_마킹_없으면_FAILED_INVALID_INPUT")
    void noMarkingResultsInFailed() {
        newRaw(133L, LsDataRaw.PRVC_TYPE_ANONY);
        when(markingRepository.findByRawSnOrderByRegDtDesc(133L))
                .thenReturn(java.util.Collections.emptyList());

        BatchStage result = orchestrator.process(133L);

        assertThat(result).isEqualTo(BatchStage.FAILED);
        verify(frameExtractor, never()).extractByMarks(any(), any());
        verify(yoloStep, never()).run(any());
    }

    @Test
    @DisplayName("YOLO_단계_정상_처리시_COMPLETED_상태_전이")
    void yoloSuccessReachesCompleted() {
        newRaw(105L, LsDataRaw.PRVC_TYPE_ANONY);
        when(yoloStep.run(eq(105L)))
                .thenReturn(List.of(
                        new kr.co.cudo.authoring.batch.step.BbHint(1L, "person", List.of(1.0, 2.0, 3.0, 4.0), 0.92, null)));

        BatchStage result = orchestrator.process(105L);

        assertThat(result).isEqualTo(BatchStage.COMPLETED);
        verify(statusService).markCompleted(105L);
    }

    @Test
    @DisplayName("INTERPOLATE_단계_실패시_FAILED_고정_+_이전_단계는_커밋")
    void interpolateFailureKeepsPriorStagesAndMarksFailed() {
        newRaw(106L, LsDataRaw.PRVC_TYPE_ANONY);
        doThrow(new RuntimeException("interpolate down")).when(trackInterpolationStep).run(any());

        BatchStage result = orchestrator.process(106L);

        assertThat(result).isEqualTo(BatchStage.FAILED);
        verify(yoloStep).run(106L);
        verify(sam2Step).run(eq(106L), any());
        verify(trackInterpolationStep).run(106L);
        verify(statusService).markFailed(eq(106L), any(RuntimeException.class));
    }

    @Test
    @DisplayName("최대_3회_재시도_후_FAILED_상태_고정_큐_재등록_거부")
    void maxRetryReachedRefusesEnqueue() {
        newRaw(107L, LsDataRaw.PRVC_TYPE_ANONY);
        doThrow(new RuntimeException("yolo down")).when(yoloStep).run(any());

        // 4회 실패 시도 — 1·2·3회는 재시도 등록 성공, 4회는 enqueueIfRetryable=false
        for (int i = 0; i < 4; i++) {
            orchestrator.process(107L);
        }

        assertThat(retryQueue.retryCount(107L)).isEqualTo(4);
        verify(statusService, times(4)).markFailed(eq(107L), any(RuntimeException.class));
    }

    @Test
    @DisplayName("rawSn_null이면_INVALID_INPUT")
    void nullRawSnRejected() {
        try {
            orchestrator.process(null);
        } catch (CustomException e) {
            assertThat(e.getErrorCode().name()).isEqualTo("INVALID_INPUT");
            return;
        }
        org.assertj.core.api.Assertions.fail("CustomException 이 발생해야 합니다.");
    }

    @Test
    @DisplayName("프레임_추출_결과_0건이면_FAILED_+_이후_단계_미호출")
    void emptyFramesMovesToFailed() {
        newRaw(108L, LsDataRaw.PRVC_TYPE_ANONY);
        when(frameExtractor.extractByMarks(any(LsDataRaw.class), any()))
                .thenReturn(List.of());

        BatchStage result = orchestrator.process(108L);

        assertThat(result).isEqualTo(BatchStage.FAILED);
        verify(vlmTimeseriesStep).runWithMarking(eq(108L), any());
        verify(yoloStep, never()).run(any());
        verify(sam2Step, never()).run(any(), any());
    }

    @Test
    @DisplayName("성공_시_재시도큐_clear_호출")
    void successClearsRetryQueue() {
        newRaw(109L, LsDataRaw.PRVC_TYPE_ANONY);
        // 한번 실패해서 retry 등록 후 재시도 성공 시나리오.
        doThrow(new RuntimeException("transient")).when(yoloStep).run(any());
        orchestrator.process(109L);
        assertThat(retryQueue.retryCount(109L)).isEqualTo(1);

        // 이번엔 정상 동작
        org.mockito.Mockito.reset(yoloStep);
        when(yoloStep.run(any())).thenReturn(List.of(
                new kr.co.cudo.authoring.batch.step.BbHint(1L, "person", List.of(1.0, 2.0, 3.0, 4.0), 0.92, null),
                new kr.co.cudo.authoring.batch.step.BbHint(2L, "car", List.of(5.0, 6.0, 7.0, 8.0), 0.81, null),
                new kr.co.cudo.authoring.batch.step.BbHint(3L, "person", List.of(9.0, 10.0, 11.0, 12.0), 0.75, null)
        ));

        BatchStage result = orchestrator.process(109L);

        assertThat(result).isEqualTo(BatchStage.COMPLETED);
        assertThat(retryQueue.retryCount(109L)).isEqualTo(0);
        verify(frameExtractor, times(2)).extractByMarks(any(LsDataRaw.class), any());
    }

    @Test
    @DisplayName("BatchOrchestrator_같은_영상_프레임은_순차_호출_보장_V2_순서")
    void yoloAndSam2InvokedSequentiallyPerVideo() {
        newRaw(120L, LsDataRaw.PRVC_TYPE_ANONY);
        List<kr.co.cudo.authoring.batch.step.BbHint> hints = List.of(
                new kr.co.cudo.authoring.batch.step.BbHint(1L, "person", List.of(1.0, 2.0, 3.0, 4.0), 0.92, 1)
        );
        when(yoloStep.run(120L)).thenReturn(hints);

        BatchStage result = orchestrator.process(120L);

        assertThat(result).isEqualTo(BatchStage.COMPLETED);
        InOrder order = inOrder(vlmTimeseriesStep, frameExtractor, yoloStep, sam2Step, trackInterpolationStep);
        order.verify(vlmTimeseriesStep).runWithMarking(eq(120L), any());
        order.verify(frameExtractor).extractByMarks(any(LsDataRaw.class), any());
        order.verify(yoloStep).run(120L);
        order.verify(sam2Step).run(eq(120L), any());
        order.verify(trackInterpolationStep).run(120L);
    }

    @Test
    @DisplayName("Phase3_trackInterpolationStep_이_sam2_다음에_호출")
    void trackInterpolationStepAfterSam2() {
        newRaw(121L, LsDataRaw.PRVC_TYPE_ANONY);

        BatchStage result = orchestrator.process(121L);

        assertThat(result).isEqualTo(BatchStage.COMPLETED);
        InOrder order = inOrder(sam2Step, trackInterpolationStep);
        order.verify(sam2Step).run(eq(121L), any());
        order.verify(trackInterpolationStep).run(121L);
    }

    @Test
    @DisplayName("Phase3_trackInterpolationStep_은_정상_플로우에서_정확히_1회_호출")
    void trackInterpolationStepInvokedOncePerProcess() {
        newRaw(122L, LsDataRaw.PRVC_TYPE_ANONY);

        BatchStage result = orchestrator.process(122L);

        assertThat(result).isEqualTo(BatchStage.COMPLETED);
        verify(trackInterpolationStep, times(1)).run(122L);
    }

    @Test
    @DisplayName("Phase3_sam2_단계_실패시_trackInterpolationStep_은_호출되지_않음")
    void trackInterpolationStepSkippedWhenSam2Fails() {
        newRaw(123L, LsDataRaw.PRVC_TYPE_ANONY);
        doThrow(new RuntimeException("sam2 down")).when(sam2Step).run(any(), any());

        BatchStage result = orchestrator.process(123L);

        assertThat(result).isEqualTo(BatchStage.FAILED);
        verify(trackInterpolationStep, never()).run(any());
    }

    @Test
    @DisplayName("BatchOrchestrator_YoloStep_결과를_Sam2Step_에_정확히_전달")
    void yoloHintsPassedToSam2() {
        newRaw(110L, LsDataRaw.PRVC_TYPE_ANONY);
        List<kr.co.cudo.authoring.batch.step.BbHint> hints = List.of(
                new kr.co.cudo.authoring.batch.step.BbHint(11L, "person", List.of(1.0, 2.0, 3.0, 4.0), 0.92, null),
                new kr.co.cudo.authoring.batch.step.BbHint(12L, "car", List.of(5.0, 6.0, 7.0, 8.0), 0.81, null)
        );
        when(yoloStep.run(110L)).thenReturn(hints);

        BatchStage result = orchestrator.process(110L);

        assertThat(result).isEqualTo(BatchStage.COMPLETED);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<kr.co.cudo.authoring.batch.step.BbHint>> captor =
                ArgumentCaptor.forClass(List.class);
        verify(sam2Step).run(eq(110L), captor.capture());
        assertThat(captor.getValue()).containsExactlyElementsOf(hints);
    }
}
