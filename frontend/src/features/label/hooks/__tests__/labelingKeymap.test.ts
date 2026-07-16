import { describe, expect, it } from 'vitest';

import { ToolType } from '../../types';
import {
  SHORTCUT_KEYMAP,
  auditKeymapConflicts,
  comboSignature,
  findBinding,
} from '../labelingKeymap';

describe('labelingKeymap — Rev.1.1 단축키 재배치 감사', () => {
  it('전체_키맵_충돌_0_감사', () => {
    // 재배치 후 어떤 두 액션도 같은 (수식키+키) 조합에 매핑되면 안 된다.
    // 같은 액션(id)에 여러 키(A/←, Del/R 등)를 붙이는 것은 충돌이 아니다.
    expect(auditKeymapConflicts(SHORTCUT_KEYMAP)).toEqual([]);
  });

  it('WASD_프레임_이동_바인딩_존재', () => {
    expect(findBinding('w')?.id).toBe('frame.first');
    expect(findBinding('a')?.id).toBe('frame.prev');
    expect(findBinding('s')?.id).toBe('frame.last');
    expect(findBinding('d')?.id).toBe('frame.next');
  });

  it('SAM추적은_Shift_T로_재배치되어_T와_충돌없음', () => {
    const track = SHORTCUT_KEYMAP.find((b) => b.tool === ToolType.TRACK);
    expect(track?.key).toBe('t');
    expect(track?.shift).toBe(true);

    // 평문 T 는 라벨 표시/숨김(action) — TRACK 과 수식키로 분리되어 충돌 없음.
    const plainT = findBinding('t'); // shift 미포함 조회
    expect(plainT?.id).toBe('label.toggleVisibility');
    expect(comboSignature(track!)).not.toBe(comboSignature(plainT!));
  });

  it('키포인트_K_유지', () => {
    expect(findBinding('k')?.tool).toBe(ToolType.KEYPOINT);
  });

  it('도구_S는_프레임이동과_분리되어_SELECT는_S에_없음', () => {
    // WASD 와 충돌 방지: SELECT 도구는 S 가 아니어야 한다.
    const s = findBinding('s');
    expect(s?.tool).toBeUndefined();
    expect(s?.id).toBe('frame.last');
    // SELECT 는 Esc 로 재배치.
    const select = SHORTCUT_KEYMAP.find((b) => b.tool === ToolType.SELECT);
    expect(select?.key).toBe('escape');
  });

  it('폴리곤_F_점추가_Q_자동완료_R_삭제_바인딩', () => {
    expect(findBinding('f')?.id).toBe('polygon.addPoint');
    expect(findBinding('q')?.id).toBe('polygon.complete');
    expect(findBinding('r')?.id).toBe('label.delete');
  });

  // ── 줌 단축키 회귀 (US 키보드에서 +/_ 는 shift 동반) ────────────────
  it('줌인_플러스는_shift_동반이라_shift로_매칭', () => {
    // US 키보드에서 '+' 는 Shift+'=' 로 입력되므로 shift 필드가 있어야 매칭된다.
    expect(findBinding('+', { shift: true })?.id).toBe('zoom.in');
    // shift 없는 '+' 조회는 매칭되지 않아야 한다(회귀 방지).
    expect(findBinding('+')).toBeUndefined();
  });

  it('줌인_등호는_무수식_매칭', () => {
    expect(findBinding('=')?.id).toBe('zoom.in');
  });

  it('줌아웃_언더스코어는_shift_동반이라_shift로_매칭', () => {
    // US 키보드에서 '_' 는 Shift+'-' 로 입력된다.
    expect(findBinding('_', { shift: true })?.id).toBe('zoom.out');
    expect(findBinding('_')).toBeUndefined();
  });

  it('줌아웃_하이픈은_무수식_매칭', () => {
    expect(findBinding('-')?.id).toBe('zoom.out');
  });
});
