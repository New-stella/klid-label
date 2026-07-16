// Phase 3: keypointHelpers — 스켈레톤 엣지→Line 좌표 변환, 가시성별 스타일.

import { describe, expect, it } from 'vitest';

import { skeletonEdgeToCanvasLine, visibilityStyle } from '../keypointHelpers';
import type { Geometry } from '../coordinateTransformer';

const geom: Geometry = {
  image: { width: 100, height: 100 },
  canvas: { width: 100, height: 100 },
  scale: 1,
  top: 0,
  left: 0,
  angle: 0,
};

// 1-indexed 관절 좌표 (테스트용 17점, 모두 v=2 가시).
function makeKeypoints(): { x: number; y: number; v: number }[] {
  return Array.from({ length: 17 }, (_, i) => ({ x: i + 1, y: (i + 1) * 2, v: 2 }));
}

describe('keypointHelpers — skeletonEdgeToCanvasLine', () => {
  it('keypointHelpers_엣지를_Line좌표로_변환', () => {
    const kps = makeKeypoints();
    // 엣지 [1,2] → keypoints[0]=(1,2), keypoints[1]=(2,4). identity geom → 그대로.
    const line = skeletonEdgeToCanvasLine(geom, kps, [1, 2]);
    expect(line).toEqual([1, 2, 2, 4]);
  });

  it('keypointHelpers_끝점_v0면_null(선_숨김)', () => {
    const kps = makeKeypoints();
    kps[1] = { x: 2, y: 4, v: 0 }; // 두번째 관절 미표기
    expect(skeletonEdgeToCanvasLine(geom, kps, [1, 2])).toBeNull();
  });

  it('keypointHelpers_미배치_인덱스면_null(부분_스켈레톤_안전)', () => {
    const partial = makeKeypoints().slice(0, 3); // 3점만 배치
    // 엣지 [1,2] 는 둘 다 존재 → 좌표 반환
    expect(skeletonEdgeToCanvasLine(geom, partial, [1, 2])).not.toBeNull();
    // 엣지 [6,7] 은 인덱스 밖 → null
    expect(skeletonEdgeToCanvasLine(geom, partial, [6, 7])).toBeNull();
  });
});

describe('keypointHelpers — visibilityStyle', () => {
  it('keypointHelpers_가시성별_스타일_v2_v1_v0_구분', () => {
    const v2 = visibilityStyle(2);
    expect(v2.visible).toBe(true);
    expect(v2.opacity).toBe(1);
    expect(v2.dash).toBeUndefined();

    const v1 = visibilityStyle(1);
    expect(v1.visible).toBe(true);
    expect(v1.opacity).toBeLessThan(1);
    expect(v1.dash).toBeDefined();

    const v0 = visibilityStyle(0);
    expect(v0.visible).toBe(false);
  });
});
