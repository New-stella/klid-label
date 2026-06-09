package kr.co.cudo.authoring.batch.pipeline;

import kr.co.cudo.authoring.batch.orchestrator.BatchStage;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BatchContext stage 토글 단위 테스트 (Phase 3 — 조건부 step 일반화).
 *
 * <p>기본(토글 미주입) 컨텍스트는 모든 stage 가 enabled 이며(프로덕션 경로 무영향),
 * 토글 주입 시 해당 stage 만 비활성화된다. 키는 {@link BatchStage} 이름(String) 으로 둔다.
 */
class BatchContextToggleTest {

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

    @Test
    @DisplayName("기본_컨텍스트는_모든_stage_enabled")
    void defaultContextAllEnabled() {
        BatchContext ctx = new BatchContext(1L, rawWith(1L));

        assertThat(ctx.isStageEnabled(BatchStage.FRAME_EXTRACT)).isTrue();
        assertThat(ctx.isStageEnabled(BatchStage.YOLO)).isTrue();
        assertThat(ctx.isStageEnabled(BatchStage.SAM2)).isTrue();
        assertThat(ctx.isStageEnabled(BatchStage.DEIDENTIFY)).isTrue();
    }

    @Test
    @DisplayName("토글_off_주입시_해당_stage만_disabled_나머지는_enabled")
    void togglesDisableOnlyMappedStages() {
        Map<String, Boolean> toggles = Map.of(
                BatchStage.YOLO.name(), false,
                BatchStage.SAM2.name(), false);
        BatchContext ctx = new BatchContext(2L, rawWith(2L), toggles);

        assertThat(ctx.isStageEnabled(BatchStage.YOLO)).isFalse();
        assertThat(ctx.isStageEnabled(BatchStage.SAM2)).isFalse();
        // 미지정 stage 는 기본 true.
        assertThat(ctx.isStageEnabled(BatchStage.FRAME_EXTRACT)).isTrue();
        assertThat(ctx.isStageEnabled(BatchStage.INTERPOLATE)).isTrue();
    }

    @Test
    @DisplayName("null_토글_주입시_모든_stage_enabled")
    void nullTogglesAllEnabled() {
        BatchContext ctx = new BatchContext(3L, rawWith(3L), null);

        assertThat(ctx.isStageEnabled(BatchStage.YOLO)).isTrue();
        assertThat(ctx.isStageEnabled(BatchStage.FRAME_EXTRACT)).isTrue();
    }
}
