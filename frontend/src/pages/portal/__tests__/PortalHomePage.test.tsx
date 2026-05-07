import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import MockAdapter from 'axios-mock-adapter';

import { apiClient } from '@/lib/api/client';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';

import { PortalHomePage } from '../PortalHomePage';

describe('PortalHomePage', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    useAuthStore.setState({
      token: 'tok',
      claims: { sub: 'u1', role: 'PORTAL_USER', channel: 'PORTAL', exp: 9999999999 },
    });
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
  });

  it('포털_홈_2단계_카드_업로드_라벨링_표시', async () => {
    mock.onGet('/portal/uploads').reply(200, {
      success: true,
      data: [],
      message: null,
      errorCode: null,
    });

    renderWithProviders(<PortalHomePage />);

    // 단계 카드 제목 — "1. 업로드", "2. 라벨링"
    expect(screen.getByRole('heading', { name: /1\. 업로드/ })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: /2\. 라벨링/ })).toBeInTheDocument();
  });

  it('내_업로드_목록_렌더', async () => {
    mock.onGet('/portal/uploads').reply(200, {
      success: true,
      data: [
        {
          srcSn: 1,
          displayName: 'sample.mp4',
          uploadedAt: '2026-05-07T10:00:00Z',
          status: 'COMPLETED',
          fileSize: 1024,
        },
      ],
      message: null,
      errorCode: null,
    });

    renderWithProviders(<PortalHomePage />);
    await waitFor(() => expect(screen.getByText('sample.mp4')).toBeInTheDocument());
  });

  it('다운로드_UI_미제공_V1_5_포털_자체_책임', async () => {
    mock.onGet('/portal/uploads').reply(200, {
      success: true,
      data: [],
      message: null,
      errorCode: null,
    });

    const { container } = renderWithProviders(<PortalHomePage />);
    await waitFor(() =>
      expect(screen.getByRole('heading', { name: /1\. 업로드/ })).toBeInTheDocument(),
    );

    // 다운로드 버튼/카드/배지 미렌더
    expect(container.querySelector('[aria-label*="다운로드"]')).toBeNull();
    expect(container.querySelector('[data-testid*="download"]')).toBeNull();
    // "다운로드는 포털 자체" 안내 문구는 허용 — 단, 다운로드 동작 UI는 없어야 함
  });

  it('KPI_2_표시_업로드수_라벨링수', async () => {
    mock.onGet('/portal/uploads').reply(200, {
      success: true,
      data: [
        {
          srcSn: 1,
          displayName: 'a.mp4',
          uploadedAt: '2026-05-07T10:00:00Z',
          status: 'COMPLETED',
          fileSize: 1024,
        },
        {
          srcSn: 2,
          displayName: 'b.mp4',
          uploadedAt: '2026-05-07T11:00:00Z',
          status: 'AUTOLABEL_DONE',
          fileSize: 2048,
        },
      ],
      message: null,
      errorCode: null,
    });

    renderWithProviders(<PortalHomePage />);
    await waitFor(() => expect(screen.getByText(/업로드 수/)).toBeInTheDocument());
    expect(screen.getByText(/라벨링 완료/)).toBeInTheDocument();
  });
});
