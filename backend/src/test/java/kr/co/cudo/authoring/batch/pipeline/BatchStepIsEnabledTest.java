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
 * <p>★<b>전 단계가 같은 규칙</b>으로 ctx 토글을 따른다 — 토글이 없으면 enabled, off 로 지정되면
 * disabled. 구 동작(FRAME_EXTRACT/YOLO/SAM2 3종만 반영, VLM·INTERPOLATE 는 항상 enabled)은 폐기됐다:
 * 단계 지목 재수행([@design API-201])의 「그 단계만」 범위가 이 토글로 전달되는데, INTERPOLATE 가
 * 토글을 무시하면 <b>「그 단계만」이 트랙 보간을 돌려 사람이 손댄 보간 라벨을 지운다</b>.
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
    @DisplayName("★VLM_step_isEnabled_도_ctx_토글을_반영한다_구_항상enabled_폐기")
    void vlmIsEnabledReflectsToggle() {
        VlmTimeseriesStep real = mock(VlmTimeseriesStep.class, org.mockito.Mockito.CALLS_REAL_METHODS);

        assertThat(real.isEnabled(defaultCtx())).isTrue();
        assertThat(real.isEnabled(ctxWithToggle(BatchStage.VLM, false))).isFalse();
    }

    @Test
    @DisplayName("★INTERPOLATE_step_isEnabled_도_ctx_토글을_반영한다_그단계만_범위의_핵심")
    void interpolateIsEnabledReflectsToggle() {
        // 이 단정이 무너지면 「그 단계만」 재수행이 보간을 돌려 사람이 손댄 보간 라벨을 전량 삭제한다.
        TrackInterpolationStep real = mock(TrackInterpolationStep.class, org.mockito.Mockito.CALLS_REAL_METHODS);

        assertThat(real.isEnabled(defaultCtx())).isTrue();
        assertThat(real.isEnabled(ctxWithToggle(BatchStage.INTERPOLATE, false))).isFalse();
    }
}
