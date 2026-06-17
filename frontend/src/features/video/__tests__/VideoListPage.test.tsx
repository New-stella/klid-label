import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { apiClient } from '@/lib/api/client';
import { VideoListPage } from '@/pages/VideoListPage';
import { useAuthStore } from '@/stores/useAuthStore';
import { renderWithProviders } from '@/test/renderWithProviders';

function mockVideosOnce(mock: MockAdapter, opts: { content?: unknown[]; total?: number } = {}) {
  mock.onGet('/videos').reply(200, {
    success: true,
    data: {
      content: opts.content ?? [
        {
          id: 1,
          cctvName: '강남대로 CCTV',
          vmsClipId: 'VMS-1',
          eventName: '쓰러짐',
          eventTypeCd: 'FALL',
          localGov: '강남구',
          frameCount: 900,
          status: 'COMPLETED',
          capturedAt: '2026-05-01T12:00:00Z',
        },
      ],
      totalElements: opts.total ?? 1,
      totalPages: 1,
      number: 0,
      size: 20,
    },
    message: null,
    errorCode: null,
  });
}

function setRole(role: 'REVIEWER' | 'WORKER') {
  useAuthStore.setState({
    token: 'fake',
    claims: {
      sub: 'u-1',
      role,
      channel: 'INTERNAL',
      exp: Math.floor(Date.now() / 1000) + 3600,
    },
  });
}

describe('VideoListPage', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
  });

  it('영상_목록_렌더_및_상세_버튼_노출', async () => {
    // mock 정합 — 영상 목록에는 행 별 액션으로 '상세' 버튼이 항상 존재한다.
    setRole('REVIEWER');
    mockVideosOnce(mock);

    renderWithProviders(<VideoListPage />, { initialEntries: ['/video/completed'] });

    await waitFor(() => {
      expect(screen.getByText('강남대로 CCTV')).toBeInTheDocument();
    });
    expect(screen.getByRole('button', { name: /상세/ })).toBeInTheDocument();
  });

  it('REVIEWER로_접속하면_각_영상_행에_배정_버튼이_노출된다', async () => {
    setRole('REVIEWER');
    mockVideosOnce(mock);

    renderWithProviders(<VideoListPage />, { initialEntries: ['/video/completed'] });

    await waitFor(() => {
      expect(screen.getByText('강남대로 CCTV')).toBeInTheDocument();
    });
    expect(
      screen.getByRole('button', { name: '강남대로 CCTV 작업자 배정' }),
    ).toBeInTheDocument();
  });

  it('REVIEWER가_배정_버튼을_누르면_해당_영상이_선택된_채_AssignModal이_열린다', async () => {
    setRole('REVIEWER');
    mockVideosOnce(mock);
    // AssignModal 이 REVIEWER 일 때 호출하는 작업자/검수자 API 빈 응답 stub
    mock.onGet('/users/workers').reply(200, { success: true, data: [], message: null, errorCode: null });
    mock.onGet('/users').reply(200, {
      success: true,
      data: { content: [], totalElements: 0, totalPages: 1, number: 0, size: 50 },
      message: null,
      errorCode: null,
    });

    const user = userEvent.setup();
    renderWithProviders(<VideoListPage />, { initialEntries: ['/video/completed'] });

    await waitFor(() => {
      expect(screen.getByText('강남대로 CCTV')).toBeInTheDocument();
    });
    await user.click(
      screen.getByRole('button', { name: '강남대로 CCTV 작업자 배정' }),
    );

    // AssignModal(단건 신규 배정) 오픈 — 영상명이 모달 내부에 선택된 채 표시된다.
    await waitFor(() => {
      expect(screen.getByRole('dialog')).toBeInTheDocument();
    });
    expect(screen.getByText('작업 배정')).toBeInTheDocument();
    // 모달 안에 대상 영상명(미리보기)이 표시
    const dialog = screen.getByRole('dialog');
    expect(within(dialog).getByText('강남대로 CCTV')).toBeInTheDocument();
  });

  it('WORKER로_접속하면_배정_버튼이_노출되지_않는다', async () => {
    setRole('WORKER');
    mockVideosOnce(mock);

    renderWithProviders(<VideoListPage />, { initialEntries: ['/video/completed'] });

    await waitFor(() => {
      expect(screen.getByText('강남대로 CCTV')).toBeInTheDocument();
    });
    // WORKER 에게는 행/일괄 배정 액션이 전혀 노출되지 않는다 (배정은 REVIEWER 전용).
    expect(
      screen.queryByRole('button', { name: /작업자 배정/ }),
    ).not.toBeInTheDocument();
    // 상세 버튼은 역할 무관 항상 노출
    expect(screen.getByRole('button', { name: /상세/ })).toBeInTheDocument();
  });

  it('REVIEWER가_영상을_선택하면_일괄_배정_버튼이_노출되고_누르면_일괄_AssignModal이_열린다', async () => {
    setRole('REVIEWER');
    mockVideosOnce(mock);
    mock.onGet('/users/workers').reply(200, { success: true, data: [], message: null, errorCode: null });
    mock.onGet('/users').reply(200, {
      success: true,
      data: { content: [], totalElements: 0, totalPages: 1, number: 0, size: 50 },
      message: null,
      errorCode: null,
    });

    const user = userEvent.setup();
    renderWithProviders(<VideoListPage />, { initialEntries: ['/video/completed'] });

    await waitFor(() => {
      expect(screen.getByText('강남대로 CCTV')).toBeInTheDocument();
    });

    // 행 체크박스 선택 → 일괄 액션 바에 배정 버튼 노출
    await user.click(screen.getByRole('checkbox', { name: '강남대로 CCTV 선택' }));
    const bulkBtn = await screen.findByRole('button', { name: /일괄 배정/ });
    expect(bulkBtn).toBeInTheDocument();

    await user.click(bulkBtn);
    await waitFor(() => {
      expect(screen.getByRole('dialog')).toBeInTheDocument();
    });
    // 모달 제목이 일괄 배정 모드(N개 영상 일괄 배정)임을 확인 (heading 로 한정)
    expect(
      screen.getByRole('heading', { name: /개 영상 일괄 배정/ }),
    ).toBeInTheDocument();
  });

  it('WORKER가_영상을_선택해도_일괄_배정_액션바가_노출되지_않는다', async () => {
    // CWE-285 심층 방어 — 배정은 REVIEWER 전용이므로 WORKER 에겐
    // 일괄 액션 바(선택 N건 + 일괄 배정 버튼) 자체를 노출하지 않는다.
    setRole('WORKER');
    mockVideosOnce(mock);

    const user = userEvent.setup();
    renderWithProviders(<VideoListPage />, { initialEntries: ['/video/completed'] });

    await waitFor(() => {
      expect(screen.getByText('강남대로 CCTV')).toBeInTheDocument();
    });
    await user.click(screen.getByRole('checkbox', { name: '강남대로 CCTV 선택' }));

    // 일괄 배정 버튼 미노출
    expect(
      screen.queryByRole('button', { name: /일괄 배정/ }),
    ).not.toBeInTheDocument();
    // 액션 바 컨테이너(선택 N건 텍스트)도 미노출 — 액션 바 자체가 렌더되지 않음
    expect(screen.queryByText(/선택 \d+건/)).not.toBeInTheDocument();
  });

  it('영상_없을_때_EmptyState_노출', async () => {
    setRole('WORKER');
    mockVideosOnce(mock, { content: [], total: 0 });

    renderWithProviders(<VideoListPage />, { initialEntries: ['/video/completed'] });

    await waitFor(() => {
      expect(screen.getByText('해당하는 영상이 없습니다.')).toBeInTheDocument();
    });
  });

  it('loading_상태에서_skeleton_렌더', () => {
    setRole('WORKER');
    mock.onGet('/videos').reply(() => new Promise(() => {}));

    renderWithProviders(<VideoListPage />, { initialEntries: ['/video/completed'] });

    expect(screen.queryByText('해당하는 영상이 없습니다.')).not.toBeInTheDocument();
  });

  it('영상_목록_검색_필터_URL_파라미터_동기화', async () => {
    setRole('WORKER');
    mockVideosOnce(mock, { content: [], total: 0 });

    const user = userEvent.setup();
    renderWithProviders(<VideoListPage />, { initialEntries: ['/video/completed'] });

    // mock 정합 — 라벨이 'CCTV명 / 영상ID'로 변경됨
    await waitFor(() => {
      expect(screen.getByLabelText('CCTV명 / 영상ID')).toBeInTheDocument();
    });

    const input = screen.getByLabelText('CCTV명 / 영상ID');
    await user.type(input, '강남');
    await user.click(screen.getByRole('button', { name: /조회/ }));

    await waitFor(() => {
      const last = mock.history.get[mock.history.get.length - 1];
      expect(last?.params).toMatchObject({ cctvNameKeyword: '강남' });
    });
  });
});
