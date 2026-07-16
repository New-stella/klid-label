// R2/R3 — 캔버스 뷰 조작 순수 로직: clampPan(팬 클램프) + shouldResetView(프레임 전환 뷰유지 판정).
// konva Stage 상호작용을 순수 함수 호출로 환원해 단위검증한다.

import { describe, expect, it } from 'vitest';

import { clampPan, shouldResetView } from '../useLabelStore';

describe('clampPan — 팬 클램프(뷰포트 이탈 방지)', () => {
  it('zoom1_fit에서는_팬이_no_op이다', () => {
    // fit(zoom=1): 한 축은 캔버스와 정확히 일치, 다른 축은 letterbox → 팬 입력이 있어도 (0,0).
    expect(clampPan(300, -200, 1, { w: 960, h: 540 }, { w: 1920, h: 1080 })).toEqual({ x: 0, y: 0 });
    // 비대칭 종횡비여도 fit 에서는 no-op.
    expect(clampPan(500, 500, 1, { w: 960, h: 540 }, { w: 1000, h: 1000 })).toEqual({ x: 0, y: 0 });
  });

  it('zoom_확대상태_드래그시_panXY가_클램프범위내로_갱신된다', () => {
    // 이미지 100×100, 뷰 200×200 → fit=2, zoom=2 → scale=4, scaledW=400, limit=(400-200)/2=100.
    // 한계 내 값은 그대로 통과.
    expect(clampPan(50, -50, 2, { w: 200, h: 200 }, { w: 100, h: 100 })).toEqual({ x: 50, y: -50 });
  });

  it('clampPan은_이미지가_뷰포트밖으로_완전이탈하지_않게_제한한다', () => {
    // 극단 팬 입력 → 초과분 절반(limit=100)으로 클램프.
    expect(clampPan(9999, -9999, 2, { w: 200, h: 200 }, { w: 100, h: 100 })).toEqual({
      x: 100,
      y: -100,
    });
    expect(clampPan(-9999, 9999, 2, { w: 200, h: 200 }, { w: 100, h: 100 })).toEqual({
      x: -100,
      y: 100,
    });
  });

  it('이미지_뷰_미확정(0크기)이면_안전하게_0반환', () => {
    expect(clampPan(100, 100, 2, { w: 0, h: 0 }, { w: 100, h: 100 })).toEqual({ x: 0, y: 0 });
    expect(clampPan(100, 100, 2, { w: 200, h: 200 }, { w: 0, h: 0 })).toEqual({ x: 0, y: 0 });
  });
});

describe('shouldResetView — 프레임 전환 시 뷰 유지/리셋 판정', () => {
  it('초기진입(이전_dims_미상)은_fit으로_리셋', () => {
    expect(shouldResetView(null, { videoId: 7, width: 1920, height: 1080 })).toBe(true);
    expect(shouldResetView(undefined, { videoId: 7, width: 1920, height: 1080 })).toBe(true);
  });

  it('동일영상_동일해상도_프레임이동시_zoom_pan이_유지된다', () => {
    // 뷰 유지 = reset 안 함(false).
    expect(
      shouldResetView(
        { videoId: 7, width: 1920, height: 1080 },
        { videoId: 7, width: 1920, height: 1080 },
      ),
    ).toBe(false);
  });

  it('해상도상이_또는_영상변경시_뷰가_fit으로_리셋된다', () => {
    // 해상도 상이
    expect(
      shouldResetView(
        { videoId: 7, width: 1920, height: 1080 },
        { videoId: 7, width: 1280, height: 720 },
      ),
    ).toBe(true);
    // 영상 변경
    expect(
      shouldResetView(
        { videoId: 7, width: 1920, height: 1080 },
        { videoId: 9, width: 1920, height: 1080 },
      ),
    ).toBe(true);
  });
});
