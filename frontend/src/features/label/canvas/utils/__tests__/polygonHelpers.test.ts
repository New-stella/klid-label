import { describe, expect, it } from 'vitest';

import { closePolygonIfNear, simplifyPolygon, validatePolygonPoints } from '../polygonHelpers';

describe('polygonHelpers', () => {
  it('Polygon_최소_3점_조건_위반시_그리기_취소', () => {
    expect(validatePolygonPoints([0, 0])).toBeNull();
    expect(validatePolygonPoints([0, 0, 10, 10])).toBeNull();
    expect(validatePolygonPoints([0, 0, 10, 10, 20, 20])).toEqual([0, 0, 10, 10, 20, 20]);
  });

  it('closePolygonIfNear_끝점이_시작점에_근접하면_닫힘', () => {
    const result = closePolygonIfNear([0, 0, 100, 0, 100, 100, 2, 2], 8);
    expect(result.closed).toBe(true);
    expect(result.points).toEqual([0, 0, 100, 0, 100, 100]);
  });

  it('closePolygonIfNear_거리_임계_초과면_그대로', () => {
    const result = closePolygonIfNear([0, 0, 100, 0, 100, 100, 50, 50], 8);
    expect(result.closed).toBe(false);
    expect(result.points.length).toBe(8);
  });

  it('simplifyPolygon_직선상_중간점_제거', () => {
    // 거의 직선 위 점들 (1,1,2,2 ... ) 는 단순화로 제거되어 시작/끝만 남음
    const line = [0, 0, 1, 0.1, 2, 0, 3, 0.1, 100, 0];
    const result = simplifyPolygon(line, 1.0);
    expect(result.length).toBeLessThan(line.length);
    expect(result[0]).toBe(0);
    expect(result[result.length - 2]).toBe(100);
  });

  it('simplifyPolygon_정점_상한_초과시_절삭', () => {
    // 지그재그 2000점 → epsilon 증가/샘플링으로 maxPoints 이하로 절삭
    const pts: number[] = [];
    for (let i = 0; i < 2000; i += 1) {
      pts.push(i, i % 2 === 0 ? 0 : 50);
    }
    const result = simplifyPolygon(pts, 1.0, 1000);
    expect(result.length / 2).toBeLessThanOrEqual(1000);
  });

  it('simplifyPolygon_3점_이하는_그대로', () => {
    expect(simplifyPolygon([0, 0, 10, 10, 20, 0])).toEqual([0, 0, 10, 10, 20, 0]);
  });
});
