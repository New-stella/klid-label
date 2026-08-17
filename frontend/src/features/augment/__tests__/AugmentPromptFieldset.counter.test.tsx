// 사양 SCREEN-022 — 증강 생성 조건 5필드의 **글자수 카운터(N/50)** 회귀 가드.
//
// 상한은 `AUGMENT_PROMPT_MAX_LENGTH`(BE `PromptFields.MAX_FIELD_LENGTH` 미러) 하나가 정한다.
// 화면에 `50` 을 숫자로 박으면 상한이 바뀔 때 카운터가 거짓말을 하므로, 이 테스트는
// **상수에서 계산한 기대 문자열**로 단언한다(하드코딩 리터럴을 쓰지 않는다).

import { useState } from 'react';
import { describe, expect, it } from 'vitest';
import { fireEvent, render, screen, within } from '@testing-library/react';

import { AugmentPromptFieldset } from '@/features/augment/components/AugmentPromptFieldset';
import {
  AUGMENT_PROMPT_FIELD_KEYS,
  AUGMENT_PROMPT_FIELD_META,
  AUGMENT_PROMPT_MAX_LENGTH,
  createEmptyAugmentPrompt,
  type AugmentPromptFieldKey,
  type AugmentPromptFields,
} from '@/features/augment/types';

const MAX = AUGMENT_PROMPT_MAX_LENGTH;

/**
 * 제어 컴포넌트라 입력이 반영되려면 값을 들고 있는 상태가 필요하다.
 * 테스트 대상은 필드셋 자체이므로 최소 상태 보유자만 둔다.
 */
function Harness() {
  const [value, setValue] = useState<AugmentPromptFields>(createEmptyAugmentPrompt());
  return (
    <AugmentPromptFieldset
      value={value}
      errors={{}}
      touched={{}}
      onChange={(key, next) => setValue((prev) => ({ ...prev, [key]: next }))}
      onBlur={() => {}}
    />
  );
}

/** 그 필드 블록 안에서 `N/MAX` 형식 카운터 노드를 찾는다(상한은 상수에서 만든다). */
function counterOf(key: AugmentPromptFieldKey): HTMLElement {
  const field = screen.getByTestId(`aug-prompt-${key}-field`);
  return within(field).getByText(new RegExp(`^\\d+/${MAX}$`));
}

const inputOf = (key: AugmentPromptFieldKey) =>
  screen.getByLabelText(new RegExp(AUGMENT_PROMPT_FIELD_META[key].label));

describe('AugmentPromptFieldset — 생성 조건 글자수 카운터 (사양 SCREEN-022)', () => {
  it('생성조건_5필드_모두에_글자수_카운터가_보인다', () => {
    // given / when
    render(<Harness />);

    // then: 일부만 붙으면 사용자가 나머지는 제한이 없다고 읽는다 — 5필드 전부에 있어야 한다.
    AUGMENT_PROMPT_FIELD_KEYS.forEach((key) => {
      expect(counterOf(key).textContent, `${key} 필드 카운터`).toBe(`0/${MAX}`);
    });
  });

  it('입력_길이가_카운터에_반영된다', () => {
    // given
    render(<Harness />);

    // when
    fireEvent.change(inputOf('terrain'), { target: { value: '교차로' } });

    // then
    expect(counterOf('terrain').textContent).toBe(`3/${MAX}`);
    // 다른 필드는 영향받지 않는다.
    expect(counterOf('time').textContent).toBe(`0/${MAX}`);
  });

  it('카운터_상한은_하드코딩이_아니라_상수를_따른다', () => {
    // given / when
    render(<Harness />);

    // then: 표시 상한이 상수와 같고, 입력 자체의 상한(maxLength)도 같은 상수를 따른다.
    //       (둘이 갈리면 카운터가 도달 불가능한 상한을 알리게 된다)
    AUGMENT_PROMPT_FIELD_KEYS.forEach((key) => {
      expect(counterOf(key).textContent).toBe(`0/${MAX}`);
      expect(inputOf(key)).toHaveAttribute('maxLength', String(MAX));
    });
  });

  it('카운터가_오류_사유를_스크린리더에서_밀어내지_않는다', () => {
    // given: 사용자가 건드린 필드에 오류가 있는 상태
    render(
      <AugmentPromptFieldset
        value={{ ...createEmptyAugmentPrompt(), season: '   ' }}
        errors={{ season: '공백만 입력할 수 없습니다.' }}
        touched={{ season: true }}
        onChange={() => {}}
        onBlur={() => {}}
      />,
    );

    // then: 입력의 aria-describedby 는 여전히 오류 문구를 가리킨다.
    const input = inputOf('season');
    const describedBy = input.getAttribute('aria-describedby') ?? '';
    expect(describedBy).not.toBe('');
    const described = describedBy
      .split(/\s+/)
      .map((id) => document.getElementById(id))
      .filter((el): el is HTMLElement => el !== null);
    expect(described.some((el) => el.textContent?.includes('공백만'))).toBe(true);

    // 카운터는 낭독 대상이 아니다(공용 FieldCounter 규약 — 타이핑마다 읽히면 방해가 된다).
    const counter = counterOf('season');
    expect(counter).toHaveAttribute('aria-hidden', 'true');
    // 그래서 describedby 로 연결돼 오류 앞에 끼어들지도 않는다.
    expect(described).not.toContain(counter);
  });
});
