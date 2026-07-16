import { describe, expect, it } from 'vitest';

import { SHORTCUT_KEYMAP, auditKeymapConflicts, findBinding } from '../labelingKeymap';

describe('labelingKeymap — 복사/붙여넣기 (Phase 4)', () => {
  it('클립보드_바인딩_추가_후에도_충돌_0', () => {
    expect(auditKeymapConflicts(SHORTCUT_KEYMAP)).toEqual([]);
  });

  it('Ctrl_C_선택복사_Ctrl_Shift_C_전체복사', () => {
    expect(findBinding('c', { ctrl: true })?.id).toBe('clipboard.copy');
    expect(findBinding('c', { ctrl: true, shift: true })?.id).toBe('clipboard.copyAll');
  });

  it('Ctrl_V_와_Ctrl_Shift_V_모두_붙여넣기_별칭', () => {
    expect(findBinding('v', { ctrl: true })?.id).toBe('clipboard.paste');
    expect(findBinding('v', { ctrl: true, shift: true })?.id).toBe('clipboard.paste');
  });

  it('평문_c_v는_클립보드_액션_아님', () => {
    // 수식키 없는 c/v 는 클립보드 바인딩과 분리(브라우저 기본 입력 보호).
    expect(findBinding('c')).toBeUndefined();
    expect(findBinding('v')).toBeUndefined();
  });
});
