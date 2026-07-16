// R17 이슈6: 커서 기준 줌(zoom-to-point) 순수 계산 유틸 테스트.

import { describe, expect, it } from 'vitest';

import { MIN_ZOOM as PROD_MIN_ZOOM, MAX_ZOOM as PROD_MAX_ZOOM } from '@/stores/useLabelStore';

import { buildGeometry } from '../canvasGeometry';
import { translateFromCanvas } from '../coordinateTransformer';
import { zoomToPoint } from '../zoomToPoint';

const CANVAS = { width: 200, height: 200 };
const IMAGE = { width: 100, height: 100 };
// 순수 함수의 minZoom 파라미터 분기 검증용(하한을 넘지 않는지) — 임의 하한.
const MIN_ZOOM = 0.1;
const MAX_ZOOM = 8;

function geomOf(zoom: number, panX = 0, panY = 0) {
  return buildGeometry(IMAGE, CANVAS, zoom, panX, panY, 0);
}

describe('zoomToPoint — 커서 기준 줌 계산', () => {
  it('줌인 시 zoom 이 factor 만큼 커진다', () => {
    const next = zoomToPoint(geomOf(1), { x: 100, y: 100 }, 1.1, MIN_ZOOM, MAX_ZOOM);
    expect(next.zoom).toBeCloseTo(1.1, 5);
  });

  it('줌아웃 시 zoom 이 작아진다', () => {
    const next = zoomToPoint(geomOf(2), { x: 100, y: 100 }, 1 / 1.1, MIN_ZOOM, MAX_ZOOM);
    expect(next.zoom).toBeCloseTo(2 / 1.1, 5);
  });

  it('커서 아래 이미지 좌표가 줌 후에도 동일하게 유지된다 (zoom-to-point)', () => {
    const before = geomOf(1);
    const pointer = { x: 130, y: 70 };
    const imgBefore = translateFromCanvas(before, pointer.x, pointer.y);

    const next = zoomToPoint(before, pointer, 1.5, MIN_ZOOM, MAX_ZOOM);
    const after = geomOf(next.zoom, next.panX, next.panY);
    const imgAfter = translateFromCanvas(after, pointer.x, pointer.y);

    expect(imgAfter.x).toBeCloseTo(imgBefore.x, 4);
    expect(imgAfter.y).toBeCloseTo(imgBefore.y, 4);
  });

  it('clamp 상한을 넘지 않는다 (MAX_ZOOM)', () => {
    const next = zoomToPoint(geomOf(MAX_ZOOM), { x: 100, y: 100 }, 4, MIN_ZOOM, MAX_ZOOM);
    expect(next.zoom).toBe(MAX_ZOOM);
  });

  it('clamp 하한을 넘지 않는다 (MIN_ZOOM)', () => {
    const next = zoomToPoint(geomOf(MIN_ZOOM), { x: 100, y: 100 }, 0.01, MIN_ZOOM, MAX_ZOOM);
    expect(next.zoom).toBe(MIN_ZOOM);
  });

  it('clamp 로 zoom 이 변하지 않으면 pan 도 그대로 (이미지 고정점 유지)', () => {
    const before = geomOf(MAX_ZOOM, 5, -3);
    const next = zoomToPoint(before, { x: 80, y: 120 }, 2, MIN_ZOOM, MAX_ZOOM);
    expect(next.zoom).toBe(MAX_ZOOM);
    expect(next.panX).toBeCloseTo(5, 5);
    expect(next.panY).toBeCloseTo(-3, 5);
  });

  // ── 프로덕션 floor(=store MIN_ZOOM=1.0) exercise — 호출부(CanvasShell)가 항상 넘기는 실제 하한 ──
  it('프로덕션 하한(store MIN_ZOOM=1.0) — fit 미만 줌아웃이 1.0에서 바닥 + pan 0 유지', () => {
    // fit(zoom=1)에서 강한 줌아웃(factor 0.1)을 시도해도 프로덕션 floor(1.0) 아래로 못 내려가고,
    // clampPan 결합으로 pan 이 중앙(0)에 고정돼 사방 여백이 생기지 않아야 한다.
    expect(PROD_MIN_ZOOM).toBe(1);
    const before = geomOf(1);
    const next = zoomToPoint(before, { x: 40, y: 160 }, 0.1, PROD_MIN_ZOOM, PROD_MAX_ZOOM);
    expect(next.zoom).toBe(1); // fit 아래로 축소 불가
    // scale=fit(2)*1 → scaledW=scaledH=200=canvas → clampPan 초과분 0 → 중앙 고정.
    expect(next.panX).toBeCloseTo(0, 5);
    expect(next.panY).toBeCloseTo(0, 5);
  });
});
