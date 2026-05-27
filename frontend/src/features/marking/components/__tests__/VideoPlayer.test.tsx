import { createRef } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { VideoPlayer, type VideoPlayerHandle } from '../VideoPlayer';

// jsdom 에서 <video> play/pause 미지원 — stub
window.HTMLMediaElement.prototype.play = vi.fn().mockResolvedValue(undefined);
window.HTMLMediaElement.prototype.pause = vi.fn();

describe('VideoPlayer 배속 설정', () => {
  const SPEED_OPTIONS = [0.25, 0.5, 1, 1.5, 2, 4];

  it('배속_버튼_6개_모두_렌더링됨', () => {
    // given
    render(<VideoPlayer src="/test.mp4" />);

    // when & then
    for (const rate of SPEED_OPTIONS) {
      expect(screen.getByRole('button', { name: `${rate}x` })).toBeInTheDocument();
    }
  });

  it('초기_배속은_1x_활성상태', () => {
    // given
    render(<VideoPlayer src="/test.mp4" />);

    // when
    const btn1x = screen.getByRole('button', { name: '1x' });

    // then: 1x 버튼이 활성 스타일(bg-blue-600)을 가져야 한다
    expect(btn1x.className).toContain('bg-blue-600');
  });

  it('배속_버튼_클릭시_playbackRate_변경', async () => {
    // given
    const ref = createRef<VideoPlayerHandle>();
    render(<VideoPlayer ref={ref} src="/test.mp4" />);
    const video = document.querySelector('video') as HTMLVideoElement;
    const user = userEvent.setup();

    // when: 2x 버튼 클릭
    const btn2x = screen.getByRole('button', { name: '2x' });
    await user.click(btn2x);

    // then: video.playbackRate 가 2 로 설정되어야 한다
    expect(video.playbackRate).toBe(2);

    // then: 2x 버튼이 활성 상태여야 한다
    expect(btn2x.className).toContain('bg-blue-600');

    // then: 1x 버튼은 비활성 상태여야 한다
    const btn1x = screen.getByRole('button', { name: '1x' });
    expect(btn1x.className).not.toContain('bg-blue-600');
  });
});
