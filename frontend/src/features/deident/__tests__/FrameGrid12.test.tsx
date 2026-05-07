import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { FrameGrid12 } from '../components/FrameGrid12';

const frames = Array.from({ length: 12 }, (_, i) => ({
  srcSn: i + 1,
  frameNo: i + 1,
  originalUrl: `/o/${i + 1}.jpg`,
  processedUrl: i === 5 ? undefined : `/d/${i + 1}.jpg`, // 6번째는 비식별 실패
}));

describe('FrameGrid12', () => {
  it('비식별_실패_프레임_하단_슬롯_비식별_이미지_없음_표시', () => {
    render(
      <FrameGrid12
        frames={frames}
        selectedSrcSn={1}
        onSelect={() => undefined}
      />,
    );
    // srcSn=6 (idx 5) processed missing
    const missing = screen.getByTestId('frame-pair-6-processed');
    expect(missing).toHaveTextContent('비식별 이미지 없음');
  });

  it('12_프레임_그리드_라디오_단일_선택_큰_비교_뷰_갱신', async () => {
    const user = userEvent.setup();
    const onSelect = vi.fn();
    render(
      <FrameGrid12
        frames={frames}
        selectedSrcSn={1}
        onSelect={onSelect}
      />,
    );

    // 라디오는 하나만 checked (radiogroup 단일 선택)
    const radios = screen.getAllByRole('radio');
    expect(radios).toHaveLength(12);
    const checked = radios.filter((r) => (r as HTMLInputElement).checked);
    expect(checked).toHaveLength(1);

    // 다른 프레임 클릭 시 onSelect 호출
    await user.click(screen.getByTestId('frame-pair-3'));
    expect(onSelect).toHaveBeenCalledWith(3);
  });

  it('pairLabel_커스텀_원본_증강_표시', () => {
    render(
      <FrameGrid12
        frames={frames.slice(0, 1)}
        selectedSrcSn={1}
        onSelect={() => undefined}
        pairLabel={{ top: '원본', bottom: '증강(겨울)' }}
      />,
    );
    expect(screen.getByText('원본')).toBeInTheDocument();
    expect(screen.getByText('증강(겨울)')).toBeInTheDocument();
  });

  it('frames_12개_초과시_상위_12개만_렌더링', () => {
    const many = Array.from({ length: 20 }, (_, i) => ({
      srcSn: i + 1,
      frameNo: i + 1,
      originalUrl: `/o/${i + 1}.jpg`,
      processedUrl: `/d/${i + 1}.jpg`,
    }));
    render(<FrameGrid12 frames={many} selectedSrcSn={null} onSelect={() => undefined} />);
    const radios = screen.getAllByRole('radio');
    expect(radios).toHaveLength(12);
  });
});
