import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { TimeseriesTextPanel } from '../components/TimeseriesTextPanel';

describe('TimeseriesTextPanel', () => {
  it('vlmText_값을_textarea에_렌더링', () => {
    // given
    const vlmText = '09:00 맑음, 차량 3대 진입';

    // when
    render(<TimeseriesTextPanel vlmText={vlmText} onChange={() => {}} />);

    // then
    const textarea = screen.getByRole('textbox');
    expect(textarea).toHaveValue(vlmText);
  });

  it('텍스트_입력시_onChange_호출', async () => {
    // given
    const onChange = vi.fn();
    const user = userEvent.setup();
    render(<TimeseriesTextPanel vlmText="" onChange={onChange} />);

    // when
    const textarea = screen.getByRole('textbox');
    await user.type(textarea, 'A');

    // then
    expect(onChange).toHaveBeenCalledWith('A');
  });

  it('disabled_상태에서_입력_불가', () => {
    // given / when
    render(<TimeseriesTextPanel vlmText="test" onChange={() => {}} disabled />);

    // then
    const textarea = screen.getByRole('textbox');
    expect(textarea).toBeDisabled();
  });

  it('글자수_카운터_표시', () => {
    // given
    const vlmText = '12345'; // 5글자

    // when
    render(<TimeseriesTextPanel vlmText={vlmText} onChange={() => {}} />);

    // then
    expect(screen.getByText('5/5000')).toBeInTheDocument();
  });

  it('maxLength_5000_속성_설정', () => {
    // given / when
    render(<TimeseriesTextPanel vlmText="" onChange={() => {}} />);

    // then
    const textarea = screen.getByRole('textbox');
    expect(textarea).toHaveAttribute('maxLength', '5000');
  });

  it('aria_label_설정', () => {
    // given / when
    render(<TimeseriesTextPanel vlmText="" onChange={() => {}} />);

    // then
    expect(screen.getByLabelText('AI 시계열 메타')).toBeInTheDocument();
  });

  // 사용자 노출 문구에는 기술 모델명을 쓰지 않고 AI 로 통일한다(YOLO 탐지 → AI 탐지 와 동일 원칙).
  // 제목·안내문·aria-label 어디로도 모델명이 다시 새어 들어오면 이 테스트가 물어야 한다.
  it('사용자_노출_문구에_기술_모델명이_없다', () => {
    // given / when
    const { container } = render(<TimeseriesTextPanel vlmText="" onChange={() => {}} />);

    // then — 보이는 텍스트 + aria-label 양쪽 모두 검사한다.
    expect(container.textContent).not.toMatch(/VLM/i);
    expect(screen.queryByLabelText(/VLM/i)).not.toBeInTheDocument();
    expect(screen.getByLabelText('AI 시계열 메타 입력')).toBeInTheDocument();
    expect(screen.getByText(/외부 AI 가 자동 생성한 시계열 정보입니다/)).toBeInTheDocument();
  });

  it('XSS_입력이_이스케이프_되어_렌더링', () => {
    // given — React는 자동 escape하므로 스크립트 태그가 텍스트로 표시
    const xssInput = '<script>alert("xss")</script>';

    // when
    render(<TimeseriesTextPanel vlmText={xssInput} onChange={() => {}} />);

    // then
    const textarea = screen.getByRole('textbox');
    expect(textarea).toHaveValue(xssInput);
    // script 태그가 실행되지 않고 값으로만 존재
    expect(document.querySelector('script')).toBeNull();
  });
});
