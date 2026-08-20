// 영상 목록 — 시계열 묶음 **일괄** 건너뛰기 / 건너뛰기 해제 / 재수행.
// [@design SCREEN-008] [@design API-212] [@design API-213] [@design API-214]
//
// ★ 이 세 조작의 대상 범위는 일괄 재시작과 다르다 — 재시작은 선택분 중 실패 건만 접수되지만
//   시계열 일괄 조작은 선택 전건에 적용되고 거부는 건별 사유로 돌아온다. 화면이 그 차이를
//   미리 알리는지까지 검증한다(보내고 결과를 받은 뒤에야 알게 되는 동선 방지).

import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { apiClient } from '@/lib/api/client';
import { VideoListPage } from '@/pages/VideoListPage';
import { useAuthStore } from '@/stores/useAuthStore';
import { renderWithProviders } from '@/test/renderWithProviders';

const SKIP_PATH = '/videos/batch/stages/VLM/skip';
const RERUN_PATH = '/videos/batch/stages/VLM/rerun';

function videoRow(id: number, status: string) {
  return {
    id,
    cctvName: `CCTV-${id}`,
    vmsClipId: `VMS-${id}`,
    eventName: '쓰러짐',
    eventTypeCd: 'FALL',
    frameCount: 900,
    status,
    capturedAt: '2026-05-01T12:00:00Z',
  };
}

/** 1번만 실패(FAILED), 나머지는 완료 — 재시작 대상과 시계열 대상의 범위 차이를 만든다. */
function mockVideos(mock: MockAdapter, count: number, failedIds: number[] = [1]) {
  const content = Array.from({ length: count }, (_, i) =>
    videoRow(i + 1, failedIds.includes(i + 1) ? 'FAILED' : 'COMPLETED'),
  );
  mock.onGet('/videos').reply(200, {
    success: true,
    data: {
      content,
      totalElements: count,
      totalPages: 1,
      number: 0,
      size: Math.max(count, 20),
    },
    message: null,
    errorCode: null,
  });
}

function okResult(data: unknown) {
  return { success: true, data, message: null, errorCode: null };
}

function setRole(role: 'REVIEWER' | 'WORKER') {
  useAuthStore.setState({
    token: 'dummy-test-token',
    claims: {
      sub: 'u-1',
      role,
      channel: 'INTERNAL',
      exp: Math.floor(Date.now() / 1000) + 3600,
    },
  });
}

describe('VideoListPage 시계열 일괄 조작', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    setRole('REVIEWER');
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
  });

  async function selectFirstTwo() {
    const user = userEvent.setup();
    await waitFor(() => expect(screen.getByText('CCTV-1')).toBeInTheDocument());
    await user.click(screen.getByRole('checkbox', { name: 'CCTV-1 선택' }));
    await user.click(screen.getByRole('checkbox', { name: 'CCTV-2 선택' }));
    return user;
  }

  it('REVIEWER_에게_시계열_일괄_3버튼이_노출된다', async () => {
    mockVideos(mock, 2);
    renderWithProviders(<VideoListPage />, { initialEntries: ['/video/status'] });
    await selectFirstTwo();

    expect(
      screen.getByRole('button', { name: '2개 영상 시계열 일괄 건너뛰기' }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole('button', { name: '2개 영상 시계열 일괄 건너뛰기 해제' }),
    ).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '2개 영상 시계열 일괄 재수행' })).toBeInTheDocument();
    // 오토라벨 묶음은 이 바에 두지 않는다(산출물이 라벨이라 대량 건너뛰기를 열지 않았다).
    expect(screen.queryByRole('button', { name: /오토라벨/ })).not.toBeInTheDocument();
  });

  it('WORKER_에게는_시계열_일괄_버튼이_노출되지_않는다', async () => {
    setRole('WORKER');
    mockVideos(mock, 2);
    renderWithProviders(<VideoListPage />, { initialEntries: ['/video/status'] });
    await waitFor(() => expect(screen.getByText('CCTV-1')).toBeInTheDocument());

    expect(screen.queryByRole('button', { name: /시계열 일괄/ })).not.toBeInTheDocument();
  });

  it('안내는_재시작_대상과_시계열_대상_범위를_구분해_알린다', async () => {
    // 2건 선택 중 실패는 1건 — 두 조작의 대상 수가 다르다는 사실을 문구가 직접 말해야 한다.
    mockVideos(mock, 2, [1]);
    renderWithProviders(<VideoListPage />, { initialEntries: ['/video/status'] });
    await selectFirstTwo();

    const hint = await screen.findByTestId('bulk-scope-hint');
    expect(hint).toHaveTextContent('실패 1건');
    expect(hint).toHaveTextContent('선택한 2건 전부');
  });

  it('상한을_넘게_고르면_시계열_3버튼도_막고_보내기_전에_알린다', async () => {
    mockVideos(mock, 101, [1]);
    renderWithProviders(<VideoListPage />, { initialEntries: ['/video/status'] });
    const user = userEvent.setup();
    await waitFor(() => expect(screen.getByText('CCTV-1')).toBeInTheDocument());
    await user.click(screen.getByRole('checkbox', { name: '전체 선택' }));

    expect(await screen.findByTestId('bulk-retry-limit-notice')).toHaveTextContent('최대 100건');
    expect(screen.getByRole('button', { name: '101개 영상 시계열 일괄 건너뛰기' })).toBeDisabled();
    expect(
      screen.getByRole('button', { name: '101개 영상 시계열 일괄 건너뛰기 해제' }),
    ).toBeDisabled();
    expect(screen.getByRole('button', { name: '101개 영상 시계열 일괄 재수행' })).toBeDisabled();
    expect(mock.history.post.filter((h) => h.url === SKIP_PATH)).toHaveLength(0);
  });

  it('사유가_공백만이면_제출_버튼이_막힌다', async () => {
    mockVideos(mock, 2);
    renderWithProviders(<VideoListPage />, { initialEntries: ['/video/status'] });
    const user = await selectFirstTwo();
    await user.click(screen.getByRole('button', { name: '2개 영상 시계열 일괄 건너뛰기' }));

    const submit = await screen.findByRole('button', { name: '2건 건너뛰기' });
    expect(submit).toBeDisabled();

    await user.type(screen.getByRole('textbox', { name: /건너뛰는 사유/ }), '   ');
    expect(screen.getByRole('button', { name: '2건 건너뛰기' })).toBeDisabled();
    expect(mock.history.post.filter((h) => h.url === SKIP_PATH)).toHaveLength(0);
  });

  it('사유_글자수는_라벨의_접근성_이름을_바꾸지_않는다', async () => {
    // 라벨 안에 글자수를 넣으면 스크린리더가 필드 이름을 타이핑할 때마다 다르게 읽는다.
    mockVideos(mock, 2);
    renderWithProviders(<VideoListPage />, { initialEntries: ['/video/status'] });
    const user = await selectFirstTwo();
    await user.click(screen.getByRole('button', { name: '2개 영상 시계열 일괄 건너뛰기' }));

    const textarea = await screen.findByRole('textbox', { name: /건너뛰는 사유/ });
    const before = textarea.getAttribute('aria-labelledby');
    const nameBefore = screen.getByRole('textbox', { name: /건너뛰는 사유/ });
    expect(nameBefore).toBe(textarea);

    await user.type(textarea, '벤더 연동 전');
    // 글자수 표시는 갱신되지만 필드 이름은 그대로여야 한다.
    expect(screen.getByTestId('bulk-skip-reason-count')).toHaveTextContent('7 / 500');
    expect(screen.getByRole('textbox', { name: /건너뛰는 사유/ })).toBe(textarea);
    expect(textarea.getAttribute('aria-labelledby')).toBe(before);
  });

  it('사유를_적어_제출하면_묶음_경로로_전건과_같은_사유를_보낸다', async () => {
    mockVideos(mock, 2);
    mock.onPost(SKIP_PATH).reply(
      200,
      okResult({
        successCount: 1,
        failureCount: 1,
        results: [
          { rawSn: 1, success: true, reason: null },
          { rawSn: 2, success: false, reason: '이미 건너뛴 작업입니다.' },
        ],
      }),
    );

    renderWithProviders(<VideoListPage />, { initialEntries: ['/video/status'] });
    const user = await selectFirstTwo();
    await user.click(screen.getByRole('button', { name: '2개 영상 시계열 일괄 건너뛰기' }));
    await user.type(
      await screen.findByRole('textbox', { name: /건너뛰는 사유/ }),
      '외부 시계열 분석 벤더 연동 전',
    );
    await user.click(screen.getByRole('button', { name: '2건 건너뛰기' }));

    await waitFor(() => {
      const call = mock.history.post.find((h) => h.url === SKIP_PATH);
      expect(call).toBeDefined();
      expect(JSON.parse(call!.data)).toEqual({
        rawSns: [1, 2],
        reason: '외부 시계열 분석 벤더 연동 전',
      });
    });

    // 부분 성공을 기존 결과 모달로 그대로 보여준다(제목만 조작별로 다르다).
    const result = await screen.findByTestId('bulk-retry-result');
    expect(result).toHaveTextContent('접수 1건');
    expect(within(result).getByTestId('bulk-retry-failure-2')).toHaveTextContent(
      '이미 건너뛴 작업입니다.',
    );
    expect(screen.getByRole('dialog', { name: '시계열 일괄 건너뛰기 결과' })).toBeInTheDocument();
  });

  it('건너뛰기_해제는_확인창_없이_바로_요청하고_결과를_보여준다', async () => {
    mockVideos(mock, 2);
    mock.onDelete(SKIP_PATH).reply(
      200,
      okResult({
        successCount: 2,
        failureCount: 0,
        results: [
          { rawSn: 1, success: true, reason: null },
          { rawSn: 2, success: true, reason: null },
        ],
      }),
    );

    renderWithProviders(<VideoListPage />, { initialEntries: ['/video/status'] });
    const user = await selectFirstTwo();
    await user.click(screen.getByRole('button', { name: '2개 영상 시계열 일괄 건너뛰기 해제' }));

    await waitFor(() => {
      const call = mock.history.delete.find((h) => h.url === SKIP_PATH);
      expect(call).toBeDefined();
      expect(JSON.parse(call!.data)).toEqual({ rawSns: [1, 2] });
    });
    expect(
      await screen.findByRole('dialog', { name: '시계열 일괄 건너뛰기 해제 결과' }),
    ).toBeInTheDocument();
  });

  it('재수행은_확인창_없이_바로_요청하고_결과를_보여준다', async () => {
    mockVideos(mock, 2);
    mock.onPost(RERUN_PATH).reply(
      200,
      okResult({
        successCount: 1,
        failureCount: 1,
        results: [
          { rawSn: 1, success: true, reason: null },
          { rawSn: 2, success: false, reason: '건너뛰기를 해제한 작업이 아닙니다.' },
        ],
      }),
    );

    renderWithProviders(<VideoListPage />, { initialEntries: ['/video/status'] });
    const user = await selectFirstTwo();
    await user.click(screen.getByRole('button', { name: '2개 영상 시계열 일괄 재수행' }));

    await waitFor(() => {
      const call = mock.history.post.find((h) => h.url === RERUN_PATH);
      expect(call).toBeDefined();
      expect(JSON.parse(call!.data)).toEqual({ rawSns: [1, 2] });
    });
    const result = await screen.findByTestId('bulk-retry-result');
    expect(within(result).getByTestId('bulk-retry-failure-2')).toHaveTextContent(
      '건너뛰기를 해제한 작업이 아닙니다.',
    );
    expect(screen.getByRole('dialog', { name: '시계열 일괄 재수행 결과' })).toBeInTheDocument();
  });

  it('같은_영상을_여러_번_눌러도_요청에는_1건만_담긴다', async () => {
    mockVideos(mock, 2);
    mock.onPost(RERUN_PATH).reply(
      200,
      okResult({ successCount: 1, failureCount: 0, results: [{ rawSn: 1, success: true, reason: null }] }),
    );

    renderWithProviders(<VideoListPage />, { initialEntries: ['/video/status'] });
    const user = userEvent.setup();
    await waitFor(() => expect(screen.getByText('CCTV-1')).toBeInTheDocument());
    const box = screen.getByRole('checkbox', { name: 'CCTV-1 선택' });
    await user.click(box);
    await user.click(box);
    await user.click(box);

    await user.click(screen.getByRole('button', { name: '1개 영상 시계열 일괄 재수행' }));

    await waitFor(() => {
      const call = mock.history.post.find((h) => h.url === RERUN_PATH);
      expect(call).toBeDefined();
      expect(JSON.parse(call!.data)).toEqual({ rawSns: [1] });
    });
  });
});
