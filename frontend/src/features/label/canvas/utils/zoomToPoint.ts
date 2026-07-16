// 커서 기준 줌(zoom-to-point) 순수 계산 — R17 이슈6.
// Stage onWheel 에서 호출. clampZoom 동일 한계를 인자로 받아 스토어와 정책 일치.

import { clampPan } from './canvasGeometry';
import type { Geometry, Point } from './coordinateTransformer';
import { clamp } from './coordinateTransformer';

export interface ZoomState {
  zoom: number;
  panX: number;
  panY: number;
}

/**
 * 현재 geometry 에서 pointer(캔버스 좌표) 아래 이미지 좌표가 줌 후에도 고정되도록
 * 새 zoom/pan 을 계산한다.
 *
 * 도출:
 *   left = (canvasW - imgW*scale)/2 + panX,  scale = fitScale * zoom
 *   이미지 고정점 i = (pointer.x - left)/scale
 *   줌 후 left' = pointer.x - i*scale'  →  panX' = left' - (canvasW - imgW*scale')/2
 * (clamp 로 zoom 이 변하지 않으면 pan 도 그대로 유지된다.)
 */
export function zoomToPoint(
  geom: Geometry,
  pointer: Point,
  factor: number,
  minZoom: number,
  maxZoom: number,
): ZoomState {
  // 현재 zoom 역산: scale = fitScale * zoom, fitScale = min(canvasW/imgW, canvasH/imgH)
  const fitScale = Math.min(
    geom.canvas.width / geom.image.width,
    geom.canvas.height / geom.image.height,
  );
  const curZoom = geom.scale / fitScale;
  const nextZoom = clamp(curZoom * factor, minZoom, maxZoom);
  const nextScale = fitScale * nextZoom;

  // 고정 이미지 좌표 (회전 미적용 — 캔버스 줌은 angle=0 전제, 기존 buildGeometry 와 동일)
  const ix = (pointer.x - geom.left) / geom.scale;
  const iy = (pointer.y - geom.top) / geom.scale;

  const nextLeft = pointer.x - ix * nextScale;
  const nextTop = pointer.y - iy * nextScale;

  const rawPanX = nextLeft - (geom.canvas.width - geom.image.width * nextScale) / 2;
  const rawPanY = nextTop - (geom.canvas.height - geom.image.height * nextScale) / 2;

  // 여백 방지: 줌 결과 pan 을 클램프해 이미지가 캔버스를 덮도록(반대편 빈 공간 차단).
  // buildGeometry 도 동일 클램프를 적용하므로 store pan 과 렌더 geometry 가 일치한다.
  const { panX, panY } = clampPan(geom.image, geom.canvas, nextScale, rawPanX, rawPanY);

  return { zoom: nextZoom, panX, panY };
}
