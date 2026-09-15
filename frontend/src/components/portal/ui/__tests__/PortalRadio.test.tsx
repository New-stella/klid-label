/**
 * 포털 라디오 — 보이는 동그라미를 네이티브 입력에 맡기지 않는다는 계약의 가드.
 *
 * <h3>이 파일이 고정하는 것</h3>
 * <ul>
 *   <li><b>보이는 동그라미는 입력과 별개 요소다.</b> 포털 Host 스타일이 네이티브 라디오를 1px·clip 으로
 *       숨기므로(2026-09-15 개발망 실측), 입력이 곧 동그라미면 포털에서 선택지가 사라진다.
 *       jsdom 에는 Host 스타일이 없어 «사라진다»를 재현할 수 없다 — 그래서 **구조**로 단언한다:
 *       입력은 스스로 숨고(sr-only), 그 다음 형제가 보조기술에서 빠진 동그라미이며, 동그라미가
 *       입력 상태를 따르는 형제 선택자의 짝(peer)이다.</li>
 *   <li><b>네이티브 라디오 동작은 그대로다</b> — 접근 이름·선택 상태·같은 이름끼리 화살표 이동·비활성.</li>
 * </ul>
 *
 * @design DS-002
 * @design SCREEN-029
 * @design AC-1108
 */

import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';

import { Field, FieldLabel } from '@/components/common/Field';
import { PortalRadio } from '@/components/portal/ui/PortalRadio';
import { PortalRadioGroup } from '@/components/portal/ui/PortalRadioGroup';

/** 입력 하나에 대해 «보이는 동그라미가 입력과 별개로 선다»를 단언한다. */
function expectOwnDot(input: HTMLElement) {
  expect(input).toHaveAttribute('type', 'radio');
  // 입력은 스스로 숨는다 — Host 스타일 유무와 무관하게 같은 모양이 되도록 명시한다.
  expect(input.className.split(/\s+/)).toEqual(expect.arrayContaining(['sr-only', 'peer']));
  const dot = input.nextElementSibling;
  expect(dot).not.toBeNull();
  expect(dot?.tagName).toBe('SPAN');
  expect(dot).toHaveAttribute('data-portal-radio-dot');
  expect(dot).toHaveAttribute('aria-hidden', 'true');
  // 형제 선택자의 짝 — 선택·초점·비활성이 입력 상태에서 온다(React 상태 없이 따라온다).
  expect(dot?.className).toMatch(/\bpeer-checked:/);
  expect(dot?.className).toMatch(/\bpeer-focus-visible:/);
  expect(dot?.className).toMatch(/\bpeer-disabled:/);
  // 크기는 px — Host 루트 글자 크기 10px 에서 rem 은 줄어든다.
  expect(dot?.className).not.toMatch(/rem\b/);
}

describe('PortalRadio', () => {
  it('★입력과_별개로_보이는_동그라미를_그린다', () => {
    render(<PortalRadio name="shape" value="BBOX" label="박스" />);
    expectOwnDot(screen.getByRole('radio', { name: '박스' }));
  });

  it('라벨이_없어도_동그라미가_선다', () => {
    render(<PortalRadio name="shape" value="BBOX" aria-label="박스" />);
    expectOwnDot(screen.getByRole('radio', { name: '박스' }));
  });

  it('선택_상태와_변경_콜백은_네이티브_라디오_그대로다', async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    render(
      <>
        <PortalRadio name="shape" value="BBOX" label="박스" defaultChecked />
        <PortalRadio name="shape" value="POLYGON" label="폴리곤" onChange={onChange} />
      </>,
    );
    expect(screen.getByRole('radio', { name: '박스' })).toBeChecked();
    await user.click(screen.getByText('폴리곤'));
    expect(onChange).toHaveBeenCalledTimes(1);
    expect(screen.getByRole('radio', { name: '폴리곤' })).toBeChecked();
    expect(screen.getByRole('radio', { name: '박스' })).not.toBeChecked();
  });

  it('비활성이면_눌러도_선택되지_않는다', async () => {
    const user = userEvent.setup();
    render(<PortalRadio name="shape" value="BBOX" label="박스" disabled />);
    const radio = screen.getByRole('radio', { name: '박스' });
    expect(radio).toBeDisabled();
    await user.click(screen.getByText('박스'));
    expect(radio).not.toBeChecked();
  });

  it('id_를_주면_그_id_로_입력이_선다', () => {
    render(<PortalRadio id="ai-tool-shape-bbox" name="shape" label="박스" />);
    expect(screen.getByRole('radio', { name: '박스' })).toHaveAttribute('id', 'ai-tool-shape-bbox');
  });
});

describe('PortalRadioGroup', () => {
  const OPTIONS = [
    { value: 'review', label: '검토 후 수락' },
    { value: 'auto', label: '자동 반영' },
  ];

  function Controlled({ onChange }: { onChange?: (v: string) => void }) {
    const [value, setValue] = useState('review');
    return (
      <PortalRadioGroup
        name="apply"
        aria-label="트랙 결과 적용 방식"
        value={value}
        options={OPTIONS}
        onChange={(v) => {
          setValue(v);
          onChange?.(v);
        }}
      />
    );
  }

  it('★그룹_이름과_선택지마다_보이는_동그라미가_선다', () => {
    render(<Controlled />);
    const group = screen.getByRole('radiogroup', { name: '트랙 결과 적용 방식' });
    const radios = screen.getAllByRole('radio');
    expect(radios).toHaveLength(2);
    for (const r of radios) {
      expect(group).toContainElement(r);
      expectOwnDot(r);
    }
    expect(screen.getByRole('radio', { name: '검토 후 수락' })).toBeChecked();
  });

  it('★키보드_화살표로_선택이_옮겨간다_네이티브_라디오_그룹_동작', async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    render(<Controlled onChange={onChange} />);

    await user.tab();
    expect(screen.getByRole('radio', { name: '검토 후 수락' })).toHaveFocus();
    await user.keyboard('{ArrowRight}');

    expect(onChange).toHaveBeenLastCalledWith('auto');
    expect(screen.getByRole('radio', { name: '자동 반영' })).toBeChecked();
    expect(screen.getByRole('radio', { name: '자동 반영' })).toHaveFocus();
  });

  it('그룹_비활성은_모든_선택지를_막는다', () => {
    render(<PortalRadioGroup name="apply" aria-label="방식" options={OPTIONS} disabled />);
    for (const r of screen.getAllByRole('radio')) expect(r).toBeDisabled();
  });

  it('Field_안에서는_필드_라벨이_그룹_이름이_된다', () => {
    render(
      <Field>
        <FieldLabel>적용 방식</FieldLabel>
        <PortalRadioGroup name="apply" options={OPTIONS} defaultValue="auto" />
      </Field>,
    );
    expect(screen.getByRole('radiogroup', { name: '적용 방식' })).toBeInTheDocument();
    expect(screen.getByRole('radio', { name: '자동 반영' })).toBeChecked();
  });
});
