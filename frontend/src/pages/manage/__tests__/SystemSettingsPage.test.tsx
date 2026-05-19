import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { fireEvent, screen, waitFor } from '@testing-library/react';
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

    // mock 정합 — 카드 제목: 'FFmpeg 설정' / '배치 처리' / '외부 연동 상태' / '위험 구역'
    // 섹션 헤더는 별도로 '편집 가능 — DB 영속화' / '실시간 모니터링' / '위험 액션'
    await waitFor(() => {
      expect(screen.getByText('FFmpeg 설정')).toBeInTheDocument();
    });
    expect(screen.getByText('배치 처리')).toBeInTheDocument();
    // '실시간 모니터링' 은 섹션 헤더(h2) 와 배지 양쪽에 등장 — getAllBy 로 처리
    expect(screen.getAllByText('실시간 모니터링').length).toBeGreaterThan(0);
    expect(screen.getByText('위험 액션')).toBeInTheDocument();
  });

  it('FFMPEG_THREADS_1_16_범위_검증_zod', async () => {
    // FFmpeg 스레드는 range slider 로 입력받아 UI 상 1~16 클램프되지만,
    // zod 스키마가 실제로 1~16 을 강제하는지 직접 검증한다 (이중 방어).
    const { ffmpegConfigSchema } = await import('@/features/sysconfig/schemas');

    const result = ffmpegConfigSchema.safeParse({
      FFMPEG_THREADS: 99,
      FFMPEG_OUTPUT_FPS: 5,
    });
    expect(result.success).toBe(false);
    if (!result.success) {
      const messages = result.error.issues.map((i) => i.message).join(' ');
      expect(messages).toMatch(/1.*16.*범위/);
    }
  });

  it('FFMPEG_저장_시_invalidateQueries_+_PUT_호출', async () => {
    mockConfigs(mock);
    mockHealth(mock);

    const putBodies: Array<{ key: string; value: unknown }> = [];
    // BE 정합: PUT /manage/configs/{key} body: { value }
    // path variable 에서 key 를 추출해 기존 검증(key=FFMPEG_THREADS,value=8) 형태 유지.
    mock.onPut(/\/manage\/configs\/.+/).reply((config) => {
      const body = JSON.parse(config.data ?? '{}');
      const url = config.url ?? '';
      const key = decodeURIComponent(url.split('/').pop() ?? '');
      putBodies.push({ key, value: Number(body.value) });
      return [
        200,
        {
          success: true,
          data: { key, value: body.value },
          message: null,
          errorCode: null,
        },
      ];
    });

    const user = userEvent.setup();
    renderWithProviders(<SystemSettingsPage />, { initialEntries: ['/manage/settings'] });

    // FFmpeg 스레드 input — label '스레드 수' (range slider, id="ffmpeg-threads")
    await waitFor(() => {
      const input = screen.getByLabelText(/스레드 수/) as HTMLInputElement;
      expect(input.value).toBe('4');
    });

    const input = screen.getByLabelText(/스레드 수/) as HTMLInputElement;
    // range slider 는 user.type 으로 값을 못 바꾸므로 fireEvent.change 로 변경
    fireEvent.change(input, { target: { value: '8' } });

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
