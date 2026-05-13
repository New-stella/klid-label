package kr.co.cudo.authoring.batch.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LsDataLblTest {

    @Test
    @DisplayName("createAutoBbox는_AUTO_LBL_YN_Y_+_BBOX_타입_저장")
    void createAutoBboxMarksAutoYes() {
        LsDataLbl lbl = LsDataLbl.createAutoBbox(
                100L, "person", "[[10,10],[20,20]]", BigDecimal.valueOf(0.85));

        assertThat(lbl.getAutoLblYn()).isEqualTo("Y");
        assertThat(lbl.getLblTypeCd()).isEqualTo("BBOX");
        assertThat(lbl.getLabel()).isEqualTo("person");
        assertThat(lbl.getConfScore()).isEqualByComparingTo("0.85");
    }

    @Test
    @DisplayName("createAutoPolygon은_POLYGON_타입_+_AUTO_LBL_YN_Y")
    void createAutoPolygonType() {
        LsDataLbl lbl = LsDataLbl.createAutoPolygon(
                200L, "car", "[[1,1],[2,2],[3,3]]", BigDecimal.valueOf(0.7));

        assertThat(lbl.getLblTypeCd()).isEqualTo("POLYGON");
        assertThat(lbl.getAutoLblYn()).isEqualTo("Y");
    }

    @Test
    @DisplayName("CONF_SCORE는_0_미만이면_IllegalArgumentException")
    void negativeScoreRejected() {
        assertThatThrownBy(() -> LsDataLbl.createAutoBbox(
                1L, "x", "[]", BigDecimal.valueOf(-0.1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CONF_SCORE");
    }

    @Test
    @DisplayName("CONF_SCORE는_1_초과면_IllegalArgumentException")
    void scoreAboveOneRejected() {
        assertThatThrownBy(() -> LsDataLbl.createAutoBbox(
                1L, "x", "[]", BigDecimal.valueOf(1.01)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("CONF_SCORE는_0_과_1_경계값_허용")
    void boundaryScoresAllowed() {
        LsDataLbl zero = LsDataLbl.createAutoBbox(1L, "x", "[]", BigDecimal.ZERO);
        LsDataLbl one = LsDataLbl.createAutoBbox(2L, "x", "[]", BigDecimal.ONE);
        assertThat(zero.getConfScore()).isEqualByComparingTo("0");
        assertThat(one.getConfScore()).isEqualByComparingTo("1");
    }

    @Test
    @DisplayName("updateConfScore는_새_값으로_갱신_+_범위_검증")
    void updateScoreClampsRange() {
        LsDataLbl lbl = LsDataLbl.createAutoBbox(1L, "x", "[]", BigDecimal.valueOf(0.5));
        lbl.updateConfScore(BigDecimal.valueOf(0.92));
        assertThat(lbl.getConfScore()).isEqualByComparingTo("0.92");

        assertThatThrownBy(() -> lbl.updateConfScore(BigDecimal.valueOf(2.0)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- Phase 3: TRACK_ID 컬럼 + factory 5-arg ---

    @Test
    @DisplayName("createAutoBbox_5arg_는_trackId_저장")
    void createAutoBboxFiveArgRetainsTrackId() {
        LsDataLbl lbl = LsDataLbl.createAutoBbox(
                100L, "person", "[[10,10],[20,20]]", BigDecimal.valueOf(0.85), "42");

        assertThat(lbl.getTrackId()).isEqualTo("42");
        assertThat(lbl.getAutoLblYn()).isEqualTo("Y");
        assertThat(lbl.getLblTypeCd()).isEqualTo("BBOX");
    }

    @Test
    @DisplayName("createAutoBbox_4arg_는_trackId_null_로_위임_(레거시_호환)")
    void createAutoBboxFourArgDelegatesNullTrackId() {
        LsDataLbl lbl = LsDataLbl.createAutoBbox(
                100L, "person", "[]", BigDecimal.valueOf(0.8));

        assertThat(lbl.getTrackId()).isNull();
        assertThat(lbl.getAutoLblYn()).isEqualTo("Y");
    }

    @Test
    @DisplayName("createAutoBbox_5arg_trackId_null_입력도_허용")
    void createAutoBboxFiveArgAllowsNullTrackId() {
        LsDataLbl lbl = LsDataLbl.createAutoBbox(
                1L, "x", "[]", BigDecimal.valueOf(0.5), null);

        assertThat(lbl.getTrackId()).isNull();
    }

    @Test
    @DisplayName("수동_라벨_(createManual)_은_trackId_null")
    void createManualHasNullTrackId() {
        LsDataLbl lbl = LsDataLbl.createManual(1L, "BBOX", "person", "[]", 99L);

        assertThat(lbl.getTrackId()).isNull();
        assertThat(lbl.getAutoLblYn()).isEqualTo("N");
    }
}
