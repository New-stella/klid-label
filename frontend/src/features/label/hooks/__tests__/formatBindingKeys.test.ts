// R1 — formatBindingKeys: SHORTCUT_KEYMAP(단일 출처)에서 액션 id 의 (수식키+키)를
// 사람 표기('Shift+T','Esc','Ctrl+S','B')로 파생. 툴바 툴팁이 키맵과 100% 일치하도록 하는 근거.

import { describe, expect, it } from 'vitest';

import { formatBindingKeys } from '../labelingKeymap';

describe('formatBindingKeys — 키맵 파생 사람표기', () => {
  it('formatBindingKeys가_수식키조합을_사람표기로_반환한다', () => {
    expect(formatBindingKeys('edit.save')).toBe('Ctrl+S');
    expect(formatBindingKeys('tool.track')).toBe('Shift+T');
    expect(formatBindingKeys('tool.select')).toBe('Esc');
    expect(formatBindingKeys('tool.bbox')).toBe('B');
  });

  it('도구_id별_대표_단축키가_확정셋과_일치한다', () => {
    // 정답셋: 선택=Esc, BBOX=B, 폴리곤=P, SAM분할=G, SAM추적=Shift+T, 키포인트=K, 표시/숨김=T
    expect(formatBindingKeys('tool.polygon')).toBe('P');
    expect(formatBindingKeys('tool.samSegment')).toBe('G');
    expect(formatBindingKeys('tool.keypoint')).toBe('K');
    expect(formatBindingKeys('label.toggleVisibility')).toBe('T');
    expect(formatBindingKeys('edit.undo')).toBe('Ctrl+Z');
  });

  it('별칭이_여럿이면_대표_1개를_반환한다_삭제는_Del', () => {
    // label.delete = r / delete / backspace — 이름있는 특수키(Del)를 대표로.
    expect(formatBindingKeys('label.delete')).toBe('Del');
  });

  it('nav_프레임이동은_화살표별칭이_아닌_WASD_문자키를_대표로_한다', () => {
    // frame.prev = A/← , frame.next = D/→ → 주키 A/D 우선(← / → 아님).
    expect(formatBindingKeys('frame.prev')).toBe('A');
    expect(formatBindingKeys('frame.next')).toBe('D');
    expect(formatBindingKeys('frame.first')).toBe('W');
    expect(formatBindingKeys('frame.last')).toBe('S');
  });

  it('미등록_id는_빈문자열', () => {
    expect(formatBindingKeys('nope.unknown')).toBe('');
  });
});
