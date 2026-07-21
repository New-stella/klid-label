package kr.co.cudo.authoring.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 1 (해상도 파생영상) — {@link LabelCoordinateScaler} 순수 스케일 로직 테스트.
 *
 * <p>원본 라벨을 저/고해상도 파생영상으로 복사할 때 좌표를 해상도 비율로 스케일한다.
 * 축소(scale&lt;1)와 확대(scale&gt;1) 양방향, 실제 존재하는 모든 POINT_CN 포맷을 커버한다.
 * (nested {@code [[x,y],...]} / object {@code [{"x":..,"y":..},...]} / flat {@code [x,y,x,y,...]}
 *  / SKELETON 삼중값 {@code [[x,y,v],...]})
 */
class LabelCoordinateScalerTest {

    private static final String BBOX = "BBOX";
    private static final String POLYGON = "POLYGON";
    private static final String SEGMENT = "SEGMENT";
    private static final String SKELETON = "SKELETON";

    @Test
    @DisplayName("BBOX_좌표가_scaleX_scaleY로_스케일된다")
    void bboxScaledByAxis() {
        // given — BBOX 대각 코너 정수 좌표 [[10,20],[30,40]] (write-time 정규화 포맷)
        String pointCn = "[[10,20],[30,40]]";

        // when — x 는 2배, y 는 3배
        String scaled = LabelCoordinateScaler.scalePointCn(pointCn, BBOX, 2.0, 3.0);

        // then — x 코너*2, y 코너*3 → w=x폭*2, h=y폭*3 이 자동 성립
        assertThat(scaled).isEqualTo("[[20,60],[60,120]]");
    }

    @Test
    @DisplayName("POLYGON_점열_전체가_홀짝인덱스로_스케일된다")
    void polygonAllPointsScaled() {
        // given — 폴리곤 정점 3개
        String pointCn = "[[0,0],[10,0],[10,10]]";

        // when
        String scaled = LabelCoordinateScaler.scalePointCn(pointCn, POLYGON, 2.0, 4.0);

        // then — 각 점의 x 는 scaleX, y 는 scaleY
        assertThat(scaled).isEqualTo("[[0,0],[20,0],[20,40]]");
    }

    @Test
    @DisplayName("SEGMENTATION_마스크폴리곤_좌표가_스케일된다")
    void segmentationScaled() {
        // given — SEGMENT 타입 마스크 폴리곤
        String pointCn = "[[100,200],[300,400]]";

        // when — 축소
        String scaled = LabelCoordinateScaler.scalePointCn(pointCn, SEGMENT, 0.5, 0.5);

        // then
        assertThat(scaled).isEqualTo("[[50,100],[150,200]]");
    }

    @Test
    @DisplayName("키포인트_x_y만_스케일되고_가시성값은_불변이다")
    void keypointVisibilityUnchanged() {
        // given — SKELETON 삼중값 [[x,y,v],...] (v: 0 미표기 / 1 비가시 / 2 가시)
        String pointCn = "[[10,20,2],[30,40,0],[50,60,1]]";

        // when — 큰 배율로 스케일해도 v 는 절대 스케일되지 않아야 한다
        String scaled = LabelCoordinateScaler.scalePointCn(pointCn, SKELETON, 2.0, 3.0);

        // then — x*2, y*3, v 그대로
        assertThat(scaled).isEqualTo("[[20,60,2],[60,120,0],[100,180,1]]");
    }

    @Test
    @DisplayName("확대_scale_1보다_클때_좌표가_비율대로_커진다")
    void upscaleGreaterThanOne() {
        // given
        String pointCn = "[[10,10],[20,20]]";

        // when — 3배 확대 (고해상도 파생)
        String scaled = LabelCoordinateScaler.scalePointCn(pointCn, BBOX, 3.0, 3.0);

        // then
        assertThat(scaled).isEqualTo("[[30,30],[60,60]]");
    }

    @Test
    @DisplayName("scaleX_scaleY_1이면_좌표가_그대로다")
    void scaleOneIsIdentity() {
        // given — 정수/실수 혼재 (증강 등가성 회귀 방어: scale=1 은 copyForNewSrc 등가)
        String intPointCn = "[[1,2],[3,4]]";
        String floatPointCn = "[[1.5,2.5],[3.0,4.0]]";

        // when
        String scaledInt = LabelCoordinateScaler.scalePointCn(intPointCn, BBOX, 1.0, 1.0);
        String scaledFloat = LabelCoordinateScaler.scalePointCn(floatPointCn, POLYGON, 1.0, 1.0);

        // then — 정수 포맷/실수 포맷 모두 원본 그대로 (정밀도 보존)
        assertThat(scaledInt).isEqualTo("[[1,2],[3,4]]");
        assertThat(scaledFloat).isEqualTo("[[1.5,2.5],[3.0,4.0]]");
    }

    @Test
    @DisplayName("pointCn_정규화_포맷들이_각각_정상_스케일된다")
    void allSupportedFormatsScaled() {
        // given/when/then — 정규 nested (정수)
        assertThat(LabelCoordinateScaler.scalePointCn("[[10,20],[30,40]]", BBOX, 0.5, 0.5))
                .isEqualTo("[[5,10],[15,20]]");

        // 객체 배열 레거시 [{"x":..,"y":..}, ...]
        assertThat(LabelCoordinateScaler.scalePointCn("[{\"x\":120,\"y\":80}]", BBOX, 0.5, 0.5))
                .isEqualTo("[{\"x\":60,\"y\":40}]");

        // 평탄 1차원 (실수) — 정밀도 보존
        assertThat(LabelCoordinateScaler.scalePointCn("[165.0,210.0,385.0,430.0]", BBOX, 2.0, 2.0))
                .isEqualTo("[330.0,420.0,770.0,860.0]");

        // 평탄 1차원 (정수) — round
        assertThat(LabelCoordinateScaler.scalePointCn("[10,20,30,40]", BBOX, 0.5, 0.5))
                .isEqualTo("[5,10,15,20]");
    }

    @Test
    @DisplayName("실수_포맷은_스케일후_정밀도가_보존된다")
    void floatPrecisionPreserved() {
        // given — 실수 좌표
        String pointCn = "[[1.5,2.5]]";

        // when — 2.5배 (실수 결과) → 반올림하지 않고 정밀도 보존
        String scaled = LabelCoordinateScaler.scalePointCn(pointCn, POLYGON, 2.0, 2.0);

        // then
        assertThat(scaled).isEqualTo("[[3.0,5.0]]");
    }

    @Test
    @DisplayName("빈_좌표_null_pointCn은_예외없이_통과한다")
    void emptyAndNullPassThrough() {
        // null → null
        assertThat(LabelCoordinateScaler.scalePointCn(null, BBOX, 2.0, 2.0)).isNull();
        // 빈 배열 → 그대로
        assertThat(LabelCoordinateScaler.scalePointCn("[]", BBOX, 2.0, 2.0)).isEqualTo("[]");
        // 공백 문자열 → 예외 없이 통과
        assertThatCode(() -> LabelCoordinateScaler.scalePointCn("", BBOX, 2.0, 2.0))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("리스케일_반올림_경계값_0px_1px가_음수가_아니다")
    void roundingBoundaryNonNegative() {
        // given — 0px / 1px 경계 좌표
        String pointCn = "[[0,0],[1,1]]";

        // when — 축소 시 round(0)=0, round(0.5)=1 → 음수 없음
        String scaled = LabelCoordinateScaler.scalePointCn(pointCn, BBOX, 0.5, 0.5);

        // then
        assertThat(scaled).isEqualTo("[[0,0],[1,1]]");
        assertThat(scaled).doesNotContain("-");
    }

    @Test
    @DisplayName("기형_JSON은_원문노출없이_컨트롤된_예외로_거부된다")
    void malformedJsonFailsSecure() {
        // given — 기형/악의적 입력 (JSON 아님)
        String malicious = "'; DROP TABLE ls_data_lbl; --";

        // when / then — NPE 등 통제불가 크래시가 아닌 IllegalArgumentException(fail-secure),
        //               예외 메시지에 원문 미노출 (CWE-117/209)
        assertThatThrownBy(() -> LabelCoordinateScaler.scalePointCn(malicious, BBOX, 2.0, 2.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("DROP TABLE");
    }

    @Test
    @DisplayName("배열이_아닌_JSON은_거부된다")
    void nonArrayJsonRejected() {
        assertThatThrownBy(() -> LabelCoordinateScaler.scalePointCn("{\"x\":1}", BBOX, 2.0, 2.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- 보강: fail-secure 방어 분기 (핵심 계약 미테스트 갭) ---

    @Test
    @DisplayName("정규_nested_쌍이_2요소가_아니면_거부")
    void nestedPairWrongArityRejected() {
        // given/when/then — 3요소·1요소 nested 쌍은 [x,y] 계약 위반으로 거부(원문 미노출)
        assertThatThrownBy(() -> LabelCoordinateScaler.scalePointCn("[[1,2,3]]", BBOX, 2.0, 2.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("1")
                .hasMessageNotContaining("2");
        assertThatThrownBy(() -> LabelCoordinateScaler.scalePointCn("[[1]]", BBOX, 2.0, 2.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("객체배열_x_또는_y키_누락_또는_원소가_객체아님이면_거부")
    void objectPairMissingKeyOrNonObjectRejected() {
        // x 만 있고 y 누락
        assertThatThrownBy(() -> LabelCoordinateScaler.scalePointCn("[{\"x\":1}]", BBOX, 2.0, 2.0))
                .isInstanceOf(IllegalArgumentException.class);
        // 첫 원소는 객체이나 뒤 원소가 객체가 아님
        assertThatThrownBy(() -> LabelCoordinateScaler.scalePointCn("[{\"x\":1,\"y\":2},5]", BBOX, 2.0, 2.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("객체배열_x_y_외_추가필드는_스케일없이_보존된다")
    void objectPairExtraFieldPreserved() {
        // given — id 같은 비좌표 필드
        String pointCn = "[{\"x\":1,\"y\":2,\"id\":\"a\"}]";

        // when — x/y 만 2배, id 는 원문 유지
        String scaled = LabelCoordinateScaler.scalePointCn(pointCn, BBOX, 2.0, 2.0);

        // then
        assertThat(scaled).isEqualTo("[{\"x\":2,\"y\":4,\"id\":\"a\"}]");
    }

    @Test
    @DisplayName("평탄_홀수길이_배열은_거부")
    void flatOddLengthRejected() {
        assertThatThrownBy(() -> LabelCoordinateScaler.scalePointCn("[1,2,3]", BBOX, 2.0, 2.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("SKELETON_삼중값_요소수_불일치는_거부")
    void skeletonTripletWrongArityRejected() {
        assertThatThrownBy(() -> LabelCoordinateScaler.scalePointCn("[[1,2]]", SKELETON, 2.0, 2.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LabelCoordinateScaler.scalePointCn("[[1,2,3,4]]", SKELETON, 2.0, 2.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("SKELETON_가시성이_비숫자면_거부")
    void skeletonNonNumericVisibilityRejected() {
        assertThatThrownBy(() -> LabelCoordinateScaler.scalePointCn("[[1,2,\"x\"]]", SKELETON, 2.0, 2.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("좌표_요소가_비숫자_또는_null이면_거부")
    void coordinateElementNonNumericRejected() {
        assertThatThrownBy(() -> LabelCoordinateScaler.scalePointCn("[[\"a\",\"b\"]]", BBOX, 2.0, 2.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LabelCoordinateScaler.scalePointCn("[[null,1]]", BBOX, 2.0, 2.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("최상위_원소가_인식불가_형식이면_거부")
    void topLevelUnrecognizedElementRejected() {
        assertThatThrownBy(() -> LabelCoordinateScaler.scalePointCn("[\"a\",\"b\"]", BBOX, 2.0, 2.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LabelCoordinateScaler.scalePointCn("[true,false]", BBOX, 2.0, 2.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LabelCoordinateScaler.scalePointCn("[null]", BBOX, 2.0, 2.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("내부_공백_빈_배열은_무변경_통과")
    void whitespaceEmptyArrayPassThrough() {
        // given/when — "[ ]" 는 빈 배열로 파싱되어 무변경 반환
        String scaled = LabelCoordinateScaler.scalePointCn("[ ]", BBOX, 2.0, 2.0);

        // then — 원본 그대로 (예외 없음)
        assertThat(scaled).isEqualTo("[ ]");
    }

    @Test
    @DisplayName("배율이_0_음수_NaN_Infinity면_원문노출없이_거부된다")
    void invalidScaleRejected() {
        // given — 좌표는 정상이나 배율이 비정상 (Phase 2 에서 srcW=0 시 NaN/Infinity 전파 차단)
        String pointCn = "[[10,20],[30,40]]";

        // when / then — 0/음수/NaN/Infinity 배율은 IllegalArgumentException, 배율 원문 미노출
        assertThatThrownBy(() -> LabelCoordinateScaler.scalePointCn(pointCn, BBOX, 0.0, 1.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LabelCoordinateScaler.scalePointCn(pointCn, BBOX, 1.0, 0.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LabelCoordinateScaler.scalePointCn(pointCn, BBOX, -2.0, 1.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LabelCoordinateScaler.scalePointCn(pointCn, BBOX, Double.NaN, 1.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("NaN");
        assertThatThrownBy(() -> LabelCoordinateScaler.scalePointCn(pointCn, BBOX, 1.0, Double.POSITIVE_INFINITY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("Infinity");
    }

    @Test
    @DisplayName("배율_가드는_null_또는_빈_pointCn에도_선적용된다")
    void scaleGuardAppliedBeforeNullShortCircuit() {
        // given/when/then — null/빈 입력이라도 비정상 배율이면 fail-secure 로 거부(진입부 가드)
        assertThatThrownBy(() -> LabelCoordinateScaler.scalePointCn(null, BBOX, 0.0, 1.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LabelCoordinateScaler.scalePointCn("[]", BBOX, Double.NaN, 1.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- 보강: 프로덕션 대표 double 경로 정밀도 (LabelPointSerializer.toJson 은 항상 double) ---

    @Test
    @DisplayName("프로덕션_double포맷_축소시_소수좌표_정밀도가_보존된다")
    void productionDoubleFormatDownscalePrecision() {
        // given — 실제 저장 포맷(LabelPointSerializer.toJson)은 항상 double 리터럴 [[10.0,20.0],...]
        String pointCn = "[[10.0,20.0],[31.0,41.0]]";

        // when — 0.5 축소 (double 곱 경로: 정수 반올림 경로가 아님)
        //        정확히 표현 가능한 배율을 써 JDK Double.toString 편차 없이 정밀도 계약만 검증
        String scaled = LabelCoordinateScaler.scalePointCn(pointCn, BBOX, 0.5, 0.5);

        // then — 소수 좌표(15.5, 20.5)가 반올림 정수화 없이 보존됨 (정수 경로였다면 16, 20 이 됐을 것)
        //        31.0*0.5=15.5, 41.0*0.5=20.5
        assertThat(scaled).isEqualTo("[[5.0,10.0],[15.5,20.5]]");
    }
}
