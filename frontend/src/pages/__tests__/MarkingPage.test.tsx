import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { apiClient } from '@/lib/api/client';
import { MarkingPage } from '@/pages/MarkingPage';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';
import { useUiStore } from '@/stores/useUiStore';
import { useMarkingStore } from '@/features/marking/store';

const navigateMock = vi.fn();

vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual<typeof import('react-router-dom')>(
    'react-router-dom',
  );
  return {
    ...actual,
    useNavigate: () => navigateMock,
    useParams: () => ({ rawSn: '42' }),
  };
});

// VideoPlayer 는 jsdom 에서 <video> 미지원 — stub 처리.
// src prop 을 data-src 로 노출해 서명 URL 주입을 단언한다.
vi.mock('@/features/marking/components/VideoPlayer', () => ({
  VideoPlayer: vi.fn(({ src }: { src: string }) => (
    <div data-testid="video-player-stub" data-src={src} />
  )),
}));

function setRole(role: 'REVIEWER' | 'WORKER', sub = 'u-7') {
  useAuthStore.setState({
    token: 'tok',
    claims: { sub, role, channel: 'INTERNAL', exp: 9999999999 },
  });
}

describe('MarkingPage', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    navigateMock.mockReset();
    useMarkingStore.getState().reset();
    // 기존 토스트 비우기
    useUiStore.setState({ toasts: [] });
    // <video> 서명 URL 발급 mock — 모든 테스트 공통.
    mock.onGet(/\/videos\/42\/stream-url/).reply(200, {
      url: '/api/v1/videos/42/stream?exp=9999999999&sig=deadbeef',
      expiresAt: 9999999999,
      ttlSeconds: 60,
    });
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
  });

  it('마킹_제출_성공시_토스트_표시_및_task_목록으로_이동', async () => {
    // given: WORKER 로그인 + 마킹 저장 API mock
    setRole('WORKER');
    mock.onGet(/\/videos\/42\/markings/).reply(200, []);
    let postBody: Record<string, unknown> = {};
    mock.onPost(/\/videos\/42\/markings/).reply((config) => {
      postBody = JSON.parse(config.data ?? '{}');
      return [
        200,
        {
          markingSn: 1,
          rawSn: 42,
          eventName: '화재',
          markingMode: 'AUTO',
          intervalFrames: 5,
          videoPath: '/path/to/video',
          marks: [],
          status: 'PENDING',
          createdAt: '2026-05-27T10:00:00Z',
        },
      ];
    });

    renderWithProviders(<MarkingPage />, {
      initialEntries: ['/marking/42'],
    });

    await waitFor(() => {
      expect(screen.getByText(/마킹 — 영상 #42/)).toBeInTheDocument();
    });

    // when: 이벤트명 입력 후 "자동" 모드에서 제출
    const user = userEvent.setup();
    const eventInput = screen.getByPlaceholderText('이벤트명');
    await user.click(screen.getByText('자동'));
    await user.clear(eventInput);
    await user.type(eventInput, '화재');

    // "마킹 완료" 버튼 클릭
    const submitBtn = screen.getByRole('button', { name: /마킹 완료/ });
    await user.click(submitBtn);

    // then: 토스트 메시지가 UI store 에 추가되어야 한다
    await waitFor(() => {
      const toasts = useUiStore.getState().toasts;
      expect(toasts.some((t) => t.variant === 'success' && t.message.includes('마킹'))).toBe(true);
    });

    // then: /task 로 navigate 되어야 한다
    await waitFor(() => {
      expect(navigateMock).toHaveBeenCalledWith('/task');
    });

    // then(I2): 요청 페이로드는 BE 계약(MarkingRequest.mode)에 맞춰 `mode` 필드여야 한다.
    // 과거엔 `markingMode` 를 보내 BE @NotBlank mode 검증에 걸려 항상 400 이 발생했다.
    expect(postBody.mode).toBe('AUTO');
    expect(postBody).not.toHaveProperty('markingMode');
  });

  it('I2_수동모드_제출시_요청_페이로드_mode_MANUAL_marks_직렬화', async () => {
    // given: WORKER 로그인 + 마킹 저장 API mock
    setRole('WORKER');
    mock.onGet(/\/videos\/42\/markings/).reply(200, []);
    let postBody: Record<string, unknown> = {};
    mock.onPost(/\/videos\/42\/markings/).reply((config) => {
      postBody = JSON.parse(config.data ?? '{}');
      return [
        200,
        {
          markingSn: 2,
          rawSn: 42,
          eventName: '침입',
          markingMode: 'MANUAL',
          intervalFrames: null,
          videoPath: '/path/to/video',
          marks: [{ frameIndex: 0, timestamp: '00:00' }],
          status: 'PENDING',
          createdAt: '2026-05-27T10:00:00Z',
        },
      ];
    });

    renderWithProviders(<MarkingPage />, { initialEntries: ['/marking/42'] });

    await waitFor(() => {
      expect(screen.getByText(/마킹 — 영상 #42/)).toBeInTheDocument();
    });

    // when: 수동 모드 + 이벤트명 입력 + 마킹 1건 추가 후 제출
    const user = userEvent.setup();
    await user.click(screen.getByText('수동'));
    const eventInput = screen.getByPlaceholderText('이벤트명');
    await user.clear(eventInput);
    await user.type(eventInput, '침입');
    // 수동 마킹 1건 추가 (스토어 직접 주입 — VideoPlayer stub 으로 키 이벤트 경로 우회)
    useMarkingStore.getState().addMark({ frameIndex: 0, timestamp: '00:00' });

    const submitBtn = screen.getByRole('button', { name: /마킹 완료/ });
    await user.click(submitBtn);

    // then(I2): 요청 페이로드 mode='MANUAL', marks 직렬화, markingMode 부재
    await waitFor(() => {
      expect(postBody.mode).toBe('MANUAL');
    });
    expect(postBody).not.toHaveProperty('markingMode');
    expect(postBody.marks).toEqual([{ frameIndex: 0, timestamp: '00:00' }]);
  });

  it('I3_재생전_서명URL_발급후_video_src에_서명URL_설정', async () => {
    // given: WORKER 로그인 + stream-url 발급 mock (beforeEach)
    setRole('WORKER');
    mock.onGet(/\/videos\/42\/markings/).reply(200, []);

    // when: 마킹 화면 진입
    renderWithProviders(<MarkingPage />, { initialEntries: ['/marking/42'] });

    // then: VideoPlayer 의 src 에 서명 URL(exp/sig 쿼리 포함)이 주입되어야 한다.
    // 과거엔 src=`/api/v1/videos/42/stream` (서명 없음) → 401 검정화면이었다.
    await waitFor(() => {
      const stub = screen.getByTestId('video-player-stub');
      expect(stub.getAttribute('data-src')).toBe(
        '/api/v1/videos/42/stream?exp=9999999999&sig=deadbeef',
      );
    });
  });
});
