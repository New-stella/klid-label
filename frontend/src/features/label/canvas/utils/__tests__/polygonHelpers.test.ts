import { describe, expect, it } from 'vitest';

import { closePolygonIfNear, validatePolygonPoints } from '../polygonHelpers';

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
});
