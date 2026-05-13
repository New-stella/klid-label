package kr.co.cudo.authoring.batch.interpolation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * 트랙 보간 알고리즘 단위 테스트.
 *
 * <p>CVAT BBOX 선형 보간 — outside 마커 이후 propagate 안 함 / 마지막 키프레임 이후 propagate 안 함.
 * 원본: {@code docs/analysis/portable-modules/01-track-interpolation.md}</p>
 */
class TrackInterpolatorTest {

    private static final double EPS = 1e-6;

    private final TrackInterpolator interpolator = new TrackInterpolator();

    @Test
    @DisplayName("TrackInterpolator_빈_키프레임은_빈_결과")
    void emptyKeyframesProduceEmptyResult() {
        Map<Integer, Bbox> result = interpolator.interpolate(List.of(), 100);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("TrackInterpolator_단일_키프레임은_그_프레임만_반환")
    void singleKeyframeReturnsOnlyThatFrame() {
        Bbox box = new Bbox(0, 0, 50, 50);
        List<Keyframe> kfs = List.of(new Keyframe(3, box, false));

        Map<Integer, Bbox> result = interpolator.interpolate(kfs, 100);

        assertThat(result).hasSize(1);
        assertThat(result).containsKey(3);
        assertThat(result.get(3)).isEqualTo(box);
    }

    @Test
    @DisplayName("TrackInterpolator_두_키프레임_5_10_사이_6_7_8_9_프레임_선형_보간")
    void twoKeyframesInterpolateBetween() {
        Bbox a = new Bbox(0, 0, 100, 100);
        Bbox b = new Bbox(100, 100, 200, 200);
        List<Keyframe> kfs = List.of(
                new Keyframe(5, a, false),
                new Keyframe(10, b, false)
        );

        Map<Integer, Bbox> result = interpolator.interpolate(kfs, 100);

        // 키프레임 2개 + 사이 4프레임(6,7,8,9) = 6개
        assertThat(result).hasSize(6);
        assertThat(result).containsKeys(5, 6, 7, 8, 9, 10);

        // frame=5,10 키프레임 자체
        assertThat(result.get(5)).isEqualTo(a);
        assertThat(result.get(10)).isEqualTo(b);

        // frame=7 (t = (7-5)/5 = 0.4) → Bbox(40, 40, 140, 140)
        Bbox at7 = result.get(7);
        assertThat(at7.left()).isCloseTo(40.0, within(EPS));
        assertThat(at7.top()).isCloseTo(40.0, within(EPS));
        assertThat(at7.right()).isCloseTo(140.0, within(EPS));
        assertThat(at7.bottom()).isCloseTo(140.0, within(EPS));

        // frame=6 (t = 0.2) → Bbox(20, 20, 120, 120)
        Bbox at6 = result.get(6);
        assertThat(at6.left()).isCloseTo(20.0, within(EPS));
        assertThat(at6.bottom()).isCloseTo(120.0, within(EPS));
    }

    @Test
    @DisplayName("TrackInterpolator_세_키프레임_각_구간_독립_보간")
    void threeKeyframesInterpolatePerSegment() {
        // 0..2: (0,0,10,10) → (10,10,20,20) (직선)
        // 2..6: (10,10,20,20) → (50,50,60,60) (4프레임 사이)
        List<Keyframe> kfs = List.of(
                new Keyframe(0, new Bbox(0, 0, 10, 10), false),
                new Keyframe(2, new Bbox(10, 10, 20, 20), false),
                new Keyframe(6, new Bbox(50, 50, 60, 60), false)
        );

        Map<Integer, Bbox> result = interpolator.interpolate(kfs, 100);

        // 0,1,2,3,4,5,6 = 7개
        assertThat(result).hasSize(7);

        // frame=1 (첫 구간 중점, t=0.5): (5,5,15,15)
        Bbox at1 = result.get(1);
        assertThat(at1.left()).isCloseTo(5.0, within(EPS));
        assertThat(at1.right()).isCloseTo(15.0, within(EPS));

        // frame=4 (둘째 구간 t = (4-2)/4 = 0.5): (10 + 40*0.5, ..., 20 + 40*0.5, ...) = (30,30,40,40)
        Bbox at4 = result.get(4);
        assertThat(at4.left()).isCloseTo(30.0, within(EPS));
        assertThat(at4.top()).isCloseTo(30.0, within(EPS));
        assertThat(at4.right()).isCloseTo(40.0, within(EPS));
        assertThat(at4.bottom()).isCloseTo(40.0, within(EPS));
    }

    @Test
    @DisplayName("TrackInterpolator_outside_키프레임은_보간_대상_외이며_이후_중단")
    void outsideKeyframeStopsProcessing() {
        // 첫 키프레임이 outside=true → 어떤 frame 도 결과에 없음
        List<Keyframe> kfs = List.of(
                new Keyframe(0, new Bbox(0, 0, 10, 10), true),
                new Keyframe(5, new Bbox(20, 20, 30, 30), false)
        );

        Map<Integer, Bbox> result = interpolator.interpolate(kfs, 100);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("TrackInterpolator_다음_키프레임이_outside_면_현재만_저장_후_종료")
    void nextOutsideStoresCurrentOnlyAndStops() {
        // 0번 키프레임 저장 → 다음(5)이 outside → 사이 보간 안 함, 종료
        List<Keyframe> kfs = List.of(
                new Keyframe(0, new Bbox(0, 0, 10, 10), false),
                new Keyframe(5, new Bbox(100, 100, 200, 200), true),
                new Keyframe(10, new Bbox(300, 300, 400, 400), false)
        );

        Map<Integer, Bbox> result = interpolator.interpolate(kfs, 100);

        assertThat(result).hasSize(1);
        assertThat(result).containsKey(0);
        assertThat(result).doesNotContainKeys(1, 2, 3, 4, 5, 10);
    }

    @Test
    @DisplayName("TrackInterpolator_마지막_키프레임_이후_propagate_안_함")
    void noPropagateAfterLastKeyframe() {
        // 마지막 키프레임 frame=10, totalFrames=100 — 11..99 는 없어야 함
        List<Keyframe> kfs = List.of(
                new Keyframe(5, new Bbox(0, 0, 10, 10), false),
                new Keyframe(10, new Bbox(20, 20, 30, 30), false)
        );

        Map<Integer, Bbox> result = interpolator.interpolate(kfs, 100);

        // 5..10 의 6개만 있어야 함
        assertThat(result).hasSize(6);
        assertThat(result.keySet()).allMatch(f -> f >= 5 && f <= 10);
        assertThat(result).doesNotContainKeys(11, 50, 99);
    }

    @Test
    @DisplayName("TrackInterpolator_같은_프레임_키프레임_두_개는_뒤_값_우선")
    void duplicateFrameKeyframeLastWins() {
        Bbox first = new Bbox(0, 0, 10, 10);
        Bbox second = new Bbox(100, 100, 200, 200);
        List<Keyframe> kfs = List.of(
                new Keyframe(5, first, false),
                new Keyframe(5, second, false)
        );

        Map<Integer, Bbox> result = interpolator.interpolate(kfs, 100);

        // 같은 frame=5 — 뒤가 우선
        assertThat(result).hasSize(1);
        assertThat(result.get(5)).isEqualTo(second);
    }

    @Test
    @DisplayName("TrackInterpolator_null_keyframes_입력_시_NullPointerException")
    void nullKeyframesRejected() {
        assertThatThrownBy(() -> interpolator.interpolate(null, 100))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("TrackInterpolator_totalFrames_음수면_IllegalArgumentException")
    void negativeTotalFramesRejected() {
        assertThatThrownBy(() -> interpolator.interpolate(List.of(), -1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
