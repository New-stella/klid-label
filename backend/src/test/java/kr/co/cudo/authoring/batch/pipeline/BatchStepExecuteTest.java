package kr.co.cudo.authoring.batch.pipeline;

import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.orchestrator.BatchStage;
import kr.co.cudo.authoring.batch.step.BbHint;
import kr.co.cudo.authoring.batch.step.FfmpegFrameExtractor;
import kr.co.cudo.authoring.batch.step.Sam2SegmentStep;
import kr.co.cudo.authoring.batch.step.TrackInterpolationStep;
import kr.co.cudo.authoring.batch.step.VlmTimeseriesStep;
import kr.co.cudo.authoring.batch.step.YoloAutolabelStep;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.marking.dto.MarkItem;
import kr.co.cudo.authoring.marking.entity.LsMarking;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 각 step 의 {@code execute(BatchContext)} 경로 단위 테스트 — typed 메서드로의 위임 +
 * stage 식별자 + ctx 읽기/쓰기 + FRAME_EXTRACT 가드를 검증한다.
 *
 * <p>typed 메서드(run/extractByMarks)는 spy 로 스텁하여 DB/외부 호출 없이 위임만 검증한다.
 */
class BatchStepExecuteTest {

    private static LsDataRaw rawWith(Long rawSn) {
        LsDataRaw raw = LsDataRaw.createFromIngest(
                "clip-" + rawSn, "cctv-1", "EVT", "GOV",
                LsDataRaw.PRVC_TYPE_ANONY, "raw/path.mp4", null, 60);
        try {
            Field f = raw.getClass().getDeclaredField("rawSn");
            f.setAccessible(true);
            f.set(raw, rawSn);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        return raw;
    }

    private static MarkItem mark(int idx) {
        return new MarkItem(idx, "00:00");
    }

    // --- VLM ---

    @Test
    @DisplayName("VLM_execute_마킹있으면_runWithMarking_호출_stage_VLM")
    void vlmExecuteWithMarking() {
        VlmTimeseriesStep real = mock(VlmTimeseriesStep.class, org.mockito.Mockito.CALLS_REAL_METHODS);
        doReturn(null).when(real).runWithMarking(any(), any());

        LsMarking marking = LsMarking.createAuto(1L, "fire", 5, "p.mp4", "[]", 1L);
        BatchContext ctx = new BatchContext(1L, rawWith(1L));
        ctx.setMarkings(List.of(marking));

        real.execute(ctx);

        assertThat(real.stage()).isEqualTo(BatchStage.VLM);
        verify(real).runWithMarking(eq(1L), eq(marking));
        verify(real, never()).run(any());
    }

    @Test
    @DisplayName("VLM_execute_마킹없으면_run_호출")
    void vlmExecuteWithoutMarking() {
        VlmTimeseriesStep real = mock(VlmTimeseriesStep.class, org.mockito.Mockito.CALLS_REAL_METHODS);
        org.mockito.Mockito.doReturn(null).when(real).run(any());

        BatchContext ctx = new BatchContext(2L, rawWith(2L));

        real.execute(ctx);

        verify(real).run(2L);
        verify(real, never()).runWithMarking(any(), any());
    }

    // --- FRAME_EXTRACT ---

    @Test
    @DisplayName("FRAME_execute_marks있으면_extractByMarks_호출_stage_FRAME_EXTRACT")
    void frameExecuteWithMarks() {
        FfmpegFrameExtractor real = mock(FfmpegFrameExtractor.class, org.mockito.Mockito.CALLS_REAL_METHODS);
        doReturn(List.of(mock(LsDataSrc.class))).when(real).extractByMarks(any(LsDataRaw.class), any());

        LsDataRaw raw = rawWith(3L);
        BatchContext ctx = new BatchContext(3L, raw);
        ctx.setMarks(List.of(mark(0)));

        real.execute(ctx);

        assertThat(real.stage()).isEqualTo(BatchStage.FRAME_EXTRACT);
        verify(real).extractByMarks(eq(raw), any());
    }

    @Test
    @DisplayName("FRAME_execute_marks비면_INVALID_INPUT_extractByMarks_미호출")
    void frameExecuteEmptyMarks() {
        FfmpegFrameExtractor real = mock(FfmpegFrameExtractor.class, org.mockito.Mockito.CALLS_REAL_METHODS);

        BatchContext ctx = new BatchContext(4L, rawWith(4L));

        assertThatThrownBy(() -> real.execute(ctx))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode().name()).isEqualTo("INVALID_INPUT"));
        verify(real, never()).extractByMarks(any(), any());
    }

    @Test
    @DisplayName("FRAME_execute_추출결과_0건이면_INTERNAL_ERROR")
    void frameExecuteEmptyFrames() {
        FfmpegFrameExtractor real = mock(FfmpegFrameExtractor.class, org.mockito.Mockito.CALLS_REAL_METHODS);
        doReturn(List.of()).when(real).extractByMarks(any(LsDataRaw.class), any());

        BatchContext ctx = new BatchContext(5L, rawWith(5L));
        ctx.setMarks(List.of(mark(0)));

        assertThatThrownBy(() -> real.execute(ctx))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode().name()).isEqualTo("INTERNAL_ERROR"));
    }

    // --- YOLO ---

    @Test
    @DisplayName("YOLO_execute_run_결과를_ctx_hints_에_적재_stage_YOLO")
    void yoloExecuteSetsHints() {
        YoloAutolabelStep real = mock(YoloAutolabelStep.class, org.mockito.Mockito.CALLS_REAL_METHODS);
        List<BbHint> hints = List.of(new BbHint(1L, "person", List.of(1.0, 2.0, 3.0, 4.0), 0.9, null));
        doReturn(hints).when(real).run(any());

        BatchContext ctx = new BatchContext(6L, rawWith(6L));
        real.execute(ctx);

        assertThat(real.stage()).isEqualTo(BatchStage.YOLO);
        verify(real).run(6L);
        assertThat(ctx.getHints()).containsExactlyElementsOf(hints);
    }

    // --- SAM2 ---

    @Test
    @DisplayName("SAM2_execute_ctx_hints_를_run_에_전달_stage_SAM2")
    void sam2ExecutePassesHints() {
        Sam2SegmentStep real = mock(Sam2SegmentStep.class, org.mockito.Mockito.CALLS_REAL_METHODS);
        doReturn(0).when(real).run(any(), any());

        List<BbHint> hints = List.of(new BbHint(1L, "car", List.of(1.0, 2.0, 3.0, 4.0), 0.8, 3));
        BatchContext ctx = new BatchContext(7L, rawWith(7L));
        ctx.setHints(hints);

        real.execute(ctx);

        assertThat(real.stage()).isEqualTo(BatchStage.SAM2);
        verify(real).run(eq(7L), eq(hints));
    }

    // --- INTERPOLATE ---

    @Test
    @DisplayName("INTERPOLATE_execute_run_호출_stage_INTERPOLATE")
    void interpolateExecute() {
        TrackInterpolationStep real = mock(TrackInterpolationStep.class, org.mockito.Mockito.CALLS_REAL_METHODS);
        doReturn(0).when(real).run(any());

        BatchContext ctx = new BatchContext(8L, rawWith(8L));
        real.execute(ctx);

        assertThat(real.stage()).isEqualTo(BatchStage.INTERPOLATE);
        verify(real).run(8L);
    }

    @Test
    @DisplayName("INTERPOLATE_execute_run_실패_전파")
    void interpolateExecutePropagatesFailure() {
        TrackInterpolationStep real = mock(TrackInterpolationStep.class, org.mockito.Mockito.CALLS_REAL_METHODS);
        doThrow(new RuntimeException("boom")).when(real).run(any());

        BatchContext ctx = new BatchContext(9L, rawWith(9L));
        assertThatThrownBy(() -> real.execute(ctx)).isInstanceOf(RuntimeException.class);
    }
}
