// R4·R5 — 프레임 폐기·복원 UI.
//
// 확정 사양(SCREEN-005):
//  · 캔버스 상단 옵션바 — "지금 보고 있는 프레임을 학습데이터에서 빼거나 도로 넣는다. 누르면 화면
//    표시만 바뀌고 저장을 눌러야 확정된다."
//  · 프레임 썸네일 띠 — "폐기한 프레임은 썸네일을 흐리게 낮추고 폐기 표식을 얹어 목록에서 바로
//    구분되게 한다. 목록에서 빼지는 않는다 — 빼면 복원할 자리를 찾을 수 없다."
//  · D2 — 내부 화면의 프레임 수는 폐기분을 빼지 않는다.

import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';

import { renderWithProviders } from '@/test/renderWithProviders';

vi.mock('../../hooks/useImageBlob', () => ({
  useImageBlob: () => ({ url: null, loading: false, error: null }),
}));

import { CanvasOptionBar } from '../CanvasOptionBar';
import { FrameFilmstrip } from '../FrameFilmstrip';
import { DscdYn, type FrameSummary } from '../../types';

function frame(srcSn: number, frameNo: number): FrameSummary {
  return { srcSn, frameNo, thumbnailUrl: '', imageUrl: '' };
}

function renderOptionBar(overrides: Partial<Parameters<typeof CanvasOptionBar>[0]> = {}) {
  const onToggleDiscard = vi.fn();
  const utils = renderWithProviders(
    <CanvasOptionBar
      frameIndex={0}
      frameCount={3}
      onRequestGoTo={vi.fn()}
      srcSn={100}
      labels={[]}
      onRequestSave={vi.fn()}
      dscdYn={DscdYn.N}
      discardPending={false}
      onToggleDiscard={onToggleDiscard}
      {...overrides}
    />,
  );
  return { ...utils, onToggleDiscard };
}

describe('프레임 폐기·복원 — 캔버스 상단 옵션바', () => {
  it('사용_중인_프레임에는_폐기_버튼이_보인다', () => {
    // given/when
    renderOptionBar();

    // then
    expect(screen.getByTestId('frame-discard-toggle')).toHaveTextContent('프레임 폐기');
  });

  it('폐기된_프레임에는_복원_버튼이_보인다', () => {
    // given/when
    renderOptionBar({ dscdYn: DscdYn.Y });

    // then: 같은 자리에서 반대 동작으로 바뀐다 — 복원 경로를 잃지 않는다.
    expect(screen.getByTestId('frame-discard-toggle')).toHaveTextContent('프레임 복원');
  });

  it('누르면_상위에_전환을_요청한다', async () => {
    // given
    const { onToggleDiscard } = renderOptionBar();
    const user = userEvent.setup();

    // when
    await user.click(screen.getByTestId('frame-discard-toggle'));

    // then: 버튼은 서버를 직접 부르지 않는다 — 확정은 저장이 한다(D8).
    expect(onToggleDiscard).toHaveBeenCalledTimes(1);
  });

  it('미저장_전환은_저장해야_확정된다는_것을_알린다', () => {
    // given/when
    renderOptionBar({ dscdYn: DscdYn.Y, discardPending: true });

    // then: 색이 아니라 글자로 알린다(색 단독 구분 금지).
    expect(screen.getByTestId('frame-discard-pending')).toHaveTextContent('저장해야');
  });

  it('전환하지_않았으면_미저장_안내를_띄우지_않는다', () => {
    // given/when
    renderOptionBar({ discardPending: false });

    // then
    expect(screen.queryByTestId('frame-discard-pending')).toBeNull();
  });

  it('폐기_전환_경로는_잠금_상태에서_닫힌다', () => {
    // given: 재비식별 대기 잠금은 저장을 막으므로 폐기도 확정될 수 없다 — 누르게 두면 무반응이 된다.
    renderOptionBar({ locked: true });

    // then
    expect(screen.getByTestId('frame-discard-toggle')).toBeDisabled();
  });

  it('폐기_전환_콜백이_없으면_버튼을_두지_않는다', () => {
    // given: 포털 등 폐기 축이 없는 화면. 눌러도 아무 일 없는 버튼을 두지 않는다.
    renderOptionBar({ onToggleDiscard: undefined });

    // then
    expect(screen.queryByTestId('frame-discard-toggle')).toBeNull();
  });
});

describe('프레임 폐기·복원 — 썸네일 띠', () => {
  const frames = [frame(100, 0), frame(101, 1), frame(102, 2)];

  it('폐기된_프레임도_목록에서_빠지지_않는다', () => {
    // given/when: 빼면 복원할 자리를 찾을 수 없다.
    render(
      <FrameFilmstrip
        frames={frames}
        currentIndex={0}
        onSelect={vi.fn()}
        discardedSrcSns={new Set([101])}
      />,
    );

    // then: D2 — 총량은 줄지 않는다.
    expect(screen.getAllByRole('option')).toHaveLength(3);
  });

  it('폐기된_프레임에만_폐기_표식이_붙는다', () => {
    // given/when
    render(
      <FrameFilmstrip
        frames={frames}
        currentIndex={0}
        onSelect={vi.fn()}
        discardedSrcSns={new Set([101])}
      />,
    );

    // then
    const thumbs = screen.getAllByRole('option');
    expect(within(thumbs[1]).getByText('폐기')).toBeInTheDocument();
    expect(within(thumbs[0]).queryByText('폐기')).toBeNull();
  });

  it('폐기_여부가_썸네일의_접근성_이름에도_담긴다', () => {
    // given/when: 흐림 처리(시각)만으로는 보조기술 사용자가 구분할 수 없다.
    render(
      <FrameFilmstrip
        frames={frames}
        currentIndex={0}
        onSelect={vi.fn()}
        discardedSrcSns={new Set([101])}
      />,
    );

    // then
    expect(screen.getByRole('option', { name: '프레임 1 (폐기)' })).toBeInTheDocument();
    expect(screen.getByRole('option', { name: '프레임 2' })).toBeInTheDocument();
  });
});
