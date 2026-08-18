// R4·R5 — 「폐기 프레임 저장 확인」이 세는 축.
//
// 이 저장이 <b>실어 보내는</b> 폐기 전환만 센다. 보내지 않는 값(서버가 알아서 정하는 프레임)은
// 세지 않는다 — 세면 화면이 서버 동작을 추정해 말하게 된다.

import { describe, expect, it } from 'vitest';

import {
  NO_DISCARD_CHANGE,
  hasDiscardChange,
  summarizeDiscardSave,
} from '../discardSaveSummary';

describe('summarizeDiscardSave — 이번 저장이 바꾸는 폐기 상태', () => {
  it('폐기로_전환되는_프레임은_빠지는_수로_센다', () => {
    // given/when
    const s = summarizeDiscardSave([{ baseline: 'N', next: 'Y' }]);

    // then
    expect(s).toEqual({ discarding: 1, restoring: 0 });
  });

  it('폐기가_해제되는_프레임은_되돌아오는_수로_센다', () => {
    // given/when
    const s = summarizeDiscardSave([{ baseline: 'Y', next: 'N' }]);

    // then
    expect(s).toEqual({ discarding: 0, restoring: 1 });
  });

  it('기준값과_같은_값은_변경이_아니다', () => {
    // given: 되돌려 놓은 전환을 "변경"으로 세면 확인 모달이 거짓말이 된다.
    // when
    const s = summarizeDiscardSave([
      { baseline: 'Y', next: 'Y' },
      { baseline: 'N', next: 'N' },
    ]);

    // then
    expect(s).toEqual(NO_DISCARD_CHANGE);
  });

  it('폐기_축을_싣지_않는_저장은_세지_않는다', () => {
    // given: next=null 은 "이 저장에 폐기여부 필드가 없다"는 뜻이다(현재 값 유지 규약).
    // when
    const s = summarizeDiscardSave([{ baseline: 'N', next: null }]);

    // then
    expect(hasDiscardChange(s)).toBe(false);
  });

  it('기준값을_모르면_보내는_값_그대로_센다', () => {
    // given: null 은 "폐기 아님"이 아니라 "모름"이다(응답이 축을 싣지 않은 경우).
    //   저장은 값을 실제로 보내므로, 알리지 않는 쪽보다 알리는 쪽을 택한다.
    // when
    const s = summarizeDiscardSave([
      { baseline: null, next: 'Y' },
      { baseline: null, next: 'N' },
    ]);

    // then
    expect(s).toEqual({ discarding: 1, restoring: 1 });
  });

  it('여러_프레임의_전환을_방향별로_합산한다', () => {
    // given: 회차를 불러온 뒤 확정 저장하면 여러 프레임의 전환이 한 번에 실린다.
    // when
    const s = summarizeDiscardSave([
      { baseline: 'N', next: 'Y' },
      { baseline: 'N', next: 'Y' },
      { baseline: 'Y', next: 'N' },
      { baseline: 'N', next: 'N' },
    ]);

    // then
    expect(s).toEqual({ discarding: 2, restoring: 1 });
  });

  it('변경이_하나도_없으면_확인을_받지_않는다', () => {
    // given/when/then: 모든 저장에 확인을 끼우면 작업 흐름이 망가진다.
    expect(hasDiscardChange(summarizeDiscardSave([]))).toBe(false);
    expect(hasDiscardChange({ discarding: 0, restoring: 1 })).toBe(true);
  });
});
