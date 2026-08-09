import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { Checkbox } from '../Checkbox';
import {
  Field,
  FieldDescription,
  FieldError,
  FieldGroup,
  FieldLabel,
  FieldLegend,
  FieldSeparator,
  FieldSet,
  FieldTitle,
} from '../Field';
import { Input } from '../Input';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '../Select';
import { Textarea } from '../Textarea';

/**
 * 조합형 전환(UI-099)의 회귀 가드.
 *
 * 이 리팩터의 가장 흔한 실패 유형은 "문구는 밖으로 나갔는데 **접근성 연결이 함께 끊기는 것**"이다
 * — 라벨↔입력(`htmlFor`/`id`), 오류의 `role=alert`·`aria-describedby`·`aria-invalid`.
 * 아래는 그 연결 자체를 단언한다.
 */
describe('Field 조립부 — 라벨·설명·오류', () => {
  it('FieldLabel_은_htmlFor_로_입력과_연결된다', async () => {
    const user = userEvent.setup();
    render(
      <Field>
        <FieldLabel>이름</FieldLabel>
        <Input />
      </Field>,
    );
    const input = screen.getByLabelText('이름');
    const label = screen.getByText('이름');
    expect(label.getAttribute('for')).toBe(input.getAttribute('id'));
    expect(input.getAttribute('id')).toBeTruthy();

    // 라벨을 클릭하면 포커스가 입력으로 이동한다(htmlFor 연결의 실동작 확인).
    await user.click(label);
    expect(document.activeElement).toBe(input);
  });

  it('호출부가_입력에_id_를_명시해도_라벨_연결이_유지된다', () => {
    // 컨텍스트 자동 id 와 호출부 명시 id 가 갈리면 라벨이 조용히 끊긴다.
    render(
      <Field>
        <FieldLabel>제목</FieldLabel>
        <Input id="notice-title" />
      </Field>,
    );
    const input = screen.getByLabelText('제목');
    expect(input).toHaveAttribute('id', 'notice-title');
    expect(screen.getByText('제목').getAttribute('for')).toBe('notice-title');
  });

  it('FieldError_는_role_alert_이며_입력과_aria_describedby_로_연결된다', () => {
    render(
      <Field>
        <FieldLabel>이메일</FieldLabel>
        <Input />
        <FieldError>필수 항목입니다</FieldError>
      </Field>,
    );
    const input = screen.getByLabelText('이메일');
    const alert = screen.getByRole('alert');
    expect(alert).toHaveTextContent('필수 항목입니다');
    expect(input.getAttribute('aria-describedby')).toBe(alert.getAttribute('id'));
    expect(input).toHaveAttribute('aria-invalid', 'true');
  });

  it('FieldDescription_도_aria_describedby_로_연결된다', () => {
    render(
      <Field>
        <FieldLabel>지자체코드</FieldLabel>
        <Input />
        <FieldDescription>숫자 1~10자리</FieldDescription>
      </Field>,
    );
    const input = screen.getByLabelText('지자체코드');
    const desc = screen.getByText('숫자 1~10자리');
    expect(input.getAttribute('aria-describedby')).toBe(desc.getAttribute('id'));
    // 오류가 없으면 invalid 로 표시하지 않는다.
    expect(input).not.toHaveAttribute('aria-invalid');
  });

  it('오류와_설명이_함께_있으면_오류를_가리킨다', () => {
    // 조합형 전환 이전 프리미티브의 `errorId ?? hintId` 규칙을 그대로 옮긴 것.
    render(
      <Field>
        <FieldLabel>지자체코드</FieldLabel>
        <Input />
        <FieldDescription>숫자 1~10자리</FieldDescription>
        <FieldError>필수 항목입니다</FieldError>
      </Field>,
    );
    const input = screen.getByLabelText('지자체코드');
    expect(input.getAttribute('aria-describedby')).toBe(
      screen.getByRole('alert').getAttribute('id'),
    );
  });

  it('FieldError_는_내용이_없으면_빈_alert_로_남지_않는다', () => {
    render(
      <Field>
        <FieldLabel>이메일</FieldLabel>
        <Input />
        <FieldError>{undefined}</FieldError>
      </Field>,
    );
    expect(screen.queryByRole('alert')).toBeNull();
    expect(screen.getByLabelText('이메일')).not.toHaveAttribute('aria-describedby');
    expect(screen.getByLabelText('이메일')).not.toHaveAttribute('aria-invalid');
  });

  it('FieldError_는_서로_다른_메시지가_2건_이상이면_목록으로_렌더한다', () => {
    render(
      <Field>
        <FieldLabel>이메일</FieldLabel>
        <Input />
        <FieldError errors={[{ message: '필수 항목입니다' }, { message: '형식이 올바르지 않습니다' }]} />
      </Field>,
    );
    const alert = screen.getByRole('alert');
    expect(alert.querySelectorAll('li')).toHaveLength(2);
  });

  it('FieldError_는_같은_메시지가_반복되면_한_번만_보여준다', () => {
    render(
      <Field>
        <FieldLabel>이메일</FieldLabel>
        <Input />
        <FieldError errors={[{ message: '필수 항목입니다' }, { message: '필수 항목입니다' }]} />
      </Field>,
    );
    const alert = screen.getByRole('alert');
    expect(alert.querySelectorAll('li')).toHaveLength(0);
    expect(alert).toHaveTextContent('필수 항목입니다');
  });

  it('FieldLabel_required_는_시각표시와_스크린리더_안내를_함께_낸다', () => {
    // 색·기호만으로 정보를 전달하지 않는다.
    render(
      <Field>
        <FieldLabel required>제목</FieldLabel>
        <Input />
      </Field>,
    );
    expect(screen.getByLabelText(/제목/)).toBeInTheDocument();
    expect(screen.getByText('(필수)').className).toMatch(/sr-only/);
  });

  it('Select_Textarea_Checkbox_도_같은_방식으로_연결된다', () => {
    render(
      <>
        <Field>
          <FieldLabel>상태</FieldLabel>
          <Select value="A" onValueChange={() => {}}>
            <SelectTrigger>
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="A">대기</SelectItem>
            </SelectContent>
          </Select>
          <FieldError>상태를 고르세요</FieldError>
        </Field>
        <Field>
          <FieldLabel>사유</FieldLabel>
          <Textarea />
          <FieldError>사유를 입력하세요</FieldError>
        </Field>
        <Field orientation="horizontal">
          <Checkbox />
          <FieldLabel>상단 고정</FieldLabel>
        </Field>
      </>,
    );
    const select = screen.getByLabelText('상태');
    const textarea = screen.getByLabelText('사유');
    const checkbox = screen.getByLabelText('상단 고정');
    expect(select).toHaveAttribute('aria-invalid', 'true');
    expect(select.getAttribute('aria-describedby')).toBeTruthy();
    expect(textarea).toHaveAttribute('aria-invalid', 'true');
    expect(textarea.getAttribute('aria-describedby')).toBeTruthy();
    // Checkbox 는 3상태(mixed)를 표현해야 해서 네이티브 input 이 아니라 role=checkbox 인
    // button 이다 — Field 의 htmlFor↔id 연결은 button 도 labelable 이라 그대로 성립한다.
    expect(checkbox.tagName).toBe('BUTTON');
    expect(checkbox).toHaveAttribute('role', 'checkbox');
  });

  it('호출부가_명시한_aria_invalid_는_Field_판정보다_우선한다', () => {
    render(
      <Field>
        <FieldLabel>이메일</FieldLabel>
        <Input aria-invalid={false} />
        <FieldError>필수 항목입니다</FieldError>
      </Field>,
    );
    expect(screen.getByLabelText('이메일')).toHaveAttribute('aria-invalid', 'false');
  });

  it('Field_밖에서_쓰면_컨텍스트_없이도_동작한다', () => {
    render(<Input aria-label="검색" />);
    const input = screen.getByLabelText('검색');
    expect(input.getAttribute('id')).toBeTruthy();
    expect(input).not.toHaveAttribute('aria-describedby');
  });

  it('FieldSet_FieldLegend_FieldGroup_FieldTitle_FieldSeparator_렌더', () => {
    render(
      <FieldGroup>
        <FieldSet>
          <FieldLegend>인입 정보</FieldLegend>
          <Field>
            <FieldLabel>지자체명</FieldLabel>
            <Input />
          </Field>
        </FieldSet>
        <FieldSeparator>또는</FieldSeparator>
        <FieldTitle>배치 단계</FieldTitle>
      </FieldGroup>,
    );
    expect(screen.getByRole('group', { name: '인입 정보' })).toBeInTheDocument();
    expect(screen.getByText('또는')).toBeInTheDocument();
    expect(screen.getByText('배치 단계')).toBeInTheDocument();
  });
});
