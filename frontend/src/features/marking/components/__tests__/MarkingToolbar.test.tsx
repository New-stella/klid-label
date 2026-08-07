import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';

import { MarkingToolbar } from '../MarkingToolbar';

describe('MarkingToolbar intervalFrames', () => {
  const defaultProps = {
    mode: 'AUTO' as const,
    intervalFrames: 30,
    onModeChange: vi.fn(),
    onIntervalFramesChange: vi.fn(),
    onSubmit: vi.fn(),
    onClear: vi.fn(),
    markCount: 0,
  };

  it('자동모드_간격_라벨에_프레임_표시', () => {
    // given
    render(<MarkingToolbar {...defaultProps} />);

    // when & then: "간격(프레임)" 텍스트가 존재해야 한다
    expect(screen.getByText(/간격\(프레임\)/)).toBeInTheDocument();
  });

  it('자동모드_간격_입력에는_상한이_없다_서버가_백스톱한다', () => {
    // given: 사양(SCREEN-006) — "1 이상 정수, 상한 없음(하한만 검증)". 구 버그:
    // 클라이언트에 max={3600} 을 박아 두어 서버 상한의 두 번째 진실원이 되어 있었다.
    render(<MarkingToolbar {...defaultProps} />);

    // when
    const input = screen.getByRole('spinbutton');

    // then: max 속성이 없어야 한다 (min=1 하한만 유지)
    expect(input).not.toHaveAttribute('max');
    expect(input).toHaveAttribute('min', '1');
  });

  it('이벤트명_입력란이_없다_자동소싱', () => {
    // given — 이벤트명은 영상의 evntTypeCd 에서 자동 소싱되므로 입력란이 없어야 한다.
    render(<MarkingToolbar {...defaultProps} />);

    // when & then
    expect(screen.queryByPlaceholderText('이벤트명')).not.toBeInTheDocument();
  });

  it('자동모드_완료버튼_활성화_intervalFrames유효', () => {
    // given — 이벤트명 가드가 사라졌으므로 자동 모드는 intervalFrames 유효 시 활성화.
    render(<MarkingToolbar {...defaultProps} />);

    // when & then
    expect(screen.getByRole('button', { name: /마킹 완료/ })).toBeEnabled();
  });

  it('수동모드_완료버튼_마크0건이면_비활성화', () => {
    // given
    render(<MarkingToolbar {...defaultProps} mode="MANUAL" markCount={0} />);

    // when & then
    expect(screen.getByRole('button', { name: /마킹 완료/ })).toBeDisabled();
  });

  it('수동모드_완료버튼_마크1건이상이면_활성화', () => {
    // given
    render(<MarkingToolbar {...defaultProps} mode="MANUAL" markCount={1} />);

    // when & then
    expect(screen.getByRole('button', { name: /마킹 완료/ })).toBeEnabled();
  });
});
