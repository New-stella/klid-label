package kr.co.cudo.authoring.common.util;

import java.util.List;
import java.util.Optional;

/**
 * C-ISSUE-41 — 외부(ai-server) YOLO 검출 BBOX 좌표 정규화 <b>단일 규칙</b>.
 *
 * <h3>왜 공용 유틸인가</h3>
 * 같은 모델·같은 프레임의 응답을 <b>배치는 무검증 저장하고 온라인만 400 으로 전량 거부</b>하던 정책
 * 비대칭이 결함의 본질이었다(실측: 온라인 5프레임 중 4프레임 400, 반면 {@code LS_DATA_LBL} 에는 배치가
 * 넣은 음수 좌표 라벨이 실존). 규칙을 여기 한 곳에 두고 배치({@code YoloLabelPersister})와 온라인
 * ({@code AutolabelOnlineService})이 <b>같은 함수를 호출</b>해 비대칭을 제거한다.
 *
 * <h3>규칙</h3>
 * <ol>
 *   <li><b>clamp</b> — {@code 0 ≤ x ≤ imgWidth}, {@code 0 ≤ y ≤ imgHeight}. 화면 경계에 걸친 객체
 *       (사람이 프레임 왼쪽 끝에 반쯤 걸림 등)는 CCTV 학습데이터의 <b>정상 다수 케이스</b>이고 모델이
 *       경계를 조금 넘겨 출력하는 것도 정상이다. 캔버스도 클램프를 전제로 그린다.</li>
 *   <li><b>거부(예외)는 형식 위반에만</b> — 좌표 개수 ≠ 4, null 원소, NaN/Infinity. 호출부는 이 경우에만
 *       all-or-nothing 으로 전체를 거부한다(부분 반환 금지).
 *       <p>{@code NaN < 0} 은 {@code false} 라 음수 검사를 통과한다 — {@link Double#isFinite} 가드가
 *       없으면 저장 후 좌표 역직렬화에서 500 을 유발한다(#5 / F-4 회귀).</li>
 *   <li><b>퇴화 박스는 예외가 아니라 스킵</b>({@link Optional#empty()}) — clamp 후 폭·높이가 0 이하면
 *       (박스 전체가 이미지 밖이거나 좌표 순서가 역전된 경우) 저장할 수 없는 검출이다. 그렇다고 400 으로
 *       전체를 거부하면 <b>같은 프레임의 정상 검출까지 폐기</b>되어 이 이슈가 고치려는 가용성 문제가 그대로
 *       남고, 배치에서는 영상 1건의 오토라벨이 통째로 실패한다. 그래서 <b>해당 검출만</b> 제외한다.</li>
 * </ol>
 *
 * <p>순수 함수다 — 로깅·예외 매핑(400/스킵 카운트)은 호출부 책임이다({@code common.util} 규약에 맞춰
 * {@link IllegalArgumentException} 만 던진다).
 */
public final class DetectionBoxNormalizer {

    /** BBOX 평탄 좌표 개수 [x1,y1,x2,y2]. */
    public static final int BBOX_POINT_COUNT = 4;

    private DetectionBoxNormalizer() {}

    /**
     * 검출 BBOX 좌표를 이미지 경계로 clamp 한다.
     *
     * @param points 외부 응답 평탄 좌표 [x1,y1,x2,y2]
     * @param bounds 이미지 실측 [width, height]. {@code null}·비정상(길이≠2, 0 이하)이면 <b>상한 없음</b>으로
     *               취급하고 하한(0) clamp 만 적용한다 — 치수 측정 실패(fail-open) 시 정상 좌표를 잘라내지
     *               않기 위함이다({@code FrameBoundsResolver} 정책과 동일).
     * @return clamp 된 좌표. clamp 후 퇴화(폭·높이 0 이하)면 {@link Optional#empty()}(해당 검출만 스킵)
     * @throws IllegalArgumentException 좌표 개수 ≠ 4 · null 원소 · NaN/Infinity
     */
    public static Optional<List<Double>> normalizeBbox(List<Double> points, int[] bounds) {
        if (points == null || points.size() != BBOX_POINT_COUNT) {
            throw new IllegalArgumentException("YOLO 응답 좌표는 [x1,y1,x2,y2] 4개여야 합니다.");
        }
        for (Double v : points) {
            if (v == null || !Double.isFinite(v)) {
                throw new IllegalArgumentException("YOLO 응답 좌표는 유한한 수여야 합니다.");
            }
        }
        double maxX = upperBound(bounds, 0);
        double maxY = upperBound(bounds, 1);
        double x1 = clamp(points.get(0), maxX);
        double y1 = clamp(points.get(1), maxY);
        double x2 = clamp(points.get(2), maxX);
        double y2 = clamp(points.get(3), maxY);
        if (x2 <= x1 || y2 <= y1) {
            return Optional.empty();
        }
        return Optional.of(List.of(x1, y1, x2, y2));
    }

    /** 축별 상한. bounds 미상/비정상이면 상한 없음({@link Double#MAX_VALUE}). */
    private static double upperBound(int[] bounds, int axis) {
        if (bounds == null || bounds.length != 2 || bounds[axis] <= 0) {
            return Double.MAX_VALUE;
        }
        return bounds[axis];
    }

    private static double clamp(double v, double max) {
        return Math.max(0.0, Math.min(max, v));
    }
}
