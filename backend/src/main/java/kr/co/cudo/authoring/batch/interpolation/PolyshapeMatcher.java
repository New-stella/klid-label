package kr.co.cudo.authoring.batch.interpolation;

import kr.co.cudo.authoring.common.util.Point;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 폴리곤/폴리라인 정점 대응(vertex correspondence) 계산기 — CVAT polyshape 포팅.
 *
 * <p>두 키프레임의 정점 목록을 받아 대응쌍(from → to)을 산출한다. 정점 개수가 달라도
 * 순서 보존(monotone) 거리기반 대응으로 좌표 꼬임(crossing) 없이 매핑한다.</p>
 *
 * <ul>
 *   <li><b>폐곡선(POLYGON, closed=true)</b>: 시작 정점·winding 방향이 모두 임의라
 *       <b>정방향+역방향(reflection) 각각 최소비용 회전 오프셋</b>을 탐색해 전체 최소를 채택한다.
 *       시작=끝 중복점(닫는 정점)은 매핑 전 정규화(마지막점==첫점이면 제거).</li>
 *   <li><b>개곡선(POLYLINE, closed=false)</b>: 시작/끝 정점을 <b>앵커로 고정</b>. 두 정점열의 진행
 *       방향이 반대면 앵커 거리 휴리스틱으로 감지 후 대상 정점열을 reverse.</li>
 * </ul>
 *
 * <p>입력 검증(CWE-20): 정점 개수(폐곡선≥3, 개곡선≥2)·상한({@value #MAX_VERTICES})·좌표 유한성
 * ({@link Double#isFinite})을 fail-fast 로 강제한다. 비용행렬(회전 탐색)은 대응 계산 1회에만 쓰이며,
 * 호출자({@link TrackInterpolator})가 키프레임 쌍당 한 번만 {@code match} 를 호출하고 결과를 재사용한다.</p>
 *
 * <p>Spring 의존성 없음 — 순수 도메인 로직. 원본: {@code docs/analysis/portable-modules/01-track-interpolation.md}</p>
 */
public final class PolyshapeMatcher {

    /** 정점 개수 상한 — 극단 상이(예: 3 vs 300) 시 O(K^2) 회전 탐색 폭주 방지. */
    public static final int MAX_VERTICES = 1000;

    private static final double DEDUP_EPS = 1e-9;

    /** 대응쌍 — 시작 키프레임 정점 {@code from} → 종료 키프레임 정점 {@code to}. */
    public record PointPair(Point from, Point to) {
    }

    /**
     * 두 정점열의 대응쌍을 계산한다.
     *
     * @param aIn    시작 키프레임 정점열 (non-null)
     * @param bIn    종료 키프레임 정점열 (non-null)
     * @param closed true=폐곡선(POLYGON) / false=개곡선(POLYLINE)
     * @return 대응쌍 리스트. 크기 == {@code max(정규화된 a.size, 정규화된 b.size)} (불변식)
     * @throws NullPointerException     입력이 null
     * @throws IllegalArgumentException 정점 개수 미달/상한 초과 또는 좌표가 유한하지 않음
     */
    public List<PointPair> match(List<Point> aIn, List<Point> bIn, boolean closed) {
        Objects.requireNonNull(aIn, "aIn must not be null");
        Objects.requireNonNull(bIn, "bIn must not be null");

        List<Point> a = closed ? normalizeRing(aIn) : new ArrayList<>(aIn);
        List<Point> b = closed ? normalizeRing(bIn) : new ArrayList<>(bIn);

        validate(a, closed);
        validate(b, closed);

        if (!closed && isReversed(a, b)) {
            b = reversed(b);
        }

        int n = a.size();
        int m = b.size();
        int k = Math.max(n, m);

        return closed ? matchClosed(a, b, k) : matchOpen(a, b, k);
    }

    /** 폐곡선 닫는 정점 정규화 — 마지막 정점이 첫 정점과 (거의) 같으면 제거. */
    private static List<Point> normalizeRing(List<Point> ring) {
        List<Point> copy = new ArrayList<>(ring);
        if (copy.size() >= 2) {
            Point first = copy.get(0);
            Point last = copy.get(copy.size() - 1);
            if (Math.abs(first.x() - last.x()) < DEDUP_EPS && Math.abs(first.y() - last.y()) < DEDUP_EPS) {
                copy.remove(copy.size() - 1);
            }
        }
        return copy;
    }

    private static void validate(List<Point> pts, boolean closed) {
        int min = closed ? 3 : 2;
        if (pts.size() < min) {
            throw new IllegalArgumentException(
                    (closed ? "폴리곤" : "폴리라인") + " 정점 개수가 부족합니다(최소 " + min + "): " + pts.size());
        }
        if (pts.size() > MAX_VERTICES) {
            throw new IllegalArgumentException("정점 개수가 상한(" + MAX_VERTICES + ")을 초과했습니다: " + pts.size());
        }
        for (Point p : pts) {
            if (p == null || !Double.isFinite(p.x()) || !Double.isFinite(p.y())) {
                throw new IllegalArgumentException("정점 좌표가 유한하지 않습니다(NaN/Infinity/null).");
            }
        }
    }

    /** 개곡선 방향 반전 판별 — 시작/끝 앵커 거리 합이 교차 앵커 거리 합보다 크면 반전으로 간주. */
    private static boolean isReversed(List<Point> a, List<Point> b) {
        Point a0 = a.get(0);
        Point al = a.get(a.size() - 1);
        Point b0 = b.get(0);
        Point bl = b.get(b.size() - 1);
        double straight = dist(a0, b0) + dist(al, bl);
        double crossed = dist(a0, bl) + dist(al, b0);
        return crossed < straight;
    }

    private static List<Point> reversed(List<Point> pts) {
        List<Point> r = new ArrayList<>(pts);
        java.util.Collections.reverse(r);
        return r;
    }

    /** 개곡선 — 시작/끝 앵커 고정 비례 대응 (순서 보존). */
    private static List<PointPair> matchOpen(List<Point> a, List<Point> b, int k) {
        List<PointPair> pairs = new ArrayList<>(k);
        for (int j = 0; j < k; j++) {
            int ia = mapOpen(j, k, a.size());
            int ib = mapOpen(j, k, b.size());
            pairs.add(new PointPair(a.get(ia), b.get(ib)));
        }
        return pairs;
    }

    /** j∈[0,k) 를 size 정점에 비례 매핑. size==k 면 항등, 아니면 시작/끝 앵커 고정 반올림. */
    private static int mapOpen(int j, int k, int size) {
        if (size == k) {
            return j;
        }
        return (int) Math.round(j * (double) (size - 1) / (k - 1));
    }

    /**
     * 폐곡선 — 비례 순환 매핑 + 최소비용 회전 오프셋 탐색.
     *
     * <p>폐곡선은 시작 정점이 임의일 뿐 아니라 <b>winding 방향(정점 나열 순서)</b>도 임의다.
     * 동일 형상이 반대 winding 으로 들어오면 회전만으로는 대응이 꼬여 중간 프레임이
     * degenerate(0면적)로 붕괴한다. 따라서 <b>정방향 + 역방향(reverse) 각각 최소비용 회전을
     * 탐색한 뒤 전체 최소</b>를 채택한다. 동률이면 정방향을 유지한다(기존 동작 보존).</p>
     */
    private static List<PointPair> matchClosed(List<Point> a, List<Point> b, int k) {
        int[] ia = new int[k];
        for (int j = 0; j < k; j++) {
            ia[j] = mapClosed(j, k, a.size());
        }
        RotationFit forward = bestRotation(a, ia, b, k);
        RotationFit reverse = bestRotation(a, ia, reversed(b), k);
        RotationFit best = reverse.cost() < forward.cost() ? reverse : forward;

        List<Point> target = best.target();
        int[] ib = new int[k];
        for (int j = 0; j < k; j++) {
            ib[j] = mapClosed(j, k, target.size());
        }
        List<PointPair> pairs = new ArrayList<>(k);
        for (int j = 0; j < k; j++) {
            pairs.add(new PointPair(a.get(ia[j]), target.get(ib[(j + best.rotation()) % k])));
        }
        return pairs;
    }

    /** 특정 target 정점열에 대한 최소비용 회전 오프셋 탐색 결과. */
    private record RotationFit(List<Point> target, int rotation, double cost) {
    }

    /** 고정된 a 대응 인덱스({@code ia})에 대해 target 을 회전시키며 최소 총거리 오프셋을 찾는다. */
    private static RotationFit bestRotation(List<Point> a, int[] ia, List<Point> target, int k) {
        int[] ib = new int[k];
        for (int j = 0; j < k; j++) {
            ib[j] = mapClosed(j, k, target.size());
        }
        int bestR = 0;
        double best = Double.POSITIVE_INFINITY;
        for (int r = 0; r < k; r++) {
            double cost = 0.0;
            for (int j = 0; j < k; j++) {
                cost += dist(a.get(ia[j]), target.get(ib[(j + r) % k]));
            }
            if (cost < best) {
                best = cost;
                bestR = r;
            }
        }
        return new RotationFit(target, bestR, best);
    }

    private static int mapClosed(int j, int k, int size) {
        if (size == k) {
            return j;
        }
        return ((int) Math.round(j * (double) size / k)) % size;
    }

    private static double dist(Point p, Point q) {
        return Math.hypot(p.x() - q.x(), p.y() - q.y());
    }
}
