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

  it('네이티브_1920x1440_라벨_픽셀이_캔버스에_정확히_매핑', () => {
    // 회귀: 소스 영상이 1920×1440(≠1080)일 때 YOLO 네이티브 픽셀 라벨이 어긋나던 버그.
    // 실측 크기를 geometry.image 로 사용하면 캔버스 매핑이 정확해야 한다.
    // 캔버스 960×720 → fit = min(960/1920, 720/1440) = 0.5, letterbox 없음.
    const geom = buildGeometry({ width: 1920, height: 1440 }, { width: 960, height: 720 }, 1, 0, 0);
    expect(geom.scale).toBeCloseTo(0.5, 5);
    expect(geom.left).toBeCloseTo(0, 5);
    expect(geom.top).toBeCloseTo(0, 5);
    // 이미지 우하단(1920,1440) → 캔버스 우하단(960,720). 세로가 밀리거나 늘어나지 않는다.
    const br = translateToCanvas(geom, 1920, 1440);
    expect(br.x).toBeCloseTo(960, 4);
    expect(br.y).toBeCloseTo(720, 4);
    // 이미지 중심(960,720) → 캔버스 중심(480,360).
    const center = translateToCanvas(geom, 960, 720);
    expect(center.x).toBeCloseTo(480, 4);
    expect(center.y).toBeCloseTo(360, 4);
  });

  it('네이티브_1280x720_letterbox_offset_실측기준_정확', () => {
    // 1280×720 이미지, 캔버스 1280×900(세로 여유) → fit = min(1, 1.25) = 1, 세로 letterbox.
    const geom = buildGeometry({ width: 1280, height: 720 }, { width: 1280, height: 900 }, 1, 0, 0);
    expect(geom.scale).toBeCloseTo(1, 5);
    expect(geom.left).toBeCloseTo(0, 5);
    expect(geom.top).toBeCloseTo((900 - 720) / 2, 5); // 90px letterbox
    const c = translateToCanvas(geom, 640, 360);
    expect(c.x).toBeCloseTo(640, 4);
    expect(c.y).toBeCloseTo(90 + 360, 4);
  });

  it('네이티브크기_비1920x1080_왕복_canvas→image→canvas_무손실', () => {
    // 드로잉→저장→재렌더 왕복 무손실 — 수동 드로잉과 오토라벨 좌표계 일치 보장.
    const geom = buildGeometry({ width: 1920, height: 1440 }, { width: 800, height: 600 }, 1.5, 25, -15);
    const canvasPt = { x: 321, y: 254 };
    const img = translateFromCanvas(geom, canvasPt.x, canvasPt.y);
    const back = translateToCanvas(geom, img.x, img.y);
    expect(back.x).toBeCloseTo(canvasPt.x, 4);
    expect(back.y).toBeCloseTo(canvasPt.y, 4);
  });

  it('이미지_미로드_0크기_가드_NaN없이_scale0_반환', () => {
    // 타이밍 가드: 이미지 로드 전 naturalWidth/Height=0 이면 0-division/NaN 없이 안전 geometry.
    const geom = buildGeometry({ width: 0, height: 0 }, { width: 960, height: 540 }, 1, 0, 0);
    expect(geom.scale).toBe(0);
    expect(Number.isNaN(geom.left)).toBe(false);
    expect(Number.isNaN(geom.top)).toBe(false);
    expect(Number.isFinite(geom.left)).toBe(true);
    expect(Number.isFinite(geom.top)).toBe(true);
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
