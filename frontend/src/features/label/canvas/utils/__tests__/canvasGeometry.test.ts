import { describe, expect, it } from 'vitest';

import { translateFromCanvas, translateToCanvas } from '../coordinateTransformer';
import { buildGeometry, isValidBox, normalizeBox } from '../canvasGeometry';

describe('canvasGeometry', () => {
  it('buildGeometry_fit_scale_zoom_1_이미지가_캔버스에_맞음', () => {
    const geom = buildGeometry({ width: 1920, height: 1080 }, { width: 960, height: 540 }, 1, 0, 0);
    expect(geom.scale).toBeCloseTo(0.5, 5); // 1920*0.5=960
    expect(geom.left).toBeCloseTo(0, 5);
    expect(geom.top).toBeCloseTo(0, 5);
  });

  it('BBox_그리기_2점_드래그_좌표_정확도', () => {
    // 2배 줌 + pan 30/40 적용 시 캔버스 좌표 → 이미지 좌표 라운드트립
    const geom = buildGeometry({ width: 1920, height: 1080 }, { width: 960, height: 540 }, 2, 30, 40);
    const start = { x: 100, y: 80 };
    const end = { x: 300, y: 200 };

    const imgStart = translateFromCanvas(geom, start.x, start.y);
    const imgEnd = translateFromCanvas(geom, end.x, end.y);

    const backStart = translateToCanvas(geom, imgStart.x, imgStart.y);
    const backEnd = translateToCanvas(geom, imgEnd.x, imgEnd.y);

    expect(backStart.x).toBeCloseTo(start.x, 4);
    expect(backStart.y).toBeCloseTo(start.y, 4);
    expect(backEnd.x).toBeCloseTo(end.x, 4);
    expect(backEnd.y).toBeCloseTo(end.y, 4);
  });

  it('normalizeBox_역순_좌표도_정상화', () => {
    const box = normalizeBox(100, 80, 20, 30);
    expect(box).toEqual({ left: 20, top: 30, right: 100, bottom: 80 });
  });

  it('isValidBox_최소_크기_미만은_false', () => {
    expect(isValidBox(0, 0, 1, 100)).toBe(false);
    expect(isValidBox(0, 0, 50, 50)).toBe(true);
  });
});
