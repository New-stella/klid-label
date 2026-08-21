// 영상 목록 — 「시계열 위탁 전체 건너뛰기」 상시 배너. [@design SCREEN-008] [@design ADR-050]
//
// ★ 왜 배너인가 — 스위치가 켜져 있는 동안 들어오는 영상은 **전건이 시계열 없이 확정**된다.
//   그 사실이 어디에도 드러나지 않으면 아무도 모르는 사이에 학습데이터가 시계열 없이 쌓이고,
//   끄는 것을 잊으면 벤더 연동이 끝난 뒤에도 계속 건너뛴다.
//
// ★ 색상 단독으로 의미를 전달하지 않는다 — 문구가 단독으로 상태를 말하고 아이콘이 보조한다.

import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { apiClient } from '@/lib/api/client';
import { VideoListPage } from '@/pages/VideoListPage';
import { useAuthStore } from '@/stores/useAuthStore';
import { renderWithProviders } from '@/test/renderWithProviders';

const SKIP_KEY = 'batch.vlm.skip-by-default';
const REASON_KEY = 'batch.vlm.skip-by-default-reason';

function mockVideos(mock: MockAdapter) {
  mock.onGet('/videos').reply(200, {
    success: true,
    data: {
      content: [
        {
          id: 1,
          cctvName: 'CCTV-1',
          vmsClipId: 'VMS-1',
          frameCount: 900,
          status: 'COMPLETED',
          capturedAt: '2026-05-01T12:00:00Z',
        },
      ],
      totalElements: 1,
      totalPages: 1,
      number: 0,
      size: 20,
    },
    message: null,
    errorCode: null,
  });
}

function mockConfigs(mock: MockAdapter, entries: Record<string, string>) {
  mock.onGet('/manage/configs').reply(200, {
    success: true,
    data: Object.entries(entries).map(([configKey, configVl]) => ({ configKey, configVl })),
    message: null,
    errorCode: null,
  });
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

describe('영상 목록 전체 건너뛰기 배너', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    mock.onGet('/event-types').reply(200, { success: true, data: [], message: null, errorCode: null });
    setRole('REVIEWER');
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
  });

  it('스위치가_켜져_있으면_사유와_함께_배너를_보여준다', async () => {
    mockVideos(mock);
    mockConfigs(mock, {
      [SKIP_KEY]: 'true',
      [REASON_KEY]: '외부 시계열 분석 벤더 연동 전',
    });

    renderWithProviders(<VideoListPage />, { initialEntries: ['/video/status'] });

    const banner = await screen.findByTestId('vlm-skip-default-banner');
    expect(banner).toHaveTextContent('외부 시계열 분석 벤더 연동 전');
  });

  it('스위치가_꺼져_있으면_배너를_두지_않는다', async () => {
    mockVideos(mock);
    mockConfigs(mock, { [SKIP_KEY]: 'false', [REASON_KEY]: '외부 벤더 연동 전' });

    renderWithProviders(<VideoListPage />, { initialEntries: ['/video/status'] });

    await waitFor(() => expect(screen.getByText('CCTV-1')).toBeInTheDocument());
    expect(screen.queryByTestId('vlm-skip-default-banner')).not.toBeInTheDocument();
  });

  it('설정_행이_아예_없으면_배너를_두지_않는다', async () => {
    // 한 번도 저장한 적 없는 키는 응답에 담기지 않는다(그 상태가 곧 꺼짐이다).
    mockVideos(mock);
    mockConfigs(mock, {});

    renderWithProviders(<VideoListPage />, { initialEntries: ['/video/status'] });

    await waitFor(() => expect(screen.getByText('CCTV-1')).toBeInTheDocument());
    expect(screen.queryByTestId('vlm-skip-default-banner')).not.toBeInTheDocument();
  });

  it('배너는_시스템_설정으로_가는_길을_둔다', async () => {
    mockVideos(mock);
    mockConfigs(mock, { [SKIP_KEY]: 'true', [REASON_KEY]: '벤더 연동 전' });

    renderWithProviders(<VideoListPage />, { initialEntries: ['/video/status'] });

    const banner = await screen.findByTestId('vlm-skip-default-banner');
    const link = screen.getByRole('link', { name: /시스템 설정/ });
    expect(banner).toContainElement(link);
    expect(link).toHaveAttribute('href', '/manage/settings');
  });

  it('색상_단독으로_의미를_전달하지_않는다_문구가_상태를_말한다', async () => {
    mockVideos(mock);
    mockConfigs(mock, { [SKIP_KEY]: 'true', [REASON_KEY]: '벤더 연동 전' });

    renderWithProviders(<VideoListPage />, { initialEntries: ['/video/status'] });

    const banner = await screen.findByTestId('vlm-skip-default-banner');
    // 문구가 단독으로 상태를 말한다.
    expect(banner).toHaveTextContent(/시계열/);
    expect(banner).toHaveTextContent(/건너뛰/);
    // 아이콘은 장식이라 보조기술에 이름을 주지 않는다(정보는 문구가 나른다).
    const icon = banner.querySelector('svg');
    expect(icon).not.toBeNull();
    expect(icon).toHaveAttribute('aria-hidden', 'true');
  });

  // ★ 스위치가 켜져 있는 동안 자동으로 건너뜀 표식이 선 영상을 **일괄 재수행**하면, 그 영상에는
  //   자동 해제 표식이 남아 이후 스위치가 켜져 있어도 계속 위탁 대상이 된다(「자동 표식은 사람의
  //   결정을 덮지 않는다」의 귀결이며 설계상 의도다). 즉 한 번의 클릭이 그 구간의 운영 결정을 선택
  //   건수만큼 뒤집는데, 그 사실이 조작 지점(버튼 옆)에 없었다.
  describe('일괄 재수행이 스위치 결정을 뒤집는다는 고지', () => {
    async function selectFirstRow() {
      const user = userEvent.setup();
      await waitFor(() => expect(screen.getByText('CCTV-1')).toBeInTheDocument());
      await user.click(screen.getByRole('checkbox', { name: 'CCTV-1 선택' }));
      return user;
    }

    it('★스위치가_켜져_있으면_재수행_이후에도_위탁_대상이_됨을_알린다', async () => {
      mockVideos(mock);
      mockConfigs(mock, { [SKIP_KEY]: 'true', [REASON_KEY]: '벤더 연동 전' });

      renderWithProviders(<VideoListPage />, { initialEntries: ['/video/status'] });
      await selectFirstRow();

      const note = await screen.findByTestId('bulk-rerun-skip-default-note');
      // 어간(`건너뛰`)으로 부분일치를 노리면 활용형을 놓치므로 두 사실을 각각 확인한다.
      expect(note).toHaveTextContent(/켜져 있습니다/);
      expect(note).toHaveTextContent(/위탁 대상이 됩니다/);
    });

    it('★스위치가_꺼져_있으면_그_고지를_두지_않는다', async () => {
      mockVideos(mock);
      mockConfigs(mock, { [SKIP_KEY]: 'false', [REASON_KEY]: '벤더 연동 전' });

      renderWithProviders(<VideoListPage />, { initialEntries: ['/video/status'] });
      await selectFirstRow();

      // 액션 바 자체는 떠 있어야 한다(고지만 없는 것과 바가 없는 것은 다른 축이다).
      expect(screen.getByTestId('bulk-scope-hint')).toBeInTheDocument();
      expect(screen.queryByTestId('bulk-rerun-skip-default-note')).not.toBeInTheDocument();
    });

    it('선택이_없으면_액션_바가_없으므로_고지도_없다', async () => {
      mockVideos(mock);
      mockConfigs(mock, { [SKIP_KEY]: 'true', [REASON_KEY]: '벤더 연동 전' });

      renderWithProviders(<VideoListPage />, { initialEntries: ['/video/status'] });

      await waitFor(() => expect(screen.getByText('CCTV-1')).toBeInTheDocument());
      expect(screen.queryByTestId('bulk-rerun-skip-default-note')).not.toBeInTheDocument();
    });

    // ★ 이 고지를 위해 조회를 새로 붙이지 않는다 — 배너가 이미 읽은 값을 그대로 쓴다.
    it('★고지를_위해_설정_조회를_새로_붙이지_않는다', async () => {
      mockVideos(mock);
      mockConfigs(mock, { [SKIP_KEY]: 'true', [REASON_KEY]: '벤더 연동 전' });

      renderWithProviders(<VideoListPage />, { initialEntries: ['/video/status'] });
      await selectFirstRow();
      await screen.findByTestId('bulk-rerun-skip-default-note');

      expect(mock.history.get.filter((h) => h.url === '/manage/configs')).toHaveLength(1);
    });
  });

  it('WORKER_에게는_설정을_조회하지_않는다_배너도_없다', async () => {
    // GET /v1/manage/configs 는 REVIEWER 전용이라 WORKER 가 부르면 403 만 쌓인다.
    setRole('WORKER');
    mockVideos(mock);
    mockConfigs(mock, { [SKIP_KEY]: 'true', [REASON_KEY]: '벤더 연동 전' });

    renderWithProviders(<VideoListPage />, { initialEntries: ['/video/status'] });

    await waitFor(() => expect(screen.getByText('CCTV-1')).toBeInTheDocument());
    expect(screen.queryByTestId('vlm-skip-default-banner')).not.toBeInTheDocument();
    expect(mock.history.get.filter((h) => h.url === '/manage/configs')).toHaveLength(0);
  });
});
