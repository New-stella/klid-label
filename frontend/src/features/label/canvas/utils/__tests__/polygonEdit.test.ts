// Phase C: 폴리곤 전체 이동 / 꼭짓점 편집 유틸 — pure 함수 단위 테스트.

import { describe, expect, it } from 'vitest';

import type { Geometry } from '../coordinateTransformer';
import {
  VERTEX_ANCHOR_MAX_POINTS,
  imagePointsToCanvas,
  movePolygonByCanvasDelta,
  moveVertexToCanvas,
  shouldRenderVertexAnchors,
} from '../polygonEdit';

// scale=2, offset 0 → 이미지 px → 캔버스 px ×2.
const geom: Geometry = {
  image: { width: 100, height: 100 },
  canvas: { width: 200, height: 200 },
  scale: 2,
  top: 0,
  left: 0,
  angle: 0,
};

describe('movePolygonByCanvasDelta — 전체 이동 (scale 보정)', () => {
  it('캔버스 delta 를 이미지 delta 로 환산해 모든 점에 적용한다', () => {
    // 삼각형 (10,10)(30,10)(20,30). 캔버스 +20,+20 이동 = 이미지 +10,+10.
    const pts = [10, 10, 30, 10, 20, 30];
    const moved = movePolygonByCanvasDelta(geom, pts, 20, 20);
    expect(moved).toEqual([20, 20, 40, 20, 30, 40]);
  });

  it('새 배열을 반환하고 원본을 변경하지 않는다 (불변성)', () => {
    const pts = [10, 10, 30, 10, 20, 30];
    const moved = movePolygonByCanvasDelta(geom, pts, 20, 20);
    expect(moved).not.toBe(pts);
    expect(pts).toEqual([10, 10, 30, 10, 20, 30]);
  });

  it('이미지 왼쪽 밖으로 이동 불가 — 외접 박스 기준 delta 클램프', () => {
    // 최소 x=10. 캔버스 -40px(=이미지 -20) 이동하면 x=-10 이 되어야 하나 0 으로 클램프.
    const pts = [10, 10, 30, 10, 20, 30];
    const moved = movePolygonByCanvasDelta(geom, pts, -40, 0);
    // delta 는 -10 (min 10 → 0). x 들: 0,20,10
    expect(moved[0]).toBeCloseTo(0, 3);
    expect(moved[2]).toBeCloseTo(20, 3);
    expect(moved[4]).toBeCloseTo(10, 3);
  });

  it('이미지 오른쪽/아래 밖으로 이동 불가', () => {
    // 최대 x=30,y=30. 큰 양수 이동 → 외접 박스가 image(100) 안에 머무름.
    const pts = [10, 10, 30, 10, 20, 30];
    const moved = movePolygonByCanvasDelta(geom, pts, 1000, 1000);
    // x delta = 100-30=70 → x: 80,100,90 / y delta = 100-30=70 → y: 80,80,100
    expect(moved).toEqual([80, 80, 100, 80, 90, 100]);
  });
});

describe('moveVertexToCanvas — 단일 꼭짓점 편집', () => {
  it('지정 인덱스 점만 캔버스 좌표 → 이미지 좌표로 갱신한다', () => {
    const pts = [10, 10, 30, 10, 20, 30];
    // index 1 (두번째 점, flat idx 2,3) 을 캔버스 (80,40)=이미지(40,20)로.
    const moved = moveVertexToCanvas(geom, pts, 1, 80, 40);
    expect(moved).toEqual([10, 10, 40, 20, 20, 30]);
  });

  it('개별 점을 이미지 경계로 클램프한다', () => {
    const pts = [10, 10, 30, 10, 20, 30];
    // 캔버스 (-20,-20) → 이미지 (-10,-10) → 클램프 (0,0)
    const moved = moveVertexToCanvas(geom, pts, 0, -20, -20);
    expect(moved[0]).toBeCloseTo(0, 3);
    expect(moved[1]).toBeCloseTo(0, 3);
  });

  it('범위 밖 인덱스는 원본 그대로 반환', () => {
    const pts = [10, 10, 30, 10, 20, 30];
    expect(moveVertexToCanvas(geom, pts, 5, 0, 0)).toBe(pts);
  });

  it('새 배열 반환 — 원본 불변', () => {
    const pts = [10, 10, 30, 10, 20, 30];
    const moved = moveVertexToCanvas(geom, pts, 1, 80, 40);
    expect(moved).not.toBe(pts);
    expect(pts).toEqual([10, 10, 30, 10, 20, 30]);
  });
});

describe('shouldRenderVertexAnchors — 점 수 임계', () => {
  it('임계 이하면 true', () => {
    const pts = new Array(VERTEX_ANCHOR_MAX_POINTS * 2).fill(0);
    expect(shouldRenderVertexAnchors(pts)).toBe(true);
  });

  it('임계 초과면 false (대량 SAM2 폴리곤)', () => {
    const pts = new Array((VERTEX_ANCHOR_MAX_POINTS + 1) * 2).fill(0);
    expect(shouldRenderVertexAnchors(pts)).toBe(false);
  });
});

describe('imagePointsToCanvas', () => {
  it('이미지 점 → 캔버스 점 (scale 적용)', () => {
    expect(imagePointsToCanvas(geom, [10, 10, 30, 30])).toEqual([20, 20, 60, 60]);
  });
});
