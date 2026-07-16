import { describe, expect, it } from 'vitest';

import { clampZoom } from '@/stores/useLabelStore';

import { translateFromCanvas, translateToCanvas } from '../coordinateTransformer';
import { buildGeometry, clampPan, isValidBox, normalizeBox } from '../canvasGeometry';

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

  it('clampZoom_fit바닥_fit미만_축소차단_사방여백_방지', () => {
    // ① fit(zoom=1) 이 최소 배율 바닥 — fit 아래로 축소돼 사방 여백이 생기는 상태를 차단.
    expect(clampZoom(0.5)).toBe(1);
    expect(clampZoom(0.1)).toBe(1);
    expect(clampZoom(1)).toBe(1);
    expect(clampZoom(3)).toBe(3); // 줌인은 그대로
    expect(clampZoom(99)).toBe(8); // 상한 유지
  });

  it('buildGeometry_zoom1_pan입력있어도_중앙정렬_pan0', () => {
    // ③ zoom=1(fit)에서는 클램프 결과 pan=0 → 항상 중앙. 팬 입력이 있어도 밀리지 않는다.
    const geom = buildGeometry({ width: 1920, height: 1080 }, { width: 960, height: 540 }, 1, 300, -200);
    expect(geom.scale).toBeCloseTo(0.5, 5);
    expect(geom.left).toBeCloseTo(0, 5);
    expect(geom.top).toBeCloseTo(0, 5);
  });

  it('buildGeometry_줌인_사방끝까지_팬해도_이미지가_캔버스를_덮음_반대편여백없음', () => {
    // ② 줌인(zoom=2) 상태에서 좌/우/상/하 극단 팬을 줘도 이미지 가장자리가 캔버스 안쪽으로
    //    들어오지 않아야(=반대편에 빈 공간 없음). 이미지 100×100, 캔버스 200×200 → fit=2, scale=4.
    const image = { width: 100, height: 100 };
    const canvas = { width: 200, height: 200 };
    const extremes = [
      [100000, 0],
      [-100000, 0],
      [0, 100000],
      [0, -100000],
      [100000, -100000],
    ];
    for (const [px, py] of extremes) {
      const geom = buildGeometry(image, canvas, 2, px, py, 0);
      const scaledW = image.width * geom.scale; // 400
      const scaledH = image.height * geom.scale; // 400
      // 좌/상단이 캔버스 안쪽(양수)으로 못 들어옴 + 우/하단이 캔버스 경계 밖(덮음).
      expect(geom.left).toBeLessThanOrEqual(1e-9);
      expect(geom.left + scaledW).toBeGreaterThanOrEqual(canvas.width - 1e-9);
      expect(geom.top).toBeLessThanOrEqual(1e-9);
      expect(geom.top + scaledH).toBeGreaterThanOrEqual(canvas.height - 1e-9);
    }
  });

  it('clampPan_letterbox축은_중앙유지_pan0', () => {
    // ④ 이미지 100×50, 캔버스 200×200 → fit=2, scaledW=200(가로 꽉참)·scaledH=100(세로 letterbox).
    //    큰 축(X)이 캔버스와 같으면(초과분 0) 중앙, 작은 축(Y, letterbox)은 팬 입력 무시하고 중앙.
    const clamped = clampPan({ width: 100, height: 50 }, { width: 200, height: 200 }, 2, 500, 500);
    expect(clamped.panX).toBe(0);
    expect(clamped.panY).toBe(0);
  });

  it('clampPan_줌인축은_초과분_절반까지만_허용', () => {
    // 이미지 100×100, 캔버스 200×200, scale=4 → scaledW=400, 초과분 절반 limit=(400-200)/2=100.
    expect(clampPan({ width: 100, height: 100 }, { width: 200, height: 200 }, 4, 50, -50)).toEqual({
      panX: 50,
      panY: -50,
    }); // 한계 내 → 그대로
    expect(clampPan({ width: 100, height: 100 }, { width: 200, height: 200 }, 4, 999, -999)).toEqual({
      panX: 100,
      panY: -100,
    }); // 한계로 클램프
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
