/**
 * 회귀 가드 — 폴링이 <b>무한히 돌지 않는다</b>.
 *
 * ★ 「멈춘다」는 축은 활성 화면 시험으로 드러나지 않는다 — 화면을 열어 둔 채로는 관찰자가 살아
 *   있어 계속 도는 것이 정상처럼 보인다. 그래서 판정을 <b>순수 함수</b>로 뽑아 여기서 못 박는다.
 *
 * @design INT-014
 */
import { describe, expect, it } from 'vitest';

import {
  MATERIALS_POLL_BUDGET_MS,
  MATERIALS_POLL_MS,
  materialsPollIntervalFor,
} from '../polling';
import { PortalMaterialsState } from '../types';

describe('소재 조달 폴링 판정', () => {
  it('진행_중이면_간격만큼_다시_묻는다', () => {
    expect(materialsPollIntervalFor(PortalMaterialsState.IN_PROGRESS, 0)).toBe(MATERIALS_POLL_MS);
    expect(materialsPollIntervalFor(PortalMaterialsState.IN_PROGRESS, 60_000)).toBe(
      MATERIALS_POLL_MS,
    );
  });

  it('★종결_상태에서는_묻지_않는다', () => {
    // 준비 완료·실패·미조달 어디서도 계속 돌 이유가 없다.
    expect(materialsPollIntervalFor(PortalMaterialsState.READY, 0)).toBe(false);
    expect(materialsPollIntervalFor(PortalMaterialsState.FAILED, 0)).toBe(false);
    expect(materialsPollIntervalFor(PortalMaterialsState.NOT_PROVISIONED, 0)).toBe(false);
  });

  it('아직_아무것도_못_받았으면_묻지_않는다', () => {
    expect(materialsPollIntervalFor(undefined, 0)).toBe(false);
  });

  it('★예산을_다_쓰면_진행_중이어도_멈춘다_무한_폴링_금지', () => {
    expect(
      materialsPollIntervalFor(PortalMaterialsState.IN_PROGRESS, MATERIALS_POLL_BUDGET_MS - 1),
    ).toBe(MATERIALS_POLL_MS);
    expect(
      materialsPollIntervalFor(PortalMaterialsState.IN_PROGRESS, MATERIALS_POLL_BUDGET_MS),
    ).toBe(false);
    expect(
      materialsPollIntervalFor(PortalMaterialsState.IN_PROGRESS, MATERIALS_POLL_BUDGET_MS * 10),
    ).toBe(false);
  });

  it('예산이_유한하고_간격이_양수다_상수를_0이나_무한으로_돌리는_변이를_잡는다', () => {
    expect(MATERIALS_POLL_MS).toBeGreaterThan(0);
    expect(Number.isFinite(MATERIALS_POLL_BUDGET_MS)).toBe(true);
    expect(MATERIALS_POLL_BUDGET_MS).toBeGreaterThan(MATERIALS_POLL_MS);
  });
});
