// 메타 탭 도움말 선택의 기억 — <b>기본이 감춤</b>이고 창의 도움말과 <b>다른 키</b>다.
// [@design SCREEN-005] [@design SCREEN-019]
//
// ★두 축을 짝으로 센다. 「기본이 감춤이다」만 두면 저장·복원이 통째로 죽어도 통과하고,
//   「기억한다」만 두면 기본값이 조용히 뒤집혀도 통과한다.

import { afterEach, describe, expect, it } from 'vitest';

import { ANNOTATION_HELP_STORAGE_KEY } from '../annotationHelpPreference';
import {
  META_HELP_STORAGE_KEY,
  readMetaHelpVisible,
  writeMetaHelpVisible,
} from '../metaHelpPreference';

afterEach(() => {
  localStorage.clear();
});

describe('메타 탭 도움말 기억', () => {
  it('★기본은_감춤이다_창의_도움말과_반대다', () => {
    // 폭 360px 탭에서 설명이 값을 밀어낸다는 사용자 지적(2026-09-15)의 직접 반영이다.
    expect(readMetaHelpVisible()).toBe(false);
  });

  it('★저장_키가_창의_도움말과_다르다', () => {
    // 한 키를 공유하면 기본값이 서로 반대라 한쪽을 끈 선택이 다른 쪽에서는 <b>켠</b> 선택이 된다.
    expect(META_HELP_STORAGE_KEY).not.toBe(ANNOTATION_HELP_STORAGE_KEY);

    // 값 축으로도 센다 — 이름만 다르고 같은 칸을 쓰면 위 단언은 통과하는데 간섭은 남는다.
    writeMetaHelpVisible(true);
    expect(localStorage.getItem(ANNOTATION_HELP_STORAGE_KEY)).toBeNull();
  });

  it('켠_선택을_기억하고_되돌리면_기본값으로_돌아간다', () => {
    writeMetaHelpVisible(true);
    expect(readMetaHelpVisible()).toBe(true);

    writeMetaHelpVisible(false);
    expect(readMetaHelpVisible()).toBe(false);
    // 기본값은 저장소에 적지 않는다 — 적어 두면 기본값이 바뀔 때 옛 선택이 그것을 덮는다.
    expect(localStorage.getItem(META_HELP_STORAGE_KEY)).toBeNull();
  });

  it('저장소를_읽지_못해도_기본값으로_동작한다', () => {
    const original = Object.getOwnPropertyDescriptor(window, 'localStorage');
    Object.defineProperty(window, 'localStorage', {
      configurable: true,
      get() {
        throw new Error('저장소 접근 거부(사생활 보호 모드 등)');
      },
    });
    try {
      // 못 읽었다고 설명이 쏟아지면 안 된다 — fail-closed 쪽이 기본값이다.
      expect(readMetaHelpVisible()).toBe(false);
      expect(() => writeMetaHelpVisible(true)).not.toThrow();
    } finally {
      if (original) Object.defineProperty(window, 'localStorage', original);
    }
  });
});
