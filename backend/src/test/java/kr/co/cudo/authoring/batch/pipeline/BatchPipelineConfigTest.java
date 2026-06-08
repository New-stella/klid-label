package kr.co.cudo.authoring.batch.pipeline;

import kr.co.cudo.authoring.batch.orchestrator.BatchStage;
import kr.co.cudo.authoring.batch.step.DeidentifyStep;
import kr.co.cudo.authoring.batch.step.FfmpegFrameExtractor;
import kr.co.cudo.authoring.batch.step.Sam2SegmentStep;
import kr.co.cudo.authoring.batch.step.TrackInterpolationStep;
import kr.co.cudo.authoring.batch.step.VlmTimeseriesStep;
import kr.co.cudo.authoring.batch.step.YoloAutolabelStep;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 파이프라인 단계 순서 회귀 방지 테스트.
 *
 * <p>순서 변경 시 수정 지점이 {@link BatchPipelineConfig} 한 곳임을 고정한다.
 * 단계 순서가 [MARKING, VLM, FRAME_EXTRACT, YOLO, SAM2, INTERPOLATE] 인지 단언하여,
 * 의도치 않은 재배치(회귀)를 차단한다.
 */
class BatchPipelineConfigTest {

    @Test
    @DisplayName("파이프라인_순서는_MARKING_VLM_FRAME_YOLO_SAM2_INTERPOLATE")
    void pipelineStageOrderIsFixed() {
        MarkingLoadStep markingLoad = mock(MarkingLoadStep.class);
        VlmTimeseriesStep vlm = mock(VlmTimeseriesStep.class);
        FfmpegFrameExtractor frame = mock(FfmpegFrameExtractor.class);
        YoloAutolabelStep yolo = mock(YoloAutolabelStep.class);
        Sam2SegmentStep sam2 = mock(Sam2SegmentStep.class);
        TrackInterpolationStep interp = mock(TrackInterpolationStep.class);

        when(markingLoad.stage()).thenReturn(BatchStage.MARKING);
        when(vlm.stage()).thenReturn(BatchStage.VLM);
        when(frame.stage()).thenReturn(BatchStage.FRAME_EXTRACT);
        when(yolo.stage()).thenReturn(BatchStage.YOLO);
        when(sam2.stage()).thenReturn(BatchStage.SAM2);
        when(interp.stage()).thenReturn(BatchStage.INTERPOLATE);

        BatchPipeline pipeline = new BatchPipelineConfig()
                .postMarkingPipeline(markingLoad, vlm, frame, yolo, sam2, interp);

        List<BatchStage> stages = pipeline.steps().stream().map(BatchStep::stage).toList();
        assertThat(stages).containsExactly(
                BatchStage.MARKING,
                BatchStage.VLM,
                BatchStage.FRAME_EXTRACT,
                BatchStage.YOLO,
                BatchStage.SAM2,
                BatchStage.INTERPOLATE);
    }

    @Test
    @DisplayName("선두_비식별_파이프라인은_DEIDENTIFY_단계만")
    void preMarkingPipelineIsDeidentifyOnly() {
        DeidentifyStep deid = mock(DeidentifyStep.class);
        when(deid.stage()).thenReturn(BatchStage.DEIDENTIFY);

        BatchPipeline pipeline = new BatchPipelineConfig().preMarkingPipeline(deid);

        List<BatchStage> stages = pipeline.steps().stream().map(BatchStep::stage).toList();
        assertThat(stages).containsExactly(BatchStage.DEIDENTIFY);
    }
}
