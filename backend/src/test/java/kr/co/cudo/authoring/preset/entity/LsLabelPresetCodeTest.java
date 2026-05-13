package kr.co.cudo.authoring.preset.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 1 — LsLabelPresetCode 의 BBOX/POLYGON 토글 동작 검증.
 *
 * <p>패키지 가시성 팩토리({@code of(preset, code, sortOrder, ...)})는
 * 같은 패키지에 있는 본 테스트에서 호출 가능하다.
 */
class LsLabelPresetCodeTest {

    @Test
    @DisplayName("LsLabelPresetCode_의_기본_팩토리는_bbox_polygon_모두_true")
    void defaultFactoryEnablesBothAnnotations() {
        LsLabelPreset preset = LsLabelPreset.create("기본", "", List.of("PERSON"), null);

        LsLabelPresetCode code = preset.getCodes().get(0);

        assertThat(code.isBboxEnabled()).isTrue();
        assertThat(code.isPolygonEnabled()).isTrue();
        assertThat(code.isAtLeastOneEnabled()).isTrue();
    }

    @Test
    @DisplayName("LsLabelPresetCode_새_팩토리는_지정한_토글값을_그대로_저장")
    void explicitFactoryPersistsExactToggles() {
        LsLabelPreset preset = LsLabelPreset.create("프리셋", "", List.of(), null);

        LsLabelPresetCode bboxOnly = LsLabelPresetCode.of(preset, "PERSON", 0, true, false);
        LsLabelPresetCode polygonOnly = LsLabelPresetCode.of(preset, "VEHICLE", 1, false, true);

        assertThat(bboxOnly.isBboxEnabled()).isTrue();
        assertThat(bboxOnly.isPolygonEnabled()).isFalse();
        assertThat(polygonOnly.isBboxEnabled()).isFalse();
        assertThat(polygonOnly.isPolygonEnabled()).isTrue();
    }

    @Test
    @DisplayName("LsLabelPresetCode_의_AssertTrue_가_둘_다_false_면_실패")
    void assertTrueRejectsBothDisabled() {
        LsLabelPreset preset = LsLabelPreset.create("프리셋", "", List.of(), null);

        LsLabelPresetCode bothFalse = LsLabelPresetCode.of(preset, "PERSON", 0, false, false);

        assertThat(bothFalse.isAtLeastOneEnabled()).isFalse();
    }

    @Test
    @DisplayName("LsLabelPresetCode_BOTH_또는_단일_활성_조합은_AssertTrue_통과")
    void assertTrueAllowsAtLeastOneEnabled() {
        LsLabelPreset preset = LsLabelPreset.create("프리셋", "", List.of(), null);

        assertThat(LsLabelPresetCode.of(preset, "A", 0, true, true).isAtLeastOneEnabled()).isTrue();
        assertThat(LsLabelPresetCode.of(preset, "B", 1, true, false).isAtLeastOneEnabled()).isTrue();
        assertThat(LsLabelPresetCode.of(preset, "C", 2, false, true).isAtLeastOneEnabled()).isTrue();
    }

    @Test
    @DisplayName("LsLabelPresetCode_기존_legacy_팩토리는_둘다_true_로_위임")
    @SuppressWarnings("deprecation")
    void legacyFactoryDelegatesToBoth() {
        LsLabelPreset preset = LsLabelPreset.create("프리셋", "", List.of(), null);

        LsLabelPresetCode code = LsLabelPresetCode.of(preset, "PERSON", 0);

        assertThat(code.isBboxEnabled()).isTrue();
        assertThat(code.isPolygonEnabled()).isTrue();
    }
}
