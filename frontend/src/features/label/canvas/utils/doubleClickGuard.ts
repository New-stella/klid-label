// 더블클릭 의도 판별 — pure 함수.

import type { Point } from './coordinateTransformer';

/**
 * 더블클릭을 구성한 누름 2회가 "같은 자리" 로 인정되는 이동 허용치(px, canvas 좌표).
 * 손떨림·트랙패드 미세 이동은 흡수하고, 다른 대상을 겨냥한 클릭은 걸러낼 정도로 작게 잡는다.
 */
export const DBLCLICK_MOVE_TOLERANCE_PX = 8;

/**
 * 이 dblclick 이 사용자가 **의도한** 더블클릭인가.
 *
 * Konva 의 dblclick 합성은 **시간(`Konva.dblClickWindow`, 기본 400ms) + 같은 shape 에서 끝났는가**
 * 만 검사하고 **포인터 이동 거리를 보지 않는다**. 라벨링 캔버스는 전체가 단일 캡처 Rect 라,
 * 서로 다른 위치를 400ms 안에 클릭하기만 해도 dblclick 이 무조건 합성된다 — 브라우저의 진짜
 * 더블클릭이 아니라 Konva 자체 합성이므로 빠르게 클릭하는 것만으로 재현된다.
 * 그래서 구성 클릭 두 지점의 거리로 의도를 판정한다.
 *
 * @param first  더블클릭을 구성한 첫 번째 누름 지점(canvas 좌표). 관측하지 못했으면 null.
 * @param second 두 번째 누름 지점(canvas 좌표). 관측하지 못했으면 null.
 * @param tolerancePx 같은 자리로 인정할 이동 허용치.
 * @returns 두 지점을 모두 알 때만 거리로 판정한다. 판정 근거가 없으면(null) **true** 를 돌려
 *   기존 동작을 유지한다 — 근거 없이 사용자의 확정 조작을 삼키지 않는다.
 */
export function isDeliberateDoubleClick(
  first: Point | null,
  second: Point | null,
  tolerancePx: number = DBLCLICK_MOVE_TOLERANCE_PX,
): boolean {
  if (!first || !second) return true;
  return Math.hypot(second.x - first.x, second.y - first.y) <= tolerancePx;
}

/**
 * 더블클릭을 구성한 클릭이 그 자리에서 다시 클릭으로 인정되는 시간창(ms).
 * Konva 의 `dblClickWindow` 기본값과 같은 크기로 잡는다 — 판정 대상이 바로 그 창 안에서
 * 합성된 dblclick 의 잔여 클릭이기 때문이다.
 */
export const TRAILING_CLICK_WINDOW_MS = 400;

/**
 * 방금 발생한 클릭이 **직전에 폴리곤을 확정한 더블클릭의 남은 클릭**인가.
 *
 * 사용자가 마지막 정점을 찍고 곧바로 같은 자리를 더블클릭해 폴리곤을 끝내면, 물리적 클릭은
 * 3회다(정점 + 더블클릭 2회). Konva 는 앞의 두 클릭으로 dblclick 을 먼저 합성하므로 그 시점에
 * 확정이 끝나고, **더블클릭의 나머지 한 클릭이 확정 뒤에 도착해 새 draft 의 첫 정점으로 남는다**
 * — 사용자가 찍은 적 없는 정점이라 다음 폴리곤의 모양을 망친다. 확정 직후 같은 자리의 클릭
 * 하나만 이 판정으로 걸러낸다.
 *
 * @param commit 직전 확정 지점과 시각. 확정 이력이 없으면 null.
 * @param click 지금 클릭한 지점(canvas 좌표).
 * @param now 현재 시각(ms).
 * @returns 확정 이력이 없거나 시간창을 벗어났거나 다른 자리면 false(= 정상 정점으로 처리).
 */
export function isTrailingClickOfDoubleClick(
  commit: { at: number; point: Point } | null,
  click: Point | null,
  now: number,
  tolerancePx: number = DBLCLICK_MOVE_TOLERANCE_PX,
  windowMs: number = TRAILING_CLICK_WINDOW_MS,
): boolean {
  if (!commit || !click) return false;
  if (now - commit.at > windowMs) return false;
  return Math.hypot(click.x - commit.point.x, click.y - commit.point.y) <= tolerancePx;
}
