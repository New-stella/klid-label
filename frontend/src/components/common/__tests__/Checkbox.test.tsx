import { useState } from 'react';
import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { Checkbox } from '../Checkbox';
import { Field, FieldLabel } from '../Field';

/** 3상태를 순환시키는 제어 하네스 — unchecked → checked → indeterminate → unchecked. */
function TriState({ initial = false }: { initial?: boolean | 'indeterminate' }) {
  const [checked, setChecked] = useState<boolean | 'indeterminate'>(initial);
  return (
    <>
      <Field orientation="horizontal">
        <Checkbox checked={checked} onCheckedChange={(v) => setChecked(v)} />
        <FieldLabel>전체 선택</FieldLabel>
      </Field>
      <button type="button" onClick={() => setChecked('indeterminate')}>
        부분선택으로
      </button>
    </>
  );
}

describe('Checkbox', () => {
  it('Checkbox_label_클릭으로_토글', async () => {
    const user = userEvent.setup();
    render(<TriState />);
    const cb = screen.getByLabelText('전체 선택');
    expect(cb).toHaveAttribute('aria-checked', 'false');
    await user.click(screen.getByText('전체 선택'));
    expect(cb).toHaveAttribute('aria-checked', 'true');
  });

  it('Checkbox_3상태_전이와_mixed_노출', async () => {
    // UI-024: checked='indeterminate' 는 aria-checked='mixed' 로 매핑되어야
    // 스크린리더가 "부분선택"을 인지한다. 별도 indeterminate prop 은 두지 않는다.
    const user = userEvent.setup();
    render(<TriState />);
    const cb = screen.getByRole('checkbox');

    expect(cb).toHaveAttribute('aria-checked', 'false');

    await user.click(cb);
    expect(cb).toHaveAttribute('aria-checked', 'true');

    // 표 전체선택이 "일부만 선택됨"으로 바뀐 상황
    await user.click(screen.getByRole('button', { name: '부분선택으로' }));
    expect(cb).toHaveAttribute('aria-checked', 'mixed');
    expect(cb).toHaveAttribute('data-state', 'indeterminate');

    // mixed 에서 클릭하면 체크로 확정된다(부분선택 → 전체선택).
    await user.click(cb);
    expect(cb).toHaveAttribute('aria-checked', 'true');
  });

  it('Checkbox_비제어_모드도_토글된다', async () => {
    const user = userEvent.setup();
    render(<Checkbox aria-label="선택" />);
    const cb = screen.getByRole('checkbox');
    expect(cb).toHaveAttribute('aria-checked', 'false');
    await user.click(cb);
    expect(cb).toHaveAttribute('aria-checked', 'true');
  });

  it('Checkbox_Space_키로_토글된다', async () => {
    // 네이티브 button 기반이라 Space 토글은 브라우저 기본 동작(UI-024).
    const user = userEvent.setup();
    render(<Checkbox aria-label="선택" />);
    const cb = screen.getByRole('checkbox');
    cb.focus();
    await user.keyboard('[Space]');
    expect(cb).toHaveAttribute('aria-checked', 'true');
  });

  it('Checkbox_클릭영역_44px', () => {
    // given/when: 라벨이 붙은 체크박스 렌더
    render(
      <Field orientation="horizontal">
        <Checkbox />
        <FieldLabel>동의합니다</FieldLabel>
      </Field>,
    );
    // then: 컨트롤 행과 라벨 행 모두 최소 44px — 조합형으로 갈라져도 양쪽이 유지된다.
    const cb = screen.getByRole('checkbox');
    expect(cb.className).toMatch(/min-h-11/);
    // 라벨이 폭을 만들어 주므로 컨트롤은 44px 폭을 예약하지 않는다(구 동작과 동일).
    expect(cb.className).not.toMatch(/min-w-11/);
    expect(screen.getByText('동의합니다').className).toMatch(
      /group-data-\[orientation=horizontal\]\/field:min-h-11/,
    );
  });

  it('Checkbox_label_없을때_44px_폭_가드', () => {
    // given/when: 라벨 없는 체크박스(표 전체선택·이력 행 선택 등) 렌더
    render(<Checkbox aria-label="선택" />);
    // then: 넓혀 줄 라벨이 없으므로 컨트롤 자신이 44×44 히트영역을 예약한다.
    const cb = screen.getByRole('checkbox');
    expect(cb.className).toMatch(/min-h-11/);
    expect(cb.className).toMatch(/min-w-11/);
  });

  it('Checkbox_44px_히트영역을_가진_요소_자체가_클릭으로_토글된다', async () => {
    // jsdom 은 레이아웃이 없어 "44px 안쪽을 눌렀다"를 좌표로 검증할 수 없다. 대신
    // **44px 을 가진 요소와 실제로 토글되는 요소가 동일한지**를 본다 —
    // 히트영역을 클릭이 전달되지 않는 래퍼(span 등)로 옮기면 이 가드가 깨진다.
    // (구 구현의 `::before` 오버레이가 Firefox 에서 죽었던 것과 같은 계열의 결함)
    const user = userEvent.setup();
    const { container } = render(<Checkbox aria-label="선택" />);

    const hitAreas = Array.from(container.querySelectorAll('*')).filter((el) =>
      el.className.toString().includes('min-h-11'),
    );
    expect(hitAreas).toHaveLength(1);
    const hitArea = hitAreas[0] as HTMLElement;
    expect(hitArea).toHaveAttribute('role', 'checkbox');

    await user.click(hitArea);
    expect(hitArea).toHaveAttribute('aria-checked', 'true');
  });

  it('Checkbox_시각_사각형은_히트영역보다_작다', () => {
    // 44px 히트영역과 시각 크기(정사각 5)를 분리 책임진다는 UI-024 규약.
    render(<Checkbox aria-label="선택" />);
    const box = screen.getByRole('checkbox').querySelector('span');
    expect(box?.className).toMatch(/\bh-5\b/);
    expect(box?.className).toMatch(/\bw-5\b/);
    // 시각 사각형은 보조기술에 중복 노출되지 않는다.
    expect(box).toHaveAttribute('aria-hidden', 'true');
  });

  it('Checkbox_disabled_면_클릭해도_토글되지_않는다', async () => {
    const user = userEvent.setup();
    render(<Checkbox aria-label="선택" disabled />);
    const cb = screen.getByRole('checkbox');
    await user.click(cb);
    expect(cb).toHaveAttribute('aria-checked', 'false');
    expect(cb).toBeDisabled();
  });

  it('Checkbox_포커스링_KRDS', () => {
    // given/when: 체크박스 렌더
    render(<Checkbox aria-label="동의합니다" />);
    // then: KRDS focus(3px ring + 2px offset + primary) 적용
    const cls = screen.getByRole('checkbox').className;
    expect(cls).toMatch(/focus-visible:ring-\[3px\]/);
    expect(cls).toMatch(/focus-visible:ring-offset-2/);
    expect(cls).toMatch(/focus-visible:ring-primary/);
  });

  it('Checkbox_는_라벨_텍스트와_오류_문구를_스스로_렌더하지_않는다', () => {
    // 조합형 계약(UI-024) — 시각 사각형은 남지만 문구는 전혀 렌더하지 않는다.
    const { container } = render(<Checkbox aria-label="선택" />);
    expect(container.textContent).toBe('');
    expect(screen.queryByRole('alert')).toBeNull();
  });
});
