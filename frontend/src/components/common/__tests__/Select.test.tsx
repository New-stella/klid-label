import { useState } from 'react';
import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { selectRadixOption } from '@/test/selectTestUtils';

import { Field, FieldLabel } from '../Field';
import {
  Select,
  SelectContent,
  SelectGroup,
  SelectItem,
  SelectLabel,
  SelectSeparator,
  SelectTrigger,
  SelectValue,
} from '../Select';

/**
 * Select 조합형 전환(UI-003)의 회귀 가드.
 *
 * 옵션 배열을 통째로 받는 `options` prop 은 없다 — 호출부가 `SelectItem` 자식으로 직접 매핑한다.
 * 빈 문자열 값("선택 안 함"/"전체")은 Select 경계에서만 내부 센티넬로 왕복 변환되고, 호출부가
 * 보는 값(state/onValueChange 인자)은 항상 `''` 그대로다.
 */
describe('Select 조합형', () => {
  it('SelectItem_선택_시_onValueChange_로_값을_받는다', async () => {
    const user = userEvent.setup();
    let value = '';
    render(
      <Field>
        <FieldLabel>채널</FieldLabel>
        <Select value={value} onValueChange={(v) => (value = v)}>
          <SelectTrigger>
            <SelectValue placeholder="선택" />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="A">관제</SelectItem>
            <SelectItem value="B">포털</SelectItem>
          </SelectContent>
        </Select>
      </Field>,
    );
    const trigger = screen.getByLabelText('채널');
    await selectRadixOption(user, trigger, '포털');
    expect(value).toBe('B');
  });

  it('빈_문자열_값_옵션을_선택_가능하다_placeholder와_구분된다', async () => {
    // 회귀 지점: Radix 는 SelectItem value="" 를 placeholder 예약값으로 취급해 그대로 넘기면
    // "선택됨"과 "미선택"이 트리거에서 구분되지 않는다. 내부 센티넬 왕복이 이를 막는다.
    const user = userEvent.setup();
    let value: string | null = null;
    function Harness() {
      const [v, setV] = useState('A');
      value = v;
      return (
        <Select value={v} onValueChange={(next: string) => setV(next)}>
          <SelectTrigger aria-label="필터">
            <SelectValue placeholder="선택하세요" />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="">전체</SelectItem>
            <SelectItem value="A">관제</SelectItem>
          </SelectContent>
        </Select>
      );
    }
    render(<Harness />);
    const trigger = screen.getByRole('combobox', { name: '필터' });
    // 선택 전: 값 'A' 이므로 트리거에 "관제"가 표시된다(placeholder 아님).
    expect(trigger).toHaveTextContent('관제');
    expect(trigger).not.toHaveAttribute('data-placeholder');

    await selectRadixOption(user, trigger, '전체');
    expect(value).toBe(''); // 호출부는 센티넬이 아니라 '' 를 그대로 받는다.
    expect(trigger).toHaveTextContent('전체'); // placeholder 로 대체되지 않고 "전체" 라벨이 보인다.
    expect(trigger).not.toHaveAttribute('data-placeholder');
  });

  it('미선택_상태에서는_placeholder_가_보이고_data-placeholder_속성이_붙는다', () => {
    render(
      <Select value="" onValueChange={() => {}}>
        <SelectTrigger aria-label="상태">
          <SelectValue placeholder="상태를 선택하세요" />
        </SelectTrigger>
        <SelectContent>
          <SelectItem value="OPEN">진행중</SelectItem>
        </SelectContent>
      </Select>,
    );
    const trigger = screen.getByRole('combobox', { name: '상태' });
    expect(trigger).toHaveTextContent('상태를 선택하세요');
    expect(trigger).toHaveAttribute('data-placeholder');
    // placeholder 는 data-placeholder 속성을 통해 muted 색으로 처리된다(accessibility_notes).
    expect(trigger.className).toMatch(/data-\[placeholder\]:text-gray-400/);
  });

  it('SelectTrigger_는_기본(default) 크기에서_44px_KRDS_최소_터치_타깃을_보장한다', () => {
    render(
      <Select value="" onValueChange={() => {}}>
        <SelectTrigger aria-label="크기 확인">
          <SelectValue placeholder="선택" />
        </SelectTrigger>
        <SelectContent>
          <SelectItem value="A">A</SelectItem>
        </SelectContent>
      </Select>,
    );
    const trigger = screen.getByRole('combobox', { name: '크기 확인' });
    expect(trigger.className).toMatch(/\bh-11\b/);
  });

  it('SelectTrigger_size_sm_은_44px_미만을_허용하는_밀집_배치_예외다', () => {
    render(
      <Select value="" onValueChange={() => {}}>
        <SelectTrigger aria-label="sm 크기" size="sm">
          <SelectValue placeholder="선택" />
        </SelectTrigger>
        <SelectContent>
          <SelectItem value="A">A</SelectItem>
        </SelectContent>
      </Select>,
    );
    const trigger = screen.getByRole('combobox', { name: 'sm 크기' });
    expect(trigger.className).not.toMatch(/\bh-11\b/);
  });

  it('aria-invalid_전달_시_트리거_보더가_destructive_로_전환된다', () => {
    render(
      <Select value="" onValueChange={() => {}}>
        <SelectTrigger aria-label="오류 확인" aria-invalid>
          <SelectValue placeholder="선택" />
        </SelectTrigger>
        <SelectContent>
          <SelectItem value="A">A</SelectItem>
        </SelectContent>
      </Select>,
    );
    const trigger = screen.getByRole('combobox', { name: '오류 확인' });
    expect(trigger).toHaveAttribute('aria-invalid', 'true');
    expect(trigger.className).toMatch(/border-danger/);
  });

  it('오류가_없으면_기본_보더를_사용한다', () => {
    render(
      <Select value="" onValueChange={() => {}}>
        <SelectTrigger aria-label="정상 확인">
          <SelectValue placeholder="선택" />
        </SelectTrigger>
        <SelectContent>
          <SelectItem value="A">A</SelectItem>
        </SelectContent>
      </Select>,
    );
    const trigger = screen.getByRole('combobox', { name: '정상 확인' });
    expect(trigger.className).not.toMatch(/border-danger/);
    expect(trigger.className).toMatch(/border-gray-300/);
  });

  it('그룹_라벨_구분선을_SelectGroup_SelectLabel_SelectSeparator_로_조립할_수_있다', async () => {
    const user = userEvent.setup();
    render(
      <Select value="" onValueChange={() => {}}>
        <SelectTrigger aria-label="그룹 확인">
          <SelectValue placeholder="선택" />
        </SelectTrigger>
        <SelectContent>
          <SelectGroup>
            <SelectLabel>내부</SelectLabel>
            <SelectItem value="A">관제</SelectItem>
          </SelectGroup>
          <SelectSeparator />
          <SelectGroup>
            <SelectLabel>외부</SelectLabel>
            <SelectItem value="B">포털</SelectItem>
          </SelectGroup>
        </SelectContent>
      </Select>,
    );
    await user.click(screen.getByRole('combobox', { name: '그룹 확인' }));
    expect(await screen.findByText('내부')).toBeInTheDocument();
    expect(screen.getByText('외부')).toBeInTheDocument();
    expect(screen.getByRole('option', { name: '관제' })).toBeInTheDocument();
  });

  it('disabled_SelectItem_은_선택되지_않는다', async () => {
    const user = userEvent.setup();
    let value = 'A';
    render(
      <Select value={value} onValueChange={(v) => (value = v)}>
        <SelectTrigger aria-label="비활성 옵션">
          <SelectValue placeholder="선택" />
        </SelectTrigger>
        <SelectContent>
          <SelectItem value="A">관제</SelectItem>
          <SelectItem value="B" disabled>
            포털(비활성)
          </SelectItem>
        </SelectContent>
      </Select>,
    );
    const trigger = screen.getByRole('combobox', { name: '비활성 옵션' });
    await user.click(trigger);
    const disabledOption = await screen.findByRole('option', { name: '포털(비활성)' });
    expect(disabledOption).toHaveAttribute('aria-disabled', 'true');
    await user.click(disabledOption);
    expect(value).toBe('A');
  });

  it('Select_는_라벨_오류_문구를_스스로_렌더하지_않는다', () => {
    render(
      <Select value="" onValueChange={() => {}}>
        <SelectTrigger aria-label="채널">
          <SelectValue placeholder="선택" />
        </SelectTrigger>
        <SelectContent>
          <SelectItem value="A">관제</SelectItem>
        </SelectContent>
      </Select>,
    );
    expect(screen.queryByRole('alert')).toBeNull();
  });
});
