import { createRef } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
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

    // then: 1x 버튼이 활성 스타일(bg-primary-600)을 가져야 한다
    expect(btn1x.className).toContain('bg-primary-600');
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
    expect(btn2x.className).toContain('bg-primary-600');

    // then: 1x 버튼은 비활성 상태여야 한다
    const btn1x = screen.getByRole('button', { name: '1x' });
    expect(btn1x.className).not.toContain('bg-primary-600');
  });
});

describe('VideoPlayer 영상 길이 노출 (FE-1)', () => {
  function setVideoDuration(video: HTMLVideoElement, sec: number) {
    Object.defineProperty(video, 'duration', { configurable: true, value: sec });
  }

  it('메타데이터_로드시_실제_길이로_onDurationChange_호출', () => {
    // given
    const onDurationChange = vi.fn();
    render(<VideoPlayer src="/test.mp4" onDurationChange={onDurationChange} />);
    const video = document.querySelector('video') as HTMLVideoElement;

    // when: 실제 영상 길이(123.4s) 로드 후 loadedmetadata 발화
    setVideoDuration(video, 123.4);
    fireEvent.loadedMetadata(video);

    // then: 실제 길이로 콜백 호출
    expect(onDurationChange).toHaveBeenCalledWith(123.4);
  });

  it('duration이_NaN이면_onDurationChange_미호출(방어)', () => {
    // given
    const onDurationChange = vi.fn();
    render(<VideoPlayer src="/test.mp4" onDurationChange={onDurationChange} />);
    const video = document.querySelector('video') as HTMLVideoElement;

    // when: 일부 스트림은 duration 이 NaN
    setVideoDuration(video, NaN);
    fireEvent.loadedMetadata(video);

    // then: 잘못된 값은 통지하지 않는다
    expect(onDurationChange).not.toHaveBeenCalled();
  });

  it('getDuration_imperative_handle로_실제_길이_반환', () => {
    // given
    const ref = createRef<VideoPlayerHandle>();
    render(<VideoPlayer ref={ref} src="/test.mp4" />);
    const video = document.querySelector('video') as HTMLVideoElement;
    setVideoDuration(video, 88);

    // then
    expect(ref.current?.getDuration()).toBe(88);
  });
});

describe('VideoPlayer 버퍼링 스피너', () => {
  it('초기에는_버퍼링_스피너_미노출', () => {
    // given
    render(<VideoPlayer src="/test.mp4" />);

    // then: 재생 시작 전에는 스피너가 없다
    expect(screen.queryByTestId('video-buffering-spinner')).not.toBeInTheDocument();
  });

  it('waiting_이벤트시_스피너_노출_canplay시_해제', () => {
    // given
    render(<VideoPlayer src="/test.mp4" />);
    const video = document.querySelector('video') as HTMLVideoElement;

    // when: 버퍼 고갈(waiting) 발화
    fireEvent.waiting(video);

    // then: 버퍼링 스피너가 노출된다
    expect(screen.getByTestId('video-buffering-spinner')).toBeInTheDocument();

    // when: 재생 가능(canplay) 발화
    fireEvent.canPlay(video);

    // then: 스피너가 해제된다
    expect(screen.queryByTestId('video-buffering-spinner')).not.toBeInTheDocument();
  });
});
