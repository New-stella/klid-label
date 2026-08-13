package kr.co.cudo.authoring.batch.entity;

import kr.co.cudo.authoring.common.exception.CustomException;
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
                100L, null, "person", "[[10,10],[20,20]]", BigDecimal.valueOf(0.85), null);

        assertThat(lbl.getAutoLblYn()).isEqualTo("Y");
        assertThat(lbl.getLblTypeCd()).isEqualTo("BBOX");
        assertThat(lbl.getLabelNm()).isEqualTo("person");
        assertThat(lbl.getConfScore()).isEqualByComparingTo("0.85");
    }

    @Test
    @DisplayName("createAutoPolygon은_POLYGON_타입_+_AUTO_LBL_YN_Y")
    void createAutoPolygonType() {
        LsDataLbl lbl = LsDataLbl.createAutoPolygon(
                200L, null, "car", "[[1,1],[2,2],[3,3]]", BigDecimal.valueOf(0.7));

        assertThat(lbl.getLblTypeCd()).isEqualTo("POLYGON");
        assertThat(lbl.getAutoLblYn()).isEqualTo("Y");
    }

    @Test
    @DisplayName("CONF_SCORE는_0_미만이면_IllegalArgumentException")
    void negativeScoreRejected() {
        assertThatThrownBy(() -> LsDataLbl.createAutoBbox(
                1L, null, "x", "[]", BigDecimal.valueOf(-0.1), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CONF_SCORE");
    }

    @Test
    @DisplayName("CONF_SCORE는_1_초과면_IllegalArgumentException")
    void scoreAboveOneRejected() {
        assertThatThrownBy(() -> LsDataLbl.createAutoBbox(
                1L, null, "x", "[]", BigDecimal.valueOf(1.01), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("CONF_SCORE는_0_과_1_경계값_허용")
    void boundaryScoresAllowed() {
        LsDataLbl zero = LsDataLbl.createAutoBbox(1L, null, "x", "[]", BigDecimal.ZERO, null);
        LsDataLbl one = LsDataLbl.createAutoBbox(2L, null, "x", "[]", BigDecimal.ONE, null);
        assertThat(zero.getConfScore()).isEqualByComparingTo("0");
        assertThat(one.getConfScore()).isEqualByComparingTo("1");
    }

    @Test
    @DisplayName("updateConfScore는_새_값으로_갱신_+_범위_검증")
    void updateScoreClampsRange() {
        LsDataLbl lbl = LsDataLbl.createAutoBbox(1L, null, "x", "[]", BigDecimal.valueOf(0.5), null);
        lbl.updateConfScore(BigDecimal.valueOf(0.92));
        assertThat(lbl.getConfScore()).isEqualByComparingTo("0.92");

        assertThatThrownBy(() -> lbl.updateConfScore(BigDecimal.valueOf(2.0)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- Phase 3: TRACK_ID 컬럼 + factory 5-arg ---

    @Test
    @DisplayName("createAutoBbox_6arg_는_trackId_저장")
    void createAutoBboxFiveArgRetainsTrackId() {
        LsDataLbl lbl = LsDataLbl.createAutoBbox(
                100L, null, "person", "[[10,10],[20,20]]", BigDecimal.valueOf(0.85), "42");

        assertThat(lbl.getTrackId()).isEqualTo("42");
        assertThat(lbl.getAutoLblYn()).isEqualTo("Y");
        assertThat(lbl.getLblTypeCd()).isEqualTo("BBOX");
    }

    @Test
    @DisplayName("createAutoBbox_trackId_null_입력_허용")
    void createAutoBboxFourArgDelegatesNullTrackId() {
        LsDataLbl lbl = LsDataLbl.createAutoBbox(
                100L, null, "person", "[]", BigDecimal.valueOf(0.8), null);

        assertThat(lbl.getTrackId()).isNull();
        assertThat(lbl.getAutoLblYn()).isEqualTo("Y");
    }

    @Test
    @DisplayName("수동_라벨_(createManual)_은_trackId_null")
    void createManualHasNullTrackId() {
        LsDataLbl lbl = LsDataLbl.createManual(1L, "BBOX", null, "person", "[]", 99L);

        assertThat(lbl.getTrackId()).isNull();
        // ⚠ V6 — 구 기대값은 "N" 이었다. 흡수 후 <b>DB 축은 부재를 null 로</b> 표현한다("N" 이 아니다) —
        //   "사람이 그린 라벨"과 "AI 가 만들었으나 자동이 아님"(롤백 복원)을 영구히 구분하기 위함이다.
        //   응답이 'N' 으로 보이는 것은 그대로이며 그 치환은 LabelResponse.Item.from 한 곳이 담당한다.
        //   되돌리지 말 것 — 여기서 "N" 을 쓰면 그 두 사실이 같은 값이 된다.
        assertThat(lbl.getAutoLblYn()).isNull();
    }

    // --- Phase 3: LBL_SRC_CD 컬럼 + createAutoInterpolatedBbox factory ---

    @Test
    @DisplayName("createAutoInterpolatedBbox_는_LBL_SRC_CD_INTERPOLATE_저장")
    void createAutoInterpolatedBboxMarksLblSrcCd() {
        LsDataLbl lbl = LsDataLbl.createAutoInterpolatedBbox(
                100L, null, "person", "[10.0,20.0,30.0,40.0]", BigDecimal.ZERO, "7");

        // ⚠ V6 — 구 기대값 "INTERPOLATED"(과거분사)는 <b>DB 에 한 번도 닿은 적이 없다</b>. 그 필드가
        //   @Transient 라 실제 적재값·조회 술어는 줄곧 "INTERPOLATE" 였고, 흡수로 이 필드가 적재값이
        //   되면서 그 잠복 결함을 바로잡았다(상수도 SRC_INTERPOLATE 하나로 통일). 되돌리면 보간
        //   산출물이 조회(findInterpolatedLblSnsByRawSn)에서 통째로 사라진다.
        assertThat(lbl.getLblSrcCd()).isEqualTo(LsDataLbl.SRC_INTERPOLATE);
        assertThat(lbl.getAutoLblYn()).isEqualTo("Y");
        assertThat(lbl.getLblTypeCd()).isEqualTo("BBOX");
        assertThat(lbl.getLabelNm()).isEqualTo("person");
        assertThat(lbl.getTrackId()).isEqualTo("7");
        assertThat(lbl.getPointCn()).isEqualTo("[10.0,20.0,30.0,40.0]");
        assertThat(lbl.getConfScore()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("createAutoInterpolatedBbox_confScore_null_허용")
    void createAutoInterpolatedBboxAllowsNullConfScore() {
        LsDataLbl lbl = LsDataLbl.createAutoInterpolatedBbox(
                1L, null, "car", "[0.0,0.0,10.0,10.0]", null, "3");

        assertThat(lbl.getConfScore()).isNull();
        // V6 — 구 "INTERPOLATED" → 실제 적재값 "INTERPOLATE"(위 케이스 주석 참조).
        assertThat(lbl.getLblSrcCd()).isEqualTo(LsDataLbl.SRC_INTERPOLATE);
    }

    @Test
    @DisplayName("createAutoBbox_는_LBL_SRC_CD_null_(DETECTED_기본)")
    void createAutoBboxLblSrcCdNull() {
        LsDataLbl lbl = LsDataLbl.createAutoBbox(
                100L, null, "person", "[]", BigDecimal.valueOf(0.85), "1");

        assertThat(lbl.getLblSrcCd()).isNull();
    }

    @Test
    @DisplayName("createAutoPolygon_은_LBL_SRC_CD_null_(DETECTED_기본)")
    void createAutoPolygonLblSrcCdNull() {
        LsDataLbl lbl = LsDataLbl.createAutoPolygon(
                1L, null, "car", "[]", BigDecimal.valueOf(0.7));

        assertThat(lbl.getLblSrcCd()).isNull();
    }

    @Test
    @DisplayName("createManual_은_LBL_SRC_CD_null_(DETECTED_기본)")
    void createManualLblSrcCdNull() {
        LsDataLbl lbl = LsDataLbl.createManual(1L, "BBOX", null, "person", "[]", 99L);

        assertThat(lbl.getLblSrcCd()).isNull();
    }

    // --- Phase 2: LABEL_ID FK 컬럼 + factory 오버로드 ---

    @Test
    @DisplayName("createAutoBbox_6arg_는_labelId_저장")
    void createAutoBboxSixArgRetainsLabelId() {
        LsDataLbl lbl = LsDataLbl.createAutoBbox(
                100L, 7L, "person", "[[10,10],[20,20]]", BigDecimal.valueOf(0.85), "track-1");

        assertThat(lbl.getLabelId()).isEqualTo(7L);
        assertThat(lbl.getAutoLblYn()).isEqualTo("Y");
        assertThat(lbl.getTrackId()).isEqualTo("track-1");
    }

    @Test
    @DisplayName("createAutoBbox_labelId_null_전달_시_null_저장")
    void createAutoBboxLabelIdNull() {
        LsDataLbl lbl = LsDataLbl.createAutoBbox(
                100L, null, "person", "[]", BigDecimal.valueOf(0.5), "track-1");

        assertThat(lbl.getLabelId()).isNull();
    }

    @Test
    @DisplayName("createAutoPolygon_5arg_는_labelId_저장")
    void createAutoPolygonWithLabelId() {
        LsDataLbl lbl = LsDataLbl.createAutoPolygon(
                100L, 9L, "fire", "[[1,1],[2,2]]", BigDecimal.valueOf(0.7));

        assertThat(lbl.getLabelId()).isEqualTo(9L);
        assertThat(lbl.getLblTypeCd()).isEqualTo("POLYGON");
    }

    @Test
    @DisplayName("createManual_6arg_는_labelId_저장")
    void createManualWithLabelId() {
        LsDataLbl lbl = LsDataLbl.createManual(1L, "BBOX", 5L, "person", "[]", 99L);

        assertThat(lbl.getLabelId()).isEqualTo(5L);
        // V6 — DB 축은 부재를 null 로 표현한다(위 createManualHasNullTrackId 주석 참조).
        assertThat(lbl.getAutoLblYn()).isNull();
    }

    @Test
    @DisplayName("createAutoInterpolatedBbox_6arg_는_labelId_저장")
    void createAutoInterpolatedBboxWithLabelId() {
        LsDataLbl lbl = LsDataLbl.createAutoInterpolatedBbox(
                100L, 3L, "car", "[0.0,0.0,10.0,10.0]", BigDecimal.ZERO, "track-2");

        assertThat(lbl.getLabelId()).isEqualTo(3L);
        // V6 — 구 "INTERPOLATED" → 실제 적재값 "INTERPOLATE".
        assertThat(lbl.getLblSrcCd()).isEqualTo(LsDataLbl.SRC_INTERPOLATE);
    }

    @Test
    @DisplayName("updateUserContent_labelId_non_null_시_LABEL_ID_변경")
    void updateUserContentChangesLabelId() {
        LsDataLbl lbl = LsDataLbl.createManual(1L, "BBOX", 5L, "person", "[[1,1],[2,2]]", 99L);

        lbl.updateUserContent("BBOX", 10L, "car", "[[3,3],[4,4]]");

        assertThat(lbl.getLabelId()).isEqualTo(10L);
        assertThat(lbl.getLabelNm()).isEqualTo("car");
    }

    @Test
    @DisplayName("updateUserContent_labelId_null_시_기존_LABEL_ID_유지")
    void updateUserContentPreservesLabelIdOnNull() {
        LsDataLbl lbl = LsDataLbl.createManual(1L, "BBOX", 5L, "person", "[[1,1],[2,2]]", 99L);

        lbl.updateUserContent("BBOX", null, "person", "[[3,3],[4,4]]");

        assertThat(lbl.getLabelId()).isEqualTo(5L);  // 그대로 유지
        assertThat(lbl.getPointCn()).contains("3,3");
    }

    // --- V2.0: copyForNewSrc ---

    @Test
    @DisplayName("copyForNewSrc_영구_필드만_복사_transient_필드_null")
    void copyForNewSrcCopiesPersistentFieldsOnly() {
        // given — 원본: DB 에서 로드한 상태를 시뮬레이션 (transient 필드는 null)
        LsDataLbl original = LsDataLbl.createAutoBbox(
                100L, 7L, "person", "[[10,20,30,40]]", BigDecimal.valueOf(0.85), "track-1");

        // when
        LsDataLbl copy = LsDataLbl.copyForNewSrc(999L, original);

        // then — 영구 필드 복사
        assertThat(copy.getSrcSn()).isEqualTo(999L);
        assertThat(copy.getLblTypeCd()).isEqualTo("BBOX");
        assertThat(copy.getLabelId()).isEqualTo(7L);
        assertThat(copy.getLabelNm()).isEqualTo("person");
        assertThat(copy.getPointCn()).isEqualTo("[[10,20,30,40]]");
        assertThat(copy.getTrackId()).isEqualTo("track-1");

        // then — transient 필드 (DB 미저장)
        assertThat(copy.getAutoLblYn()).isNull();
        assertThat(copy.getConfScore()).isNull();
        assertThat(copy.getLblSrcCd()).isNull();
        assertThat(copy.getDataAugSn()).isNull();

        // then — 새 row 이므로 lblSn 미할당
        assertThat(copy.getLblSn()).isNull();
        assertThat(copy.getRegDt()).isNotNull();
    }

    @Test
    @DisplayName("copyForNewSrc_original_null이면_예외")
    void copyForNewSrcRejectsNullOriginal() {
        assertThatThrownBy(() -> LsDataLbl.copyForNewSrc(999L, null))
                .isInstanceOf(CustomException.class);
    }

    // --- Phase 1 (해상도 파생영상): copyForNewSrcScaled ---

    @Test
    @DisplayName("copyForNewSrcScaled_좌표를_해상도비율로_스케일_복사")
    void copyForNewSrcScaledRescalesCoordinates() {
        // given — 원본 BBOX 코너 좌표
        LsDataLbl original = LsDataLbl.createAutoBbox(
                100L, 7L, "person", "[[10,20],[30,40]]", BigDecimal.valueOf(0.85), "track-1");

        // when — x 2배 / y 3배 파생영상으로 복사
        LsDataLbl copy = LsDataLbl.copyForNewSrcScaled(999L, original, 2.0, 3.0);

        // then — 좌표만 리스케일, 그 외 필드는 copyForNewSrc 와 동일하게 복제
        assertThat(copy.getSrcSn()).isEqualTo(999L);
        assertThat(copy.getPointCn()).isEqualTo("[[20,60],[60,120]]");
        assertThat(copy.getLblTypeCd()).isEqualTo("BBOX");
        assertThat(copy.getLabelId()).isEqualTo(7L);
        assertThat(copy.getLabelNm()).isEqualTo("person");
        assertThat(copy.getTrackId()).isEqualTo("track-1");
        assertThat(copy.getLblSn()).isNull();
        assertThat(copy.getRegDt()).isNotNull();
    }

    @Test
    @DisplayName("copyForNewSrcScaled_scale_1이면_copyForNewSrc_등가")
    void copyForNewSrcScaledIdentityEqualsCopyForNewSrc() {
        LsDataLbl original = LsDataLbl.createManual(1L, "POLYGON", 5L, "car", "[[1,2],[3,4]]", 99L);

        LsDataLbl scaled = LsDataLbl.copyForNewSrcScaled(999L, original, 1.0, 1.0);
        LsDataLbl plain = LsDataLbl.copyForNewSrc(999L, original);

        assertThat(scaled.getPointCn()).isEqualTo(plain.getPointCn());
        assertThat(scaled.getPointCn()).isEqualTo("[[1,2],[3,4]]");
    }

    @Test
    @DisplayName("copyForNewSrcScaled_SKELETON_가시성값은_불변")
    void copyForNewSrcScaledKeepsVisibility() {
        LsDataLbl original = LsDataLbl.createManual(
                1L, "SKELETON", 5L, "person", "[[10,20,2],[30,40,0]]", 99L);

        LsDataLbl copy = LsDataLbl.copyForNewSrcScaled(999L, original, 2.0, 2.0);

        assertThat(copy.getPointCn()).isEqualTo("[[20,40,2],[60,80,0]]");
    }

    @Test
    @DisplayName("copyForNewSrcScaled_original_null이면_예외")
    void copyForNewSrcScaledRejectsNullOriginal() {
        assertThatThrownBy(() -> LsDataLbl.copyForNewSrcScaled(999L, null, 2.0, 2.0))
                .isInstanceOf(CustomException.class);
    }

    @Test
    @DisplayName("copyForNewSrcScaled_원본_pointCn_null이면_결과도_null_정상전파")
    void copyForNewSrcScaledPropagatesNullPointCn() {
        // given — 좌표(pointCn)가 null 인 원본 라벨
        LsDataLbl original = LsDataLbl.createManual(1L, "BBOX", 5L, "car", null, 99L);

        // when — 스케일 복사
        LsDataLbl copy = LsDataLbl.copyForNewSrcScaled(999L, original, 2.0, 3.0);

        // then — pointCn 은 null 그대로 전파, 그 외 필드는 정상 복제
        assertThat(copy.getPointCn()).isNull();
        assertThat(copy.getSrcSn()).isEqualTo(999L);
        assertThat(copy.getLblTypeCd()).isEqualTo("BBOX");
        assertThat(copy.getLabelId()).isEqualTo(5L);
    }

    @Test
    @DisplayName("updateUserContent_labelId_null_전달_시_기존_LABEL_ID_유지")
    void updateUserContentNullLabelIdPreservesExisting() {
        LsDataLbl lbl = LsDataLbl.createManual(1L, "BBOX", 5L, "person", "[]", 99L);

        lbl.updateUserContent("BBOX", null, "person-v2", "[[1,1]]");

        assertThat(lbl.getLabelId()).isEqualTo(5L);  // labelId=null → 기존 유지
        assertThat(lbl.getLabelNm()).isEqualTo("person-v2");
    }
}
