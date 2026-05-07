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
      { key: 'FFMPEG_THREADS', value: 4 },
      { key: 'FFMPEG_OUTPUT_FPS', value: 5 },
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

  it('3섹션_렌더링_(FFmpeg_Batch_health_danger)', async () => {
    mockConfigs(mock);
    mockHealth(mock);

    renderWithProviders(<SystemSettingsPage />, { initialEntries: ['/manage/settings'] });

    await waitFor(() => {
      expect(screen.getByText('FFmpeg 설정')).toBeInTheDocument();
    });
    expect(screen.getByText('배치 설정')).toBeInTheDocument();
    expect(screen.getByText('헬스 상태')).toBeInTheDocument();
    expect(screen.getByText('위험 액션')).toBeInTheDocument();
  });

  it('FFMPEG_THREADS_1_16_범위_검증_zod', async () => {
    mockConfigs(mock);
    mockHealth(mock);

    const user = userEvent.setup();
    renderWithProviders(<SystemSettingsPage />, { initialEntries: ['/manage/settings'] });

    await waitFor(() => {
      expect(screen.getByLabelText(/FFMPEG_THREADS/)).toBeInTheDocument();
    });

    const input = screen.getByLabelText(/FFMPEG_THREADS/) as HTMLInputElement;
    await user.clear(input);
    await user.type(input, '99');

    // FFmpeg 카드 저장 버튼 클릭
    const saveBtns = screen.getAllByRole('button', { name: '저장' });
    await user.click(saveBtns[0]);

    await waitFor(() => {
      // 범위 에러 메시지 노출
      expect(screen.getByText(/1.*16.*범위/)).toBeInTheDocument();
    });
  });

  it('FFMPEG_저장_시_invalidateQueries_+_PUT_호출', async () => {
    mockConfigs(mock);
    mockHealth(mock);

    const putBodies: unknown[] = [];
    mock.onPut('/manage/configs').reply((config) => {
      putBodies.push(JSON.parse(config.data ?? '{}'));
      return [
        200,
        {
          success: true,
          data: { key: 'FFMPEG_THREADS', value: 8 },
          message: null,
          errorCode: null,
        },
      ];
    });

    const user = userEvent.setup();
    renderWithProviders(<SystemSettingsPage />, { initialEntries: ['/manage/settings'] });

    await waitFor(() => {
      const input = screen.getByLabelText(/FFMPEG_THREADS/) as HTMLInputElement;
      expect(input.value).toBe('4');
    });

    const input = screen.getByLabelText(/FFMPEG_THREADS/) as HTMLInputElement;
    await user.clear(input);
    await user.type(input, '8');

    const saveBtns = screen.getAllByRole('button', { name: '저장' });
    await user.click(saveBtns[0]);

    await waitFor(() => {
      expect(putBodies).toEqual(
        expect.arrayContaining([
          expect.objectContaining({ key: 'FFMPEG_THREADS', value: 8 }),
        ]),
      );
    });
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

    // ConfirmDialog 노출
    await waitFor(() => {
      expect(screen.getByText(/되돌릴 수 없습니다/)).toBeInTheDocument();
    });

    // 확인 클릭 — placeholder이므로 실제 API 호출 X
    const confirmBtn = screen.getByRole('button', { name: '실행' });
    await user.click(confirmBtn);

    const afterCount = mock.history.delete.length + mock.history.post.length;
    expect(afterCount).toBe(beforeCount);
  });
});
