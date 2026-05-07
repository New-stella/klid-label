// 트랙 보간 클라이언트 보조 — 키프레임 사이 시각 미리보기 용도.
// BE TrackInterpolator(portable-modules/01)가 정답이며, FE는 보조 표시만.

export interface BBox4 {
  left: number;
  top: number;
  right: number;
  bottom: number;
}

export interface Keyframe {
  frameNo: number;
  bbox: BBox4;
}

/**
 * 두 BBox 사이 선형 보간 (t ∈ [0, 1]).
 */
export function interpolateBBox(a: BBox4, b: BBox4, t: number): BBox4 {
  const c = Math.max(0, Math.min(1, t));
  return {
    left: a.left + (b.left - a.left) * c,
    top: a.top + (b.top - a.top) * c,
    right: a.right + (b.right - a.right) * c,
    bottom: a.bottom + (b.bottom - a.bottom) * c,
  };
}

/**
 * 키프레임 시퀀스에서 frame 위치의 보간 BBox.
 * - frame이 첫/마지막 키프레임 범위 밖이면 가장 가까운 끝값 반환 (extrapolation 안 함).
 * - 키프레임 1개면 그대로 반환.
 */
export function interpolateTrack(keyframes: Keyframe[], frame: number): BBox4 {
  if (keyframes.length === 0) {
    throw new Error('keyframes 배열이 비어 있습니다');
  }
  // frameNo 오름차순 정렬 (방어적)
  const sorted = [...keyframes].sort((a, b) => a.frameNo - b.frameNo);
  if (sorted.length === 1) {
    return { ...sorted[0].bbox };
  }
  if (frame <= sorted[0].frameNo) return { ...sorted[0].bbox };
  if (frame >= sorted[sorted.length - 1].frameNo) {
    return { ...sorted[sorted.length - 1].bbox };
  }
  // frame을 포함하는 구간 탐색
  for (let i = 0; i < sorted.length - 1; i++) {
    const a = sorted[i];
    const b = sorted[i + 1];
    if (frame >= a.frameNo && frame <= b.frameNo) {
      const span = b.frameNo - a.frameNo;
      const t = span === 0 ? 0 : (frame - a.frameNo) / span;
      return interpolateBBox(a.bbox, b.bbox, t);
    }
  }
  return { ...sorted[sorted.length - 1].bbox };
}
