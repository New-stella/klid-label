package kr.co.cudo.authoring.label.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 2 — 라벨 형태 파생(강제) 규칙 검증. 마스터 {@code LBL_TYPE_CD} → bbox/polygon 토글.
 */
class LabelGeometryTest {

    @Test
    @DisplayName("BBOX면_bbox만_활성이다")
    void bboxEnablesBboxOnly() {
        LabelGeometry g = LabelGeometry.from("BBOX").orElseThrow();
        assertThat(g.bboxEnabled()).isTrue();
        assertThat(g.polygonEnabled()).isFalse();
    }

    @Test
    @DisplayName("POLYGON이면_polygon만_활성이다")
    void polygonEnablesPolygonOnly() {
        LabelGeometry g = LabelGeometry.from("POLYGON").orElseThrow();
        assertThat(g.bboxEnabled()).isFalse();
        assertThat(g.polygonEnabled()).isTrue();
    }

    @Test
    @DisplayName("POINT_SKELETON이면_두_토글_모두_비활성이다")
    void pointAndSkeletonDisableBoth() {
        LabelGeometry point = LabelGeometry.from("POINT").orElseThrow();
        LabelGeometry skeleton = LabelGeometry.from("SKELETON").orElseThrow();
        assertThat(point.bboxEnabled()).isFalse();
        assertThat(point.polygonEnabled()).isFalse();
        assertThat(skeleton.bboxEnabled()).isFalse();
        assertThat(skeleton.polygonEnabled()).isFalse();
    }

    @Test
    @DisplayName("대소문자_공백_무시하고_파싱한다")
    void normalizesCaseAndWhitespace() {
        assertThat(LabelGeometry.from("  bbox ")).contains(LabelGeometry.BBOX);
    }

    @Test
    @DisplayName("null_blank_미지원값은_빈_Optional")
    void unsupportedReturnsEmpty() {
        assertThat(LabelGeometry.from(null)).isEmpty();
        assertThat(LabelGeometry.from("  ")).isEmpty();
        assertThat(LabelGeometry.from("UNKNOWN")).isEmpty();
    }
}
