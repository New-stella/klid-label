import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { apiClient } from '@/lib/api/client';
import { SystemSettingsPage } from '@/pages/manage/SystemSettingsPage';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';

function setReviewer() {
  useAuthStore.setState({
    token: 'tok',
    claims: { sub: 'u', role: 'REVIEWER', channel: 'INTERNAL', exp: 9999999999 },
  });
}

function mockConfigs(mock: MockAdapter) {
  mock.onGet('/manage/configs').reply(200, {
    success: true,
    data: [
      { key: 'BATCH_INTERVAL_SEC', value: 60 },
      { key: 'BATCH_CONCURRENCY', value: 1 },
    ],
    message: null,
    errorCode: null,
  });
}

function mockHealth(mock: MockAdapter) {
  mock.onGet('/manage/health').reply(200, {
    status: 'UP',
    components: {
      db: { status: 'UP' },
      controlServer: { status: 'UP' },
    },
  });
}

describe('SystemSettingsPage', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    setReviewer();
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
  });

  it('3섹션_렌더링_(Batch_health_danger)', async () => {
    mockConfigs(mock);
    mockHealth(mock);

    renderWithProviders(<SystemSettingsPage />, { initialEntries: ['/manage/settings'] });

    // mock 정합 — 카드 제목: '배치 처리' / '외부 연동 상태' / '위험 구역'
    // 섹션 헤더는 별도로 '편집 가능 — DB 영속화' / '실시간 모니터링' / '위험 액션'
    await waitFor(() => {
      expect(screen.getByText('배치 처리')).toBeInTheDocument();
    });
    // '실시간 모니터링' 은 섹션 헤더(h2) 와 배지 양쪽에 등장 — getAllBy 로 처리
    expect(screen.getAllByText('실시간 모니터링').length).toBeGreaterThan(0);
    expect(screen.getByText('위험 액션')).toBeInTheDocument();
  });

  it('위험_액션_클릭시_confirm_dialog_노출_및_API_호출_없음', async () => {
    mockConfigs(mock);
    mockHealth(mock);

    const user = userEvent.setup();
    renderWithProviders(<SystemSettingsPage />, { initialEntries: ['/manage/settings'] });

    await waitFor(() => {
      expect(screen.getByText('위험 액션')).toBeInTheDocument();
    });

    const beforeCount = mock.history.delete.length + mock.history.post.length;

    // 위험 액션 첫 버튼 클릭
    const dangerBtn = screen.getByRole('button', { name: /배치 큐 초기화/ });
    await user.click(dangerBtn);

    // ConfirmDialog 노출 — dialog 안의 description 텍스트는 '이 작업은 되돌릴 수 없습니다' 가 포함됨.
    // 페이지 상단에도 '되돌릴 수 없습니다' 가 있어 다수 매치 → getAllBy 로 확인.
    await waitFor(() => {
      expect(screen.getAllByText(/되돌릴 수 없습니다/).length).toBeGreaterThanOrEqual(2);
    });

    // 확인 클릭 — placeholder이므로 실제 API 호출 X
    const confirmBtn = screen.getByRole('button', { name: '실행' });
    await user.click(confirmBtn);

    const afterCount = mock.history.delete.length + mock.history.post.length;
    expect(afterCount).toBe(beforeCount);
  });
});
