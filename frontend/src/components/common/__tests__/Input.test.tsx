import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useForm } from 'react-hook-form';

import { Field, FieldError, FieldLabel } from '../Field';
import { Input } from '../Input';

describe('Input', () => {
  // 시안 `--border-strong`(= `--n-4` = gray-400). 흰 배경 위 gray-300 은 2.01:1 로
  // WCAG 1.4.11(비텍스트 3:1) 미달이고 gray-400 은 3.08:1 이다. Textarea·Button secondary 가
  // 같은 근거로 이미 gray-400 이라, 이 값이 갈리면 한 폼 안에서 테두리 두 종류가 섞인다.
  it('Input_경계는_시안_border_strong_대비를_따른다', () => {
    render(<Input aria-label="이름" />);
    const cls = screen.getByLabelText('이름').className;

    expect(cls, '경계는 --border-strong(gray-400)이다').toMatch(/\bborder-gray-400\b/);
    expect(cls, '대비 미달인 gray-300 으로 되돌리지 말 것').not.toMatch(/\bborder-gray-300\b/);
  });

  it('Input_에러_메시지_aria_describedby_연결', () => {
    // 조합형 전환 후에도 접근성 연결은 Field 조립부가 자동으로 잇는다.
    render(
      <Field>
        <FieldLabel>이메일</FieldLabel>
        <Input id="email" />
        <FieldError>필수 항목입니다</FieldError>
      </Field>,
    );
    const input = screen.getByLabelText('이메일');
    const describedBy = input.getAttribute('aria-describedby');
    expect(describedBy).toBeTruthy();
    expect(input).toHaveAttribute('aria-invalid', 'true');
    const errorEl = document.getElementById(describedBy!);
    expect(errorEl).toHaveTextContent('필수 항목입니다');
  });

  it('Input_검증에러_아이콘_병기', () => {
    // given/when: 검증 에러가 있는 Input 렌더
    render(
      <Field>
        <FieldLabel>비밀번호</FieldLabel>
        <Input id="pw" />
        <FieldError>필수 항목입니다</FieldError>
      </Field>,
    );

    // then: 에러 메시지(role=alert)에 경고 아이콘(svg)이 텍스트와 병기 (색만으로 구분 금지)
    const alert = screen.getByRole('alert');
    expect(alert).toHaveTextContent('필수 항목입니다');
    expect(alert.querySelector('svg')).toBeTruthy();
  });

  it('Input_react_hook_form_register와_연동', async () => {
    const user = userEvent.setup();
    function Form() {
      const { register, watch } = useForm<{ name: string }>({ defaultValues: { name: '' } });
      const name = watch('name');
      return (
        <div>
          <Field>
            <FieldLabel>이름</FieldLabel>
            <Input id="name" {...register('name')} />
          </Field>
          <span data-testid="value">{name}</span>
        </div>
      );
    }
    render(<Form />);
    await user.type(screen.getByLabelText('이름'), '홍길동');
    expect(screen.getByTestId('value')).toHaveTextContent('홍길동');
  });

  it('Input_은_라벨_오류_문구를_스스로_렌더하지_않는다', () => {
    // 조합형 계약(UI-002) — 프리미티브 단독 렌더 시 문구 요소가 전혀 없어야 한다.
    const { container } = render(<Input aria-label="검색" />);
    expect(container.querySelector('label')).toBeNull();
    expect(screen.queryByRole('alert')).toBeNull();
    // 래퍼 div 없이 네이티브 input 자신이 루트다.
    expect(container.firstElementChild?.tagName).toBe('INPUT');
  });
});
