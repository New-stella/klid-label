import { describe, expect, it } from 'vitest';

import {
  DBLCLICK_MOVE_TOLERANCE_PX,
  TRAILING_CLICK_WINDOW_MS,
  isDeliberateDoubleClick,
  isTrailingClickOfDoubleClick,
} from '../doubleClickGuard';

describe('doubleClickGuard — 합성 더블클릭 판별', () => {
  it('같은_지점_두번_누름은_의도된_더블클릭이다', () => {
    // given: 구성 클릭 두 지점이 사실상 같은 자리
    const first = { x: 400, y: 260 };
    const second = { x: 402, y: 261 };
    // when / then
    expect(isDeliberateDoubleClick(first, second)).toBe(true);
  });

  it('먼_위치_두번_누름은_의도된_더블클릭이_아니다', () => {
    // given: Konva 가 시간(400ms)만 보고 합성한, 서로 다른 위치의 클릭 2회
    const first = { x: 400, y: 260 };
    const second = { x: 540, y: 250 };
    // when / then
    expect(isDeliberateDoubleClick(first, second)).toBe(false);
  });

  it('허용치_경계는_이내면_인정_초과면_거부한다', () => {
    const base = { x: 0, y: 0 };
    const onEdge = { x: DBLCLICK_MOVE_TOLERANCE_PX, y: 0 };
    const overEdge = { x: DBLCLICK_MOVE_TOLERANCE_PX + 0.01, y: 0 };
    expect(isDeliberateDoubleClick(base, onEdge)).toBe(true);
    expect(isDeliberateDoubleClick(base, overEdge)).toBe(false);
  });

  it('구성_클릭_지점을_모르면_기존_동작을_유지한다', () => {
    // 판정 근거가 없는 경우(누름 이벤트 미관측)는 관대하게 통과시킨다.
    expect(isDeliberateDoubleClick(null, { x: 1, y: 1 })).toBe(true);
    expect(isDeliberateDoubleClick({ x: 1, y: 1 }, null)).toBe(true);
    expect(isDeliberateDoubleClick(null, null)).toBe(true);
  });

  it('허용치는_호출부에서_조정할_수_있다', () => {
    const a = { x: 0, y: 0 };
    const b = { x: 20, y: 0 };
    expect(isDeliberateDoubleClick(a, b, 25)).toBe(true);
    expect(isDeliberateDoubleClick(a, b, 5)).toBe(false);
  });
});

describe('doubleClickGuard — 확정 더블클릭의 잔여 클릭 판별', () => {
  const commitPoint = { x: 380, y: 410 };

  it('확정_직후_같은_자리_클릭은_잔여_클릭이다', () => {
    // given: 마지막 정점 클릭 + 더블클릭(물리 3클릭) → 앞 두 개로 확정된 직후 남은 한 클릭
    const commit = { at: 1_000, point: commitPoint };
    // when / then: 같은 시간창·같은 자리 → 정점으로 세지 않는다
    expect(isTrailingClickOfDoubleClick(commit, { x: 383, y: 412 }, 1_050)).toBe(true);
  });

  it('시간창을_지난_클릭은_정상_정점이다', () => {
    const commit = { at: 1_000, point: commitPoint };
    expect(
      isTrailingClickOfDoubleClick(commit, commitPoint, 1_000 + TRAILING_CLICK_WINDOW_MS + 1),
    ).toBe(false);
  });

  it('같은_시간창이어도_다른_자리_클릭은_정상_정점이다', () => {
    // 확정 직후 다른 위치에서 새 폴리곤을 시작하는 정상 조작 — 삼키면 안 된다.
    const commit = { at: 1_000, point: commitPoint };
    expect(isTrailingClickOfDoubleClick(commit, { x: 540, y: 250 }, 1_050)).toBe(false);
  });

  it('확정_이력이_없거나_클릭_지점을_모르면_정상_정점이다', () => {
    // 근거가 없을 때 클릭을 삼키면 사용자의 정점이 무음으로 사라진다 — 관대하게 통과시킨다.
    expect(isTrailingClickOfDoubleClick(null, commitPoint, 1_050)).toBe(false);
    expect(isTrailingClickOfDoubleClick({ at: 1_000, point: commitPoint }, null, 1_050)).toBe(false);
  });

  it('허용치_경계는_이내면_잔여_초과면_정상_정점이다', () => {
    const commit = { at: 0, point: { x: 0, y: 0 } };
    expect(isTrailingClickOfDoubleClick(commit, { x: DBLCLICK_MOVE_TOLERANCE_PX, y: 0 }, 0)).toBe(
      true,
    );
    expect(
      isTrailingClickOfDoubleClick(commit, { x: DBLCLICK_MOVE_TOLERANCE_PX + 0.01, y: 0 }, 0),
    ).toBe(false);
  });
});
