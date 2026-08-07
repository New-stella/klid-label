import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';

import { Field, FieldLabel } from '../Field';
import { Textarea } from '../Textarea';

describe('Textarea', () => {
  it('Textarea_min_44px', () => {
    // KRDS 터치 타깃 44px — textarea 최소 높이 min-h-11 명시
    render(
      <Field>
        <FieldLabel>설명</FieldLabel>
        <Textarea />
      </Field>,
    );
    const ta = screen.getByLabelText('설명');
    expect(ta.className).toMatch(/min-h-11/);
  });

  it('Textarea_rows_기본값을_두지_않는다', () => {
    // UI-027: 자동 확장이 기본 동작이므로 고정 rows 를 심지 않는다.
    // `rows` 가 붙어 있으면 field-sizing:content 미지원 브라우저에서 자동 확장 대신
    // 고정 높이가 이기고, 지원 브라우저에서는 죽은 속성이 된다.
    render(<Textarea aria-label="설명" />);
    const ta = screen.getByLabelText('설명');
    expect(ta.hasAttribute('rows')).toBe(false);
  });

  it('Textarea_호출부가_지정한_rows_는_그대로_전달된다', () => {
    // 기본값만 폐지했을 뿐 prop 자체를 막지 않는다(하위호환).
    render(<Textarea aria-label="설명" rows={3} />);
    expect(screen.getByLabelText('설명')).toHaveAttribute('rows', '3');
  });

  it('Textarea_자동확장_선언과_상한이_함께_적용된다', () => {
    // jsdom 은 field-sizing 을 계산하지 않아 실제 높이 증가를 단언할 수 없다 —
    // 자동 확장 선언(field-sizing:content)과 그 상한(max-h)이 함께 걸렸는지로 가드한다.
    // 상한이 없으면 공통 Modal(스크롤 컨테이너 아님)이 뷰포트를 넘어 조작 불가가 된다.
    render(<Textarea aria-label="설명" />);
    const cls = screen.getByLabelText('설명').className;
    expect(cls).toMatch(/\[field-sizing:content\]/);
    expect(cls).toMatch(/max-h-/);
    expect(cls).toMatch(/min-h-11/);
  });

  it('Textarea_호출부_className_이_기본_최소높이를_이긴다', () => {
    // rows 대신 className 으로 기본 크기를 재지정하는 계약(UI-027).
    render(<Textarea aria-label="설명" className="min-h-[236px]" />);
    const cls = screen.getByLabelText('설명').className;
    expect(cls).toMatch(/min-h-\[236px\]/);
    expect(cls).not.toMatch(/min-h-11/);
  });

  it('Textarea_는_라벨_오류_문구를_스스로_렌더하지_않는다', () => {
    // 조합형 계약(UI-027)
    const { container } = render(<Textarea aria-label="설명" />);
    expect(container.querySelector('label')).toBeNull();
    expect(screen.queryByRole('alert')).toBeNull();
    expect(container.firstElementChild?.tagName).toBe('TEXTAREA');
  });
});
