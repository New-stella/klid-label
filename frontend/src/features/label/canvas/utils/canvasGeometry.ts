// 캔버스 Geometry 계산 유틸 — pure 함수 (테스트 가능).

import type { Geometry, Size } from './coordinateTransformer';

/**
 * 팬(panX/panY) 여백 방지 클램프 — 순수 함수(테스트 용이).
 *
 * 규칙:
 *  - 스케일된 이미지가 캔버스보다 큰 축(scaled > canvas): 이미지가 캔버스를 항상 덮도록
 *    가장자리가 안쪽으로 못 들어오게 |pan| ≤ (scaled − canvas)/2 로 제한.
 *    (도출: left = (canvas − scaled)/2 + pan, "left ≤ 0 && left + scaled ≥ canvas"
 *     ⇔ |pan| ≤ (scaled − canvas)/2)
 *  - 작거나 같은 축(letterbox 축, scaled ≤ canvas): 중앙 정렬 고정 → pan = 0.
 *  - zoom=1(fit)에서는 한 축 scaled==canvas·다른 축 scaled<canvas 이므로 결과가 자연히 0.
 */
export function clampPan(
  image: Size,
  canvas: Size,
  scale: number,
  panX: number,
  panY: number,
): { panX: number; panY: number } {
  const scaledW = image.width * scale;
  const scaledH = image.height * scale;
  const limitX = (scaledW - canvas.width) / 2;
  const limitY = (scaledH - canvas.height) / 2;
  const cx = limitX > 0 ? Math.min(Math.max(panX, -limitX), limitX) : 0;
  const cy = limitY > 0 ? Math.min(Math.max(panY, -limitY), limitY) : 0;
  return { panX: cx, panY: cy };
}

/**
 * 이미지 + 캔버스 크기와 zoom/pan으로부터 Geometry 생성.
 * - fit 모드: 이미지가 캔버스에 들어가는 최대 scale (편차 zoom 1.0 기준).
 * - top/left: zoom 후 이미지 좌상단이 캔버스에서 위치하는 오프셋.
 * - pan 은 clampPan 으로 여백 방지 클램프 후 적용(줌인 시 이미지가 캔버스 밖으로 밀려
 *   반대편에 빈 공간이 생기지 않게).
 */
export function buildGeometry(
  image: Size,
  canvas: Size,
  zoom: number,
  panX: number,
  panY: number,
  angle = 0,
): Geometry {
  // 이미지 실측 크기 미확정(로드 전 0/음수) 가드 — 0-division/NaN·잘못된 초기 배치 방지.
  // 렌더 보류 신호로 scale=0 을 반환한다(호출측이 geometry 준비 여부로 활용 가능).
  if (image.width <= 0 || image.height <= 0) {
    return { image, canvas, scale: 0, top: canvas.height / 2, left: canvas.width / 2, angle };
  }
  const fitScale = Math.min(canvas.width / image.width, canvas.height / image.height);
  const scale = fitScale * zoom;
  const scaledW = image.width * scale;
  const scaledH = image.height * scale;
  const { panX: cPanX, panY: cPanY } = clampPan(image, canvas, scale, panX, panY);
  const left = (canvas.width - scaledW) / 2 + cPanX;
  const top = (canvas.height - scaledH) / 2 + cPanY;
  return { image, canvas, scale, top, left, angle };
}

/**
 * BBox 정규화 (left < right, top < bottom 보장).
 */
export function normalizeBox(left: number, top: number, right: number, bottom: number) {
  return {
    left: Math.min(left, right),
    top: Math.min(top, bottom),
    right: Math.max(left, right),
    bottom: Math.max(top, bottom),
  };
}

/**
 * BBox 최소 크기(픽셀) 검증 — drag 거리가 너무 작으면 무효.
 */
export function isValidBox(
  left: number,
  top: number,
  right: number,
  bottom: number,
  minSize = 2,
): boolean {
  return Math.abs(right - left) >= minSize && Math.abs(bottom - top) >= minSize;
}
