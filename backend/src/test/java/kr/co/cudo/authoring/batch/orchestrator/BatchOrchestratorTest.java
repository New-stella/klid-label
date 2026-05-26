package kr.co.cudo.authoring.batch.orchestrator;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.retry.BatchRetryQueue;
import kr.co.cudo.authoring.batch.status.BatchStatusService;
import kr.co.cudo.authoring.batch.step.DeidentifyStep;
import kr.co.cudo.authoring.batch.step.FfmpegFrameExtractor;
import kr.co.cudo.authoring.batch.step.Sam2SegmentStep;
import kr.co.cudo.authoring.batch.step.TrackInterpolationStep;
import kr.co.cudo.authoring.batch.step.VlmTimeseriesStep;
import kr.co.cudo.authoring.batch.step.YoloAutolabelStep;
import kr.co.cudo.authoring.common.exception.CustomException;
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
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BatchOrchestrator V2 단위 테스트.
 *
 * <p>V2 정책 파이프라인 순서:
 *   VLM(영상 단위 메타) → DEIDENTIFY(무조건) → FRAME_EXTRACT(extractBoth)
 *   → YOLO → SAM2 → INTERPOLATE → COMPLETED
 *
 * <p>Phase 3/4: 영상 잠금은 LS_AUTH_WORK_LOCK 에서 관리되며 본 orchestrator 는
 * 잠금 상태에 관여하지 않는다 (LsDataRaw.attachLockStts/releaseLock 호출 제거).
 */
class BatchOrchestratorTest {

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
                markingRepository, new ObjectMapper());

        // Phase 3: 기본 마킹 없음 (기존 테스트 호환)
        when(markingRepository.findByRawSnOrderByCreatedAtDesc(any()))
                .thenReturn(java.util.Collections.emptyList());

        // 기본: extractBoth(raw, deidVideoPath) 가 1 프레임 반환 — orchestrator 통과 보장
        when(frameExtractor.extractBoth(any(LsDataRaw.class), nullable(String.class)))
                .thenReturn(List.of(mock(LsDataSrc.class)));
        // 기본: deidentifyStep.run 는 null 반환 (비식별 결과 없음 == 원본만 추출)
        when(deidentifyStep.run(any(LsDataRaw.class))).thenReturn(null);
    }

    private LsDataRaw newRaw(Long rawSn, String prvcTypeCd) {
        LsDataRaw raw = LsDataRaw.createFromIngest(
                "clip-" + rawSn, "cctv-1", "EVT", "GOV",
                prvcTypeCd, "raw/path.mp4", null, 60);
        setField(raw, "rawSn", rawSn);
        when(videoRepository.findById(rawSn)).thenReturn(Optional.of(raw));
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
    @DisplayName("Phase2_ANONY_영상도_DeidentifyStep_무조건_호출_VLM_먼저")
    void anonyAlsoInvokesDeidentifyStep() {
        newRaw(101L, LsDataRaw.PRVC_TYPE_ANONY);

        BatchStage result = orchestrator.process(101L);

        assertThat(result).isEqualTo(BatchStage.COMPLETED);
        verify(vlmTimeseriesStep).run(101L);
        verify(deidentifyStep, times(1)).run(any(LsDataRaw.class));
        verify(frameExtractor).extractBoth(any(LsDataRaw.class), nullable(String.class));
        verify(yoloStep).run(101L);
        verify(sam2Step).run(eq(101L), any());
        verify(trackInterpolationStep).run(101L);
    }

    @Test
    @DisplayName("PRVC_영상은_DeidentifyStep_호출_VLM_먼저_그_다음_DEIDENT_그_다음_FRAME")
    void prvcInvokesDeidentifyStep() {
        newRaw(102L, LsDataRaw.PRVC_TYPE_PRVC);

        BatchStage result = orchestrator.process(102L);

        assertThat(result).isEqualTo(BatchStage.COMPLETED);
        InOrder order = inOrder(vlmTimeseriesStep, deidentifyStep, frameExtractor);
        order.verify(vlmTimeseriesStep).run(102L);
        order.verify(deidentifyStep).run(any(LsDataRaw.class));
        order.verify(frameExtractor).extractBoth(any(LsDataRaw.class), nullable(String.class));
    }

    @Test
    @DisplayName("PSDO_영상도_DeidentifyStep_호출")
    void psdoInvokesDeidentifyStep() {
        newRaw(103L, LsDataRaw.PRVC_TYPE_PSDO);

        BatchStage result = orchestrator.process(103L);

        assertThat(result).isEqualTo(BatchStage.COMPLETED);
        verify(deidentifyStep, times(1)).run(any(LsDataRaw.class));
    }

    @Test
    @DisplayName("BatchOrchestrator_V2_순서_VLM_DEIDENT_FRAME_YOLO_SAM2_INTERPOLATE")
    void v2PipelineOrderForPrvc() {
        newRaw(130L, LsDataRaw.PRVC_TYPE_PRVC);

        BatchStage result = orchestrator.process(130L);

        assertThat(result).isEqualTo(BatchStage.COMPLETED);
        InOrder order = inOrder(vlmTimeseriesStep, deidentifyStep, frameExtractor, yoloStep, sam2Step, trackInterpolationStep);
        order.verify(vlmTimeseriesStep).run(130L);
        order.verify(deidentifyStep).run(any(LsDataRaw.class));
        order.verify(frameExtractor).extractBoth(any(LsDataRaw.class), nullable(String.class));
        order.verify(yoloStep).run(130L);
        order.verify(sam2Step).run(eq(130L), any());
        order.verify(trackInterpolationStep).run(130L);
    }

    @Test
    @DisplayName("Phase2_ANONY도_VLM_DEIDENT_FRAME_순서")
    void v2PipelineOrderForAnony() {
        newRaw(131L, LsDataRaw.PRVC_TYPE_ANONY);

        BatchStage result = orchestrator.process(131L);

        assertThat(result).isEqualTo(BatchStage.COMPLETED);
        InOrder order = inOrder(vlmTimeseriesStep, deidentifyStep, frameExtractor, yoloStep, sam2Step, trackInterpolationStep);
        order.verify(vlmTimeseriesStep).run(131L);
        order.verify(deidentifyStep).run(any(LsDataRaw.class));
        order.verify(frameExtractor).extractBoth(any(LsDataRaw.class), nullable(String.class));
        order.verify(yoloStep).run(131L);
        order.verify(sam2Step).run(eq(131L), any());
        order.verify(trackInterpolationStep).run(131L);
    }

    @Test
    @DisplayName("BatchOrchestrator_VLM_META_실패_시_이후_모든_단계_호출_안_함_+_FAILED_마킹")
    void vlmMetaFailureFailsImmediately() {
        newRaw(132L, LsDataRaw.PRVC_TYPE_PRVC);
        doThrow(new RuntimeException("vlm-meta 5xx")).when(vlmTimeseriesStep).run(132L);

        BatchStage result = orchestrator.process(132L);

        assertThat(result).isEqualTo(BatchStage.FAILED);
        verify(deidentifyStep, never()).run(any());
        verify(frameExtractor, never()).extractBoth(any(), any());
        verify(yoloStep, never()).run(any());
        verify(sam2Step, never()).run(any(), any());
        verify(trackInterpolationStep, never()).run(any());
        verify(statusService).markFailed(eq(132L), any(RuntimeException.class));
        assertThat(retryQueue.retryCount(132L)).isEqualTo(1);
    }

    @Test
    @DisplayName("Phase4_프레임_추출_extractBoth_단일_호출")
    void frameExtractorInvokedOnce() {
        newRaw(133L, LsDataRaw.PRVC_TYPE_ANONY);

        BatchStage result = orchestrator.process(133L);

        assertThat(result).isEqualTo(BatchStage.COMPLETED);
        verify(frameExtractor, times(1)).extractBoth(any(LsDataRaw.class), nullable(String.class));
    }

    @Test
    @DisplayName("비식별_API_500_응답시_FAILED_+_재시도큐_등록")
    void deidentifyFailureMovesToFailedAndRetry() {
        newRaw(104L, LsDataRaw.PRVC_TYPE_PRVC);
        doThrow(new RuntimeException("deid 5xx")).when(deidentifyStep).run(any(LsDataRaw.class));

        BatchStage result = orchestrator.process(104L);

        assertThat(result).isEqualTo(BatchStage.FAILED);
        assertThat(retryQueue.retryCount(104L)).isEqualTo(1);
        verify(vlmTimeseriesStep).run(104L);
        verify(frameExtractor, never()).extractBoth(any(), any());
        verify(yoloStep, never()).run(any());
        verify(sam2Step, never()).run(any(), any());
        verify(trackInterpolationStep, never()).run(any());
        verify(statusService).markFailed(eq(104L), any(RuntimeException.class));
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
        when(frameExtractor.extractBoth(any(LsDataRaw.class), nullable(String.class))).thenReturn(List.of());

        BatchStage result = orchestrator.process(108L);

        assertThat(result).isEqualTo(BatchStage.FAILED);
        verify(vlmTimeseriesStep).run(108L);
        verify(deidentifyStep).run(any(LsDataRaw.class));
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

        ArgumentCaptor<LsDataRaw> captor = ArgumentCaptor.forClass(LsDataRaw.class);
        BatchStage result = orchestrator.process(109L);

        assertThat(result).isEqualTo(BatchStage.COMPLETED);
        assertThat(retryQueue.retryCount(109L)).isEqualTo(0);
        verify(frameExtractor, times(2)).extractBoth(captor.capture(), nullable(String.class));
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
        InOrder order = inOrder(vlmTimeseriesStep, deidentifyStep, frameExtractor, yoloStep, sam2Step, trackInterpolationStep);
        order.verify(vlmTimeseriesStep).run(120L);
        order.verify(deidentifyStep).run(any(LsDataRaw.class));
        order.verify(frameExtractor).extractBoth(any(LsDataRaw.class), nullable(String.class));
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

    @Test
    @DisplayName("Phase4_deidentifyStep_반환_경로가_extractBoth_에_전달")
    void deidVideoPathPassedToExtractor() {
        newRaw(140L, LsDataRaw.PRVC_TYPE_PRVC);
        String deidPath = "/storage/deidentified/videos/140/deidentified.mp4";
        when(deidentifyStep.run(any(LsDataRaw.class))).thenReturn(deidPath);

        BatchStage result = orchestrator.process(140L);

        assertThat(result).isEqualTo(BatchStage.COMPLETED);
        ArgumentCaptor<String> pathCaptor = ArgumentCaptor.forClass(String.class);
        verify(frameExtractor).extractBoth(any(LsDataRaw.class), pathCaptor.capture());
        assertThat(pathCaptor.getValue()).isEqualTo(deidPath);
    }
}
