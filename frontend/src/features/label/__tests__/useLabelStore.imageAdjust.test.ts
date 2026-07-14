import { beforeEach, describe, expect, it } from 'vitest';

import { DEFAULT_IMAGE_ADJUST, useLabelStore } from '@/stores/useLabelStore';

describe('useLabelStore — imageAdjust 세션 상태 (2c)', () => {
  beforeEach(() => {
    useLabelStore.getState().reset();
  });

  it('default_원본값_중립', () => {
    const adj = useLabelStore.getState().imageAdjust;
    expect(adj).toEqual(DEFAULT_IMAGE_ADJUST);
    // 밝기/대비 가운데=0, 투명도 기본 1.
    expect(adj.brightness).toBe(0);
    expect(adj.contrast).toBe(0);
    expect(adj.labelOpacity).toBe(1);
    expect(adj.activeOpacity).toBe(1);
  });

  it('setImageAdjust_부분패치_반영', () => {
    useLabelStore.getState().setImageAdjust({ brightness: 0.4 });
    expect(useLabelStore.getState().imageAdjust.brightness).toBe(0.4);
    // 나머지는 유지.
    expect(useLabelStore.getState().imageAdjust.contrast).toBe(0);

    useLabelStore.getState().setImageAdjust({ labelOpacity: 0.5 });
    expect(useLabelStore.getState().imageAdjust.labelOpacity).toBe(0.5);
    expect(useLabelStore.getState().imageAdjust.brightness).toBe(0.4);
  });

  it('조절값_세션전용_영속안됨_reset시_default복원', () => {
    useLabelStore.getState().setImageAdjust({ brightness: 0.9, contrast: 50, labelOpacity: 0.2 });
    // reset() = 새 세션/프레임 진입 시 원본값으로 복원 (영속 계층 없음).
    useLabelStore.getState().reset();
    expect(useLabelStore.getState().imageAdjust).toEqual(DEFAULT_IMAGE_ADJUST);
  });

  it('resetImageAdjust_다른상태_보존하고_조절값만_복원', () => {
    useLabelStore.getState().setZoom(3);
    useLabelStore.getState().setImageAdjust({ brightness: 0.7 });
    useLabelStore.getState().resetImageAdjust();
    expect(useLabelStore.getState().imageAdjust).toEqual(DEFAULT_IMAGE_ADJUST);
    // 조절값만 복원 — zoom 등 다른 상태는 유지.
    expect(useLabelStore.getState().zoom).toBe(3);
  });
});
