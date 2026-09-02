// 사양 SCREEN-022 — 증강 요청 조건 블록의 **자유 지시문 글자수 카운터(N/1000)** 회귀 가드.
//
// ⚠ 대상이 바뀌었다(2026-08-27, 연동명세서 v1.3 정합). 구 버전은 생성 조건 5필드가 자유 텍스트
// 입력이라 필드마다 카운터(N/50)를 달았는데, v1.3 이 다섯 축의 허용 코드를 닫으면서 그 자리는
// **드롭다운**이 되었다 — 선택에는 길이 개념이 없다. 카운터가 필요한 유일한 입력은 자유 지시문이다.
//
// 상한은 `AUGMENT_PROMPT_MAX_LENGTH`(BE `AugmentPrompts.MAX_PROMPT_LENGTH` 미러) 하나가 정한다.
// 화면에 `1000` 을 숫자로 박으면 상한이 바뀔 때 카운터가 거짓말을 하므로, 이 테스트는
// **상수에서 계산한 기대 문자열**로 단언한다(하드코딩 리터럴을 쓰지 않는다).

import { useState } from 'react';
import { describe, expect, it } from 'vitest';
import { fireEvent, render, screen, within } from '@testing-library/react';

import { AugmentPromptFieldset } from '@/features/augment/components/AugmentPromptFieldset';
import type { AugmentConditionDraft } from '@/features/augment/promptValidation';
import {
  AUGMENT_MTDT_FIELD_KEYS,
  AUGMENT_MTDT_FIELD_META,
  AUGMENT_PROMPT_MAX_LENGTH,
  createEmptyAugmentMtdt,
} from '@/features/augment/types';

const MAX = AUGMENT_PROMPT_MAX_LENGTH;

const emptyDraft = (): AugmentConditionDraft => ({
  mtdt: createEmptyAugmentMtdt(),
  prompt: '',
});

/**
 * 제어 컴포넌트라 입력이 반영되려면 값을 들고 있는 상태가 필요하다.
 * 테스트 대상은 블록 자체이므로 최소 상태 보유자만 둔다.
 */
function Harness() {
  const [value, setValue] = useState<AugmentConditionDraft>(emptyDraft);
  return (
    <AugmentPromptFieldset
      value={value}
      errors={{ mtdt: {} }}
      touched={{ mtdt: {} }}
      selectedPreset={null}
      onSelectPreset={() => {}}
      presetFilledFields={{}}
      onMtdtChange={(key, next) =>
        setValue((prev) => ({ ...prev, mtdt: { ...prev.mtdt, [key]: next } }))
      }
      onPromptChange={(next) => setValue((prev) => ({ ...prev, prompt: next }))}
    />
  );
}

/** 자유 지시문 블록 안에서 `N/MAX` 형식 카운터 노드를 찾는다(상한은 상수에서 만든다). */
function promptCounter(): HTMLElement {
  const block = screen.getByTestId('augment-free-prompt-block');
  return within(block).getByText(new RegExp(`^\\d+/${MAX}$`));
}

const promptInput = () => screen.getByLabelText('자유 지시문');

describe('AugmentPromptFieldset — 자유 지시문 글자수 카운터 (사양 SCREEN-022)', () => {
  it('자유_지시문에_글자수_카운터가_보인다', () => {
    render(<Harness />);

    expect(promptCounter().textContent).toBe(`0/${MAX}`);
  });

  it('입력_길이가_카운터에_반영된다', () => {
    render(<Harness />);

    fireEvent.change(promptInput(), { target: { value: '교차로 유지' } });

    expect(promptCounter().textContent).toBe(`6/${MAX}`);
  });

  it('카운터_상한은_하드코딩이_아니라_상수를_따른다', () => {
    render(<Harness />);

    // 표시 상한이 상수와 같고, 입력 자체의 상한(maxLength)도 같은 상수를 따른다.
    // (둘이 갈리면 카운터가 도달 불가능한 상한을 알리게 된다)
    expect(promptCounter().textContent).toBe(`0/${MAX}`);
    expect(promptInput()).toHaveAttribute('maxLength', String(MAX));
  });

  it('카운터가_오류_사유를_스크린리더에서_밀어내지_않는다', () => {
    render(
      <AugmentPromptFieldset
        value={{ ...emptyDraft(), prompt: 'A' }}
        errors={{ mtdt: {}, prompt: '1000자 이내로 입력하세요.' }}
        touched={{ mtdt: {}, prompt: true }}
        selectedPreset={null}
        onSelectPreset={() => {}}
        presetFilledFields={{}}
        onMtdtChange={() => {}}
        onPromptChange={() => {}}
      />,
    );

    // 입력의 aria-describedby 는 오류 문구를 가리킨다.
    const describedBy = promptInput().getAttribute('aria-describedby') ?? '';
    expect(describedBy).not.toBe('');
    const described = describedBy
      .split(/\s+/)
      .map((id) => document.getElementById(id))
      .filter((el): el is HTMLElement => el !== null);
    expect(described.some((el) => el.textContent?.includes('이내로'))).toBe(true);

    // 카운터는 낭독 대상이 아니다(공용 FieldCounter 규약 — 타이핑마다 읽히면 방해가 된다).
    const counter = promptCounter();
    expect(counter).toHaveAttribute('aria-hidden', 'true');
    // 그래서 describedby 로 연결돼 오류 앞에 끼어들지도 않는다.
    expect(described).not.toContain(counter);
  });

  it('생성_조건_5항목은_드롭다운이며_길이_카운터를_갖지_않는다', () => {
    // 구 회귀 방지 — 자유 입력으로 되돌아가면 허용 코드 밖 값을 보낼 수단이 생긴다.
    render(<Harness />);

    AUGMENT_MTDT_FIELD_KEYS.forEach((key) => {
      const field = screen.getByTestId(`aug-mtdt-${key}-field`);
      const control = screen.getByLabelText(
        new RegExp(AUGMENT_MTDT_FIELD_META[key].label),
      );
      expect(control.tagName, `${key} 항목`).toBe('SELECT');
      expect(
        within(field).queryByText(new RegExp('^\\d+/\\d+$')),
        `${key} 항목 카운터`,
      ).toBeNull();
    });
  });
});
