package kr.co.cudo.authoring.batch.interpolation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * BBOX 트랙 선형 보간기.
 *
 * <p>키프레임 리스트(프레임 오름차순 정렬 권장)를 받아 키프레임 사이 프레임의 BBOX 를
 * 선형 보간하여 채워준다. {@code outside=true} 마커 이후는 처리하지 않으며,
 * 마지막 키프레임 이후 프레임으로 propagate 도 하지 않는다 (CVAT 동일 정책).</p>
 *
 * <p>Spring 의존성 없음 — 순수 도메인 로직. 호출자가 {@code new TrackInterpolator()} 로 인스턴스화한다.</p>
 *
 * <p>원본: CVAT 트랙 보간 — {@code docs/analysis/portable-modules/01-track-interpolation.md}</p>
 */
public final class TrackInterpolator {

    /**
     * 키프레임 리스트를 선형 보간하여 frame → Bbox 매핑을 반환한다.
     *
     * <p>처리 규칙 (CVAT 호환):</p>
     * <ul>
     *   <li>빈 입력 → 빈 결과</li>
     *   <li>단일 키프레임 → 그 프레임만 결과에 포함</li>
     *   <li>두 키프레임 사이 frame 은 {@link Bbox#linearInterpolate} 로 채움</li>
     *   <li>{@code outside=true} 키프레임을 만나면 즉시 종료 — 해당 frame 도 결과 미포함</li>
     *   <li>다음 키프레임이 {@code outside=true} 면 현재 키프레임만 결과에 넣고 종료
     *       (사이 보간 안 함)</li>
     *   <li>마지막 키프레임 이후 frame 은 propagate 하지 않음</li>
     *   <li>동일 frame 키프레임이 중복되면 뒤 값이 우선 (LinkedHashMap 덮어쓰기)</li>
     * </ul>
     *
     * @param keyframes  프레임 오름차순 키프레임 목록 (non-null, 정렬은 호출자 책임)
     * @param totalFrames 영상 전체 프레임 수 (≥ 0). 현재 알고리즘은 상한 가드로만 사용
     * @return frame 번호 → 보간된 BBOX 의 LinkedHashMap (삽입 순서 보존)
     * @throws NullPointerException     {@code keyframes} 가 null
     * @throws IllegalArgumentException {@code totalFrames} 가 음수
     */
    public Map<Integer, Bbox> interpolate(List<Keyframe> keyframes, int totalFrames) {
        Objects.requireNonNull(keyframes, "keyframes must not be null");
        if (totalFrames < 0) {
            throw new IllegalArgumentException("totalFrames must be >= 0, but was " + totalFrames);
        }
        if (keyframes.isEmpty()) {
            return Map.of();
        }
        Map<Integer, Bbox> out = new LinkedHashMap<>();
        for (int i = 0; i < keyframes.size(); i++) {
            Keyframe k = keyframes.get(i);
            if (k.outside()) {
                // 트랙 종료 마커 — 이후 처리 중단. 해당 frame 도 결과 미포함.
                break;
            }
            out.put(k.frame(), k.bbox());

            if (i + 1 < keyframes.size()) {
                Keyframe next = keyframes.get(i + 1);
                if (next.outside()) {
                    // 다음이 종료 마커 — 현재 키프레임만 저장하고 종료.
                    break;
                }
                int span = next.frame() - k.frame();
                if (span <= 0) {
                    // 같은 frame 또는 역순 — 사이 보간 없이 다음 iteration 에서 덮어쓰기 처리.
                    continue;
                }
                for (int f = k.frame() + 1; f < next.frame(); f++) {
                    double t = (f - k.frame()) / (double) span;
                    out.put(f, Bbox.linearInterpolate(k.bbox(), next.bbox(), t));
                }
            }
        }
        return out;
    }
}
