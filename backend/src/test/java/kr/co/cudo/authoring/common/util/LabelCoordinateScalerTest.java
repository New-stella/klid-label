package kr.co.cudo.authoring.common.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.common.exception.CustomException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 2 — 라벨 좌표 스케일 유틸 테스트.
 *
 * <p>RESOLUTION 증강(해상도 변경) 시 원본 영상을 단일 factor 로 리사이즈하면서
 * 라벨 좌표(절대 픽셀)를 동일 factor 로 스케일한다 (종횡비 보존 → scaleX==scaleY==factor).
 *
 * <p>POINT_CN 포맷은 LBL_TYPE_CD 별로 이원화:
 * <ul>
 *   <li>BBOX: flat 배열 {@code [x1,y1,x2,y2]}</li>
 *   <li>POLYGON/SEGMENT/TRACK: nested 배열 {@code [[x,y],[x,y],...]}</li>
 * </ul>
 */
class LabelCoordinateScalerTest {

    private final LabelCoordinateScaler scaler = new LabelCoordinateScaler(new ObjectMapper());

    @Test
    @DisplayName("BBOX_0.5배_스케일_좌표_검증")
    void scaleBboxHalf() {
        // given — flat 배열 [x1,y1,x2,y2]
        String pointCn = "[100,50,300,400]";

        // when — factor 0.5, 타겟 960x540
        String scaled = scaler.scale(pointCn, LsDataLblTypes.BBOX, 0.5, 960, 540);

        // then — 각 좌표 * 0.5
        assertThat(scaled).isEqualTo("[50,25,150,200]");
    }

    @Test
    @DisplayName("POLYGON_nested_좌표_0.5배_스케일_검증")
    void scalePolygonNestedHalf() {
        // given — nested 배열 [[x,y],[x,y]]
        String pointCn = "[[100,50],[300,400]]";

        // when
        String scaled = scaler.scale(pointCn, LsDataLblTypes.POLYGON, 0.5, 960, 540);

        // then
        assertThat(scaled).isEqualTo("[[50,25],[150,200]]");
    }

    @Test
    @DisplayName("SEGMENT_nested_좌표_스케일_검증")
    void scaleSegmentNested() {
        // given
        String pointCn = "[[200,100],[400,300]]";

        // when — factor 0.5
        String scaled = scaler.scale(pointCn, LsDataLblTypes.SEGMENT, 0.5, 960, 540);

        // then
        assertThat(scaled).isEqualTo("[[100,50],[200,150]]");
    }

    @Test
    @DisplayName("TRACK_nested_좌표_스케일_검증")
    void scaleTrackNested() {
        // given — TRACK 도 nested 동일 처리
        String pointCn = "[[10,10],[20,20],[30,40]]";

        // when — factor 2.0 (확대)
        String scaled = scaler.scale(pointCn, LsDataLblTypes.TRACK, 2.0, 1920, 1080);

        // then
        assertThat(scaled).isEqualTo("[[20,20],[40,40],[60,80]]");
    }

    @Test
    @DisplayName("클램프_targetW_초과좌표_경계로_보정")
    void clampExceedingTargetBounds() {
        // given — 스케일 후 x=1100 > maxW=960, y=600 > maxH=540
        String pointCn = "[[2200,1200],[100,50]]";

        // when — factor 0.5 → [[1100,600],[50,25]]
        String scaled = scaler.scale(pointCn, LsDataLblTypes.POLYGON, 0.5, 960, 540);

        // then — x 는 960, y 는 540 으로 클램프
        assertThat(scaled).isEqualTo("[[960,540],[50,25]]");
    }

    @Test
    @DisplayName("클램프_BBOX_초과좌표_경계로_보정")
    void clampBboxExceedingBounds() {
        // given — 스케일 후 x2=1000 > maxW=960
        String pointCn = "[100,50,2000,200]";

        // when — factor 0.5 → [50,25,1000,100]
        String scaled = scaler.scale(pointCn, LsDataLblTypes.BBOX, 0.5, 960, 540);

        // then — x2 클램프 960
        assertThat(scaled).isEqualTo("[50,25,960,100]");
    }

    @Test
    @DisplayName("클램프_음수_좌표_0으로_보정")
    void clampNegativeToZero() {
        // given — 음수 좌표
        String pointCn = "[[-10,-5],[100,50]]";

        // when
        String scaled = scaler.scale(pointCn, LsDataLblTypes.POLYGON, 1.0, 960, 540);

        // then — 음수는 0 으로 클램프
        assertThat(scaled).isEqualTo("[[0,0],[100,50]]");
    }

    @Test
    @DisplayName("factor_1.0_무변환_idempotent")
    void factorOneIdempotent() {
        // given
        String bbox = "[100,50,300,400]";
        String polygon = "[[100,50],[300,400]]";

        // when — factor 1.0
        String scaledBbox = scaler.scale(bbox, LsDataLblTypes.BBOX, 1.0, 960, 540);
        String scaledPolygon = scaler.scale(polygon, LsDataLblTypes.POLYGON, 1.0, 960, 540);

        // then — 좌표 불변 (정수화만)
        assertThat(scaledBbox).isEqualTo("[100,50,300,400]");
        assertThat(scaledPolygon).isEqualTo("[[100,50],[300,400]]");
    }

    @Test
    @DisplayName("반올림_경계_BBOX_Math_round_적용")
    void roundingBoundaryBbox() {
        // given — 101 * 0.5 = 50.5 → round = 51, 51*0.5=25.5 → 26
        String pointCn = "[101,51,301,401]";

        // when
        String scaled = scaler.scale(pointCn, LsDataLblTypes.BBOX, 0.5, 960, 540);

        // then — Math.round: 50.5→51, 25.5→26, 150.5→151, 200.5→201
        assertThat(scaled).isEqualTo("[51,26,151,201]");
    }

    @Test
    @DisplayName("반올림_경계_nested_Math_round_적용")
    void roundingBoundaryNested() {
        // given — 33 * 0.5 = 16.5 → 17
        String pointCn = "[[33,15],[101,51]]";

        // when
        String scaled = scaler.scale(pointCn, LsDataLblTypes.SEGMENT, 0.5, 960, 540);

        // then — 16.5→17, 7.5→8, 50.5→51, 25.5→26
        assertThat(scaled).isEqualTo("[[17,8],[51,26]]");
    }

    @Test
    @DisplayName("잘못된_POINT_CN_JSON_예외_처리")
    void invalidJsonThrows() {
        // given — 깨진 JSON
        String broken = "[[100,50],[300,";

        // when / then — INVALID_INPUT 예외
        assertThatThrownBy(() -> scaler.scale(broken, LsDataLblTypes.POLYGON, 0.5, 960, 540))
                .isInstanceOf(CustomException.class);
    }

    @Test
    @DisplayName("null_POINT_CN_예외_처리")
    void nullPointCnThrows() {
        assertThatThrownBy(() -> scaler.scale(null, LsDataLblTypes.BBOX, 0.5, 960, 540))
                .isInstanceOf(CustomException.class);
    }

    @Test
    @DisplayName("빈문자_POINT_CN_예외_처리")
    void blankPointCnThrows() {
        assertThatThrownBy(() -> scaler.scale("   ", LsDataLblTypes.BBOX, 0.5, 960, 540))
                .isInstanceOf(CustomException.class);
    }

    @Test
    @DisplayName("BBOX_홀수_길이_배열_예외_처리")
    void bboxOddLengthThrows() {
        // given — flat 배열인데 홀수 길이
        String odd = "[100,50,300]";

        assertThatThrownBy(() -> scaler.scale(odd, LsDataLblTypes.BBOX, 0.5, 960, 540))
                .isInstanceOf(CustomException.class);
    }

    @Test
    @DisplayName("nested_좌표쌍_길이_불일치_예외_처리")
    void nestedInvalidPairThrows() {
        // given — [x,y] 가 아닌 [x,y,z]
        String bad = "[[100,50,99],[300,400]]";

        assertThatThrownBy(() -> scaler.scale(bad, LsDataLblTypes.POLYGON, 0.5, 960, 540))
                .isInstanceOf(CustomException.class);
    }

    @Test
    @DisplayName("BBOX_nested_포맷_수동라벨_스케일_검증")
    void scaleBboxNestedManualLabel() {
        // given — 사용자 수동 BBOX (LabelPointSerializer 산출 = nested [[x,y],[x,y]])
        String pointCn = "[[100,50],[300,400]]";

        // when — LBL_TYPE_CD=BBOX 이지만 nested 포맷 입력
        String scaled = scaler.scale(pointCn, LsDataLblTypes.BBOX, 0.5, 960, 540);

        // then — nested 로 스케일 + 입력 포맷(nested) 보존
        assertThat(scaled).isEqualTo("[[50,25],[150,200]]");
    }

    @Test
    @DisplayName("빈배열_입력시_빈배열_반환")
    void emptyArrayReturnsEmptyArray() {
        // given — 미검출(빈 좌표)
        String empty = "[]";

        // when / then — BBOX/POLYGON 모두 빈 배열 그대로 반환 (예외 아님)
        assertThat(scaler.scale(empty, LsDataLblTypes.BBOX, 0.5, 960, 540)).isEqualTo("[]");
        assertThat(scaler.scale(empty, LsDataLblTypes.POLYGON, 0.5, 960, 540)).isEqualTo("[]");
    }

    /** 테스트 가독성용 LBL_TYPE_CD 상수 alias. */
    private static final class LsDataLblTypes {
        static final String BBOX = "BBOX";
        static final String POLYGON = "POLYGON";
        static final String SEGMENT = "SEGMENT";
        static final String TRACK = "TRACK";
    }
}
