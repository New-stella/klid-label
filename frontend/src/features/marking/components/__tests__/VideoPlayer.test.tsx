import { createRef } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
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

/**
 * <b>이 컴포넌트와 &lt;video&gt; 사이의 이음매</b>. [@design API-114] [@design SCREEN-006]
 *
 * 이 축이 어떤 시험으로도 지켜지지 않고 있었다 — 화면 시험 둘이 이 컴포넌트를 대역으로 갈아끼우고
 * 이 파일에는 `src`·`onSrcError` 단정이 0건이라, `src={undefined}` 로 바꾸는 변이가 마킹 관련
 * 57건을 전부 통과했다(실측). 대역은 그대로 두되 이음매는 여기서 한 번 못박는다.
 */
describe('VideoPlayer ↔ <video> 이음매', () => {
  it('★받은_src가_video_요소에_그대로_실린다', () => {
    // given: 서명이 붙은 주소(쿼리까지 그대로 가야 한다 — 하나라도 빠지면 서버가 거절한다)
    const src = '/label-studio/api/v1/videos/42/stream?exp=9999999999&u=2001&sig=testsig';

    render(<VideoPlayer src={src} />);

    // then: 요소가 실제로 그 주소를 물고 있다. getAttribute 로 본다 — video.src 는 절대주소로
    //       해석돼 우리가 넘긴 값과 비교할 수 없다.
    const video = document.querySelector('video') as HTMLVideoElement;
    expect(video.getAttribute('src')).toBe(src);
  });

  it('★video_로드_실패시_onSrcError가_불린다', () => {
    // given
    const onSrcError = vi.fn();
    render(<VideoPlayer src="/test.mp4" onSrcError={onSrcError} />);
    const video = document.querySelector('video') as HTMLVideoElement;

    // when: 서명 만료(401) 등으로 로드가 실패한다
    fireEvent.error(video);

    // then: 상위가 재발급으로 회복할 수 있게 알린다
    expect(onSrcError).toHaveBeenCalledTimes(1);
  });

  it('★로드_실패는_회복이_아니다_error에서는_onSrcRecovered가_불리지_않는다', async () => {
    // given: 실패 축과 회복 축을 <b>동시에</b> 넘긴다. 한쪽만 넘기면 「실패가 회복으로도 세어지는」
    //        상태를 애초에 만들 수 없어, 그 변이를 잡을 수 없다.
    const onSrcError = vi.fn();
    const onSrcRecovered = vi.fn();
    render(<VideoPlayer src="/test.mp4" onSrcError={onSrcError} onSrcRecovered={onSrcRecovered} />);
    const video = document.querySelector('video') as HTMLVideoElement;

    // when: 서명 만료(401) 등으로 로드가 실패한다
    fireEvent.error(video);

    // 대기 조건은 지키려는 축과 <b>다른 축</b>(실패 통지 도달)으로 건다. 회복 축으로 기다리면
    // 변이 시 대기에서 먼저 죽어 정작 아래 단정이 실행되지 않고, 실패 메시지가 「타임아웃」이라
    // 원인을 가린다.
    await waitFor(() => expect(onSrcError).toHaveBeenCalledTimes(1));
    // 「일어나지 않는다」를 지연 없이 단정하면 늦게 오는 호출을 놓쳐 <b>항상</b> 통과한다.
    // 상태가 정착할 만큼 진행시킨 뒤 단정한다.
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 50));
    });

    // then: 실패는 회복이 아니다. 여기서 회복 신호가 함께 나가면 상위의 재시도 예산이 매 실패마다
    //       0 으로 되돌아가 상한이 무력해지고, 서명 URL 재발급 폭주가 되살아난다.
    //       (훅 층의 폭주 보호 시험은 이 축을 못 잡는다 — 훅은 신호가 왜 왔는지 모른다.)
    expect(onSrcRecovered).not.toHaveBeenCalled();
  });

  it('★재생_가능해지면_onSrcRecovered가_불린다_재시도_예산_회복_신호', () => {
    // given
    const onSrcRecovered = vi.fn();
    render(<VideoPlayer src="/test.mp4" onSrcRecovered={onSrcRecovered} />);
    const video = document.querySelector('video') as HTMLVideoElement;

    // when: 새 소스가 실제로 재생 가능해졌다
    fireEvent.canPlay(video);

    // then: 상위가 연속 실패 예산을 되돌린다. 신호를 canplay 로 고른 이유는 컴포넌트 주석 참조
    //       (loadedmetadata 는 재생 가능을 뜻하지 않고, playing 은 사용자가 다시 눌러야 온다).
    expect(onSrcRecovered).toHaveBeenCalledTimes(1);
  });

  it('회복_신호는_loadedmetadata만으로는_오지_않는다_길이만_읽힌_상태는_회복이_아니다', () => {
    // given
    const onSrcRecovered = vi.fn();
    render(<VideoPlayer src="/test.mp4" onSrcRecovered={onSrcRecovered} />);
    const video = document.querySelector('video') as HTMLVideoElement;
    Object.defineProperty(video, 'duration', { configurable: true, value: 12 });

    // when
    fireEvent.loadedMetadata(video);

    // then: 아직 재생 가능하다고 볼 수 없다 — 여기서 예산을 되돌리면 폭주 보호가 약해진다
    expect(onSrcRecovered).not.toHaveBeenCalled();
  });
});
