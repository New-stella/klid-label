import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { fireEvent, screen, waitFor } from '@testing-library/react';
import MockAdapter from 'axios-mock-adapter';

import { apiClient } from '@/lib/api/client';
import { HistoryPanel } from '@/features/version/components/HistoryPanel';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';

function setRole(role: 'REVIEWER' | 'WORKER', sub = 'u-7') {
  useAuthStore.setState({
    token: 'tok',
    claims: { sub, role, channel: 'INTERNAL', exp: 9999999999 },
  });
}

const versionsPayload = {
  success: true,
  data: [
    {
      commitSha: 'aaa111aaa111aaa111aaa111aaa111aaa111aaa1',
      shortHash: 'aaa111a',
      authorName: '홍길동',
      message: '라벨 수정',
      committedAt: '2026-05-07T10:00:00Z',
      isCurrent: true,
    },
    {
      commitSha: 'bbb222bbb222bbb222bbb222bbb222bbb222bbb2',
      shortHash: 'bbb222b',
      authorName: '김검수',
      message: '초기 라벨',
      committedAt: '2026-05-06T10:00:00Z',
      isCurrent: false,
    },
  ],
  message: null,
  errorCode: null,
};

describe('HistoryPanel', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
  });

  it('REVIEWER가_버전_2건_렌더링하고_롤백_트리거가_노출됨', async () => {
    setRole('REVIEWER');
    mock.onGet('/frames/42/versions').reply(200, versionsPayload);

    renderWithProviders(<HistoryPanel srcSn={42} />);

    await waitFor(() => {
      expect(screen.getByText('aaa111a')).toBeInTheDocument();
    });
    expect(screen.getByText('bbb222b')).toBeInTheDocument();

    // 최신 아닌 커밋(bbb222b) 단일 선택 → 롤백 트리거 노출
    const bbbRow = screen.getByTestId('commit-row-bbb222b');
    const bbbButton = bbbRow.querySelector('button[type="button"]') as HTMLButtonElement;
    fireEvent.click(bbbButton);

    await waitFor(() => {
      expect(screen.getByTestId('rollback-trigger-bbb222b')).toBeInTheDocument();
    });
  });

  it('WORKER도_롤백_트리거_노출됨', async () => {
    setRole('WORKER');
    mock.onGet('/frames/42/versions').reply(200, versionsPayload);

    renderWithProviders(<HistoryPanel srcSn={42} />);

    await waitFor(() => {
      expect(screen.getByText('aaa111a')).toBeInTheDocument();
    });

    const bbbRow = screen.getByTestId('commit-row-bbb222b');
    const bbbButton = bbbRow.querySelector('button[type="button"]') as HTMLButtonElement;
    fireEvent.click(bbbButton);

    // WORKER에게도 롤백 트리거가 노출되어야 함
    await waitFor(() => {
      expect(screen.getByTestId('rollback-trigger-bbb222b')).toBeInTheDocument();
    });
  });

  it('빈_응답일_때_커밋_없음_메시지_노출_및_500_없이_렌더', async () => {
    setRole('WORKER');
    mock.onGet('/frames/99/versions').reply(200, {
      success: true,
      data: [],
      message: null,
      errorCode: null,
    });

    renderWithProviders(<HistoryPanel srcSn={99} />);

    await waitFor(() => {
      expect(screen.getByText('아직 커밋된 버전이 없습니다.')).toBeInTheDocument();
    });
    // 패널 자체는 정상 렌더 (회귀 가드)
    expect(screen.getByTestId('history-panel')).toBeInTheDocument();
  });

  it('onClose_콜백이_있으면_닫기_버튼_노출_및_호출', async () => {
    setRole('WORKER');
    mock.onGet('/frames/42/versions').reply(200, versionsPayload);

    let closed = false;
    renderWithProviders(<HistoryPanel srcSn={42} onClose={() => { closed = true; }} />);

    await waitFor(() => {
      expect(screen.getByTestId('history-panel-close')).toBeInTheDocument();
    });
    fireEvent.click(screen.getByTestId('history-panel-close'));
    expect(closed).toBe(true);
  });

  it('BE_응답이_배열이_아니어도_빈_목록으로_방어', async () => {
    setRole('WORKER');
    // 레거시 { items: [...] } 응답 — FE 는 안전하게 빈 배열로 처리
    // 2026-05-18: 경로 정합화로 /frames/{srcSn}/versions 사용 (api.ts/api.test.ts 참조)
    mock.onGet('/frames/42/versions').reply(200, {
      success: true,
      data: { items: [{ commitSha: 'x' }] },
      message: null,
      errorCode: null,
    });

    renderWithProviders(<HistoryPanel srcSn={42} />);

    await waitFor(() => {
      expect(screen.getByText('아직 커밋된 버전이 없습니다.')).toBeInTheDocument();
    });
  });
});
