// 영상 목록 — 「작업 묶음 실패」 검색·필터. [@design SCREEN-008] [@design API-042] [@design ADR-050]
//
// ★ 왜 이 필터가 필요한가 — 「실패 후 판단」 입구(ADR-050)의 대상을 목록에서 모으는 유일한 수단이다.
//   시계열 위탁 실패는 파이프라인을 멈추지 않아 그 영상의 **배치 상태가 완료로 남는다** — 즉 기존
//   상태 필터(실패)로는 한 건도 잡히지 않는다. 일괄 요청이 한 번에 받는 건수에 상한이 있어, 대상을
//   골라내지 못하면 회수 동선 자체가 성립하지 않는다.
//
// ★ 「시계열 건너뜀」 필터와 **다른 축**이다(사람이 건너뛴 상태 vs 실패한 상태). 두 필터는 동시에
//   실릴 수 있고 서버는 교집합으로 해석한다.
//
// ★ 하위호환이 이 파일의 또 다른 핵심이다 — 미선택이면 파라미터를 **아예 싣지 않는다**. 빈 문자열을
//   올리면 서버가 그것을 값으로 해석할 여지가 생기고, 기존 북마크·저장된 URL 의 동작이 달라진다.

import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { apiClient } from '@/lib/api/client';
import { VideoListPage } from '@/pages/VideoListPage';
import { useAuthStore } from '@/stores/useAuthStore';
import { renderWithProviders } from '@/test/renderWithProviders';

function mockVideos(mock: MockAdapter) {
  mock.onGet('/videos').reply(200, {
    success: true,
    data: {
      content: [
        {
          id: 1,
          cctvName: 'CCTV-1',
          vmsClipId: 'VMS-1',
          eventName: '쓰러짐',
          eventTypeCd: 'FALL',
          frameCount: 900,
          // ★ 완료 상태다 — 시계열 위탁만 실패한 영상이 정확히 이 모양이라 상태 필터로는 안 잡힌다.
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

/** 마지막 목록 조회 요청의 쿼리 파라미터. */
function lastListParams(mock: MockAdapter): Record<string, unknown> {
  const calls = mock.history.get.filter((h) => h.url === '/videos');
  expect(calls.length).toBeGreaterThan(0);
  return (calls[calls.length - 1]!.params ?? {}) as Record<string, unknown>;
}

describe('영상 목록 작업 묶음 실패 필터', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    mock
      .onGet('/manage/configs')
      .reply(200, { success: true, data: [], message: null, errorCode: null });
    mock
      .onGet('/event-types')
      .reply(200, { success: true, data: [], message: null, errorCode: null });
    useAuthStore.setState({
      token: 'dummy-test-token',
      claims: {
        sub: 'u-1',
        role: 'REVIEWER',
        channel: 'INTERNAL',
        exp: Math.floor(Date.now() / 1000) + 3600,
      },
    });
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
  });

  it('미선택이면_목록_요청에_failedStage_파라미터가_실리지_않는다', async () => {
    mockVideos(mock);
    renderWithProviders(<VideoListPage />, { initialEntries: ['/video/status'] });

    await waitFor(() => expect(screen.getByText('CCTV-1')).toBeInTheDocument());
    expect(lastListParams(mock)).not.toHaveProperty('failedStage');
  });

  it('★시계열_실패를_고르고_조회하면_failedStage_VLM_이_실린다', async () => {
    mockVideos(mock);
    const user = userEvent.setup();
    renderWithProviders(<VideoListPage />, { initialEntries: ['/video/status'] });
    await waitFor(() => expect(screen.getByText('CCTV-1')).toBeInTheDocument());

    await user.click(screen.getByLabelText('작업 묶음 실패'));
    await user.click(await screen.findByRole('option', { name: '시계열 실패' }));
    await user.click(screen.getByRole('button', { name: '조회' }));

    await waitFor(() => expect(lastListParams(mock).failedStage).toBe('VLM'));
  });

  // ★ 건너뜀 필터와 달리 **두 묶음을 모두** 둔다 — 조회 축이라 오토라벨 실패를 감출 이유가 없다
  //   (감추면 그 영상이 목록에서 도달 불가능해진다).
  it('★오토라벨_실패도_고를_수_있다_건너뜀_필터와_달리_두_묶음_모두다', async () => {
    mockVideos(mock);
    const user = userEvent.setup();
    renderWithProviders(<VideoListPage />, { initialEntries: ['/video/status'] });
    await waitFor(() => expect(screen.getByText('CCTV-1')).toBeInTheDocument());

    await user.click(screen.getByLabelText('작업 묶음 실패'));
    await user.click(await screen.findByRole('option', { name: '오토라벨링 실패' }));
    await user.click(screen.getByRole('button', { name: '조회' }));

    await waitFor(() => expect(lastListParams(mock).failedStage).toBe('AUTOLABEL'));
  });

  it('URL_에_들어온_failedStage_를_그대로_조회에_싣는다', async () => {
    mockVideos(mock);
    renderWithProviders(<VideoListPage />, {
      initialEntries: ['/video/status?failedStage=AUTOLABEL'],
    });

    await waitFor(() => expect(screen.getByText('CCTV-1')).toBeInTheDocument());
    expect(lastListParams(mock).failedStage).toBe('AUTOLABEL');
  });

  it('미지의_값은_버린다_서버로_흘려보내지_않는다', async () => {
    mockVideos(mock);
    renderWithProviders(<VideoListPage />, {
      initialEntries: ['/video/status?failedStage=%27%20OR%201%3D1'],
    });

    await waitFor(() => expect(screen.getByText('CCTV-1')).toBeInTheDocument());
    expect(lastListParams(mock)).not.toHaveProperty('failedStage');
  });

  // ★ 두 필터는 축이 다르므로 서로를 지우지 않는다 — 서버는 교집합으로 해석한다.
  it('★건너뜀_필터와_동시에_실린다_서로를_지우지_않는다', async () => {
    mockVideos(mock);
    renderWithProviders(<VideoListPage />, {
      initialEntries: ['/video/status?skippedStage=VLM&failedStage=VLM'],
    });

    await waitFor(() => expect(screen.getByText('CCTV-1')).toBeInTheDocument());
    expect(lastListParams(mock).skippedStage).toBe('VLM');
    expect(lastListParams(mock).failedStage).toBe('VLM');
  });

  it('조회하면_페이지가_0_으로_돌아간다', async () => {
    mockVideos(mock);
    const user = userEvent.setup();
    renderWithProviders(<VideoListPage />, { initialEntries: ['/video/status?page=3'] });
    await waitFor(() => expect(screen.getByText('CCTV-1')).toBeInTheDocument());

    await user.click(screen.getByLabelText('작업 묶음 실패'));
    await user.click(await screen.findByRole('option', { name: '시계열 실패' }));
    await user.click(screen.getByRole('button', { name: '조회' }));

    await waitFor(() => expect(lastListParams(mock).page).toBe(0));
  });

  it('초기화하면_필터가_풀려_파라미터가_사라진다', async () => {
    mockVideos(mock);
    const user = userEvent.setup();
    renderWithProviders(<VideoListPage />, {
      initialEntries: ['/video/status?failedStage=VLM'],
    });
    await waitFor(() => expect(screen.getByText('CCTV-1')).toBeInTheDocument());

    await user.click(screen.getByRole('button', { name: '초기화' }));

    await waitFor(() => expect(lastListParams(mock)).not.toHaveProperty('failedStage'));
  });
});
