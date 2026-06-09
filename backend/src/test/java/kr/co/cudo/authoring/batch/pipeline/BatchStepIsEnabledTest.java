package kr.co.cudo.authoring.batch.pipeline;

import kr.co.cudo.authoring.batch.orchestrator.BatchStage;
import kr.co.cudo.authoring.batch.step.FfmpegFrameExtractor;
import kr.co.cudo.authoring.batch.step.Sam2SegmentStep;
import kr.co.cudo.authoring.batch.step.TrackInterpolationStep;
import kr.co.cudo.authoring.batch.step.VlmTimeseriesStep;
import kr.co.cudo.authoring.batch.step.YoloAutolabelStep;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 각 step 의 {@code isEnabled(ctx)} 토글 동작 단위 테스트 (Phase 3 — 조건부 step 일반화).
 *
 * <p>토글 대상(FRAME_EXTRACT/YOLO/SAM2)은 ctx 토글에 따라 enabled 가 변하고,
 * 비토글 대상(VLM/INTERPOLATE)은 항상 enabled 다. MarkingLoadStep 도 항상 enabled(기본 default).
 */
class BatchStepIsEnabledTest {

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

    private static BatchContext ctxWithToggle(BatchStage stage, boolean enabled) {
        return new BatchContext(1L, rawWith(1L), Map.of(stage.name(), enabled));
    }

    private static BatchContext defaultCtx() {
        return new BatchContext(1L, rawWith(1L));
    }

    @Test
    @DisplayName("FRAME_EXTRACT_step_isEnabled_가_ctx_토글을_반영")
    void frameExtractIsEnabledReflectsToggle() {
        FfmpegFrameExtractor real = mock(FfmpegFrameExtractor.class, org.mockito.Mockito.CALLS_REAL_METHODS);

        assertThat(real.isEnabled(defaultCtx())).isTrue();
        assertThat(real.isEnabled(ctxWithToggle(BatchStage.FRAME_EXTRACT, false))).isFalse();
        assertThat(real.isEnabled(ctxWithToggle(BatchStage.FRAME_EXTRACT, true))).isTrue();
    }

    @Test
    @DisplayName("YOLO_step_isEnabled_가_ctx_토글을_반영")
    void yoloIsEnabledReflectsToggle() {
        YoloAutolabelStep real = mock(YoloAutolabelStep.class, org.mockito.Mockito.CALLS_REAL_METHODS);

        assertThat(real.isEnabled(defaultCtx())).isTrue();
        assertThat(real.isEnabled(ctxWithToggle(BatchStage.YOLO, false))).isFalse();
    }

    @Test
    @DisplayName("SAM2_step_isEnabled_가_ctx_토글을_반영")
    void sam2IsEnabledReflectsToggle() {
        Sam2SegmentStep real = mock(Sam2SegmentStep.class, org.mockito.Mockito.CALLS_REAL_METHODS);

        assertThat(real.isEnabled(defaultCtx())).isTrue();
        assertThat(real.isEnabled(ctxWithToggle(BatchStage.SAM2, false))).isFalse();
    }

    @Test
    @DisplayName("VLM_step_은_토글과_무관하게_항상_enabled")
    void vlmAlwaysEnabled() {
        VlmTimeseriesStep real = mock(VlmTimeseriesStep.class, org.mockito.Mockito.CALLS_REAL_METHODS);

        assertThat(real.isEnabled(defaultCtx())).isTrue();
        // VLM 키로 false 를 줘도 무시(항상 enabled — 비토글 대상).
        assertThat(real.isEnabled(ctxWithToggle(BatchStage.VLM, false))).isTrue();
    }

    @Test
    @DisplayName("INTERPOLATE_step_은_토글과_무관하게_항상_enabled")
    void interpolateAlwaysEnabled() {
        TrackInterpolationStep real = mock(TrackInterpolationStep.class, org.mockito.Mockito.CALLS_REAL_METHODS);

        assertThat(real.isEnabled(defaultCtx())).isTrue();
        assertThat(real.isEnabled(ctxWithToggle(BatchStage.INTERPOLATE, false))).isTrue();
    }
}
