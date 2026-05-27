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

// VideoPlayer 는 jsdom 에서 <video> 미지원 — stub 처리
vi.mock('@/features/marking/components/VideoPlayer', () => ({
  VideoPlayer: vi.fn().mockReturnValue(<div data-testid="video-player-stub" />),
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
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
  });

  it('마킹_제출_성공시_토스트_표시_및_task_목록으로_이동', async () => {
    // given: WORKER 로그인 + 마킹 저장 API mock
    setRole('WORKER');
    mock.onGet(/\/videos\/42\/markings/).reply(200, []);
    mock.onPost(/\/videos\/42\/markings/).reply(200, {
      markingSn: 1,
      rawSn: 42,
      eventName: '화재',
      markingMode: 'AUTO',
      intervalFrames: 5,
      videoPath: '/path/to/video',
      marks: [],
      status: 'PENDING',
      createdAt: '2026-05-27T10:00:00Z',
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
  });
});
