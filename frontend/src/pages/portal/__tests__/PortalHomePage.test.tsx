import { afterEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '@/test/renderWithProviders';
import type { DatamartVideo } from '@/features/portal/api';

import { PortalHomePage } from '../PortalHomePage';

// Phase B — 데이터마트 영상 목록 훅을 mock 하여 목록/빈 상태/라우팅 분기를 검증한다.
// (BE 게이트 = APPROVED + 프레임>0 은 BE 단 테스트가 보장 — FE 는 렌더/동선만 검증)
const useDatamartVideosMock = vi.fn();
vi.mock('@/features/portal/hooks/useDatamartVideos', () => ({
  useDatamartVideos: (params: { page?: number; size?: number }) => useDatamartVideosMock(params),
}));

function video(over: Partial<DatamartVideo> = {}): DatamartVideo {
  return {
    rawSn: 10,
    title: 'CLIP-10',
    eventName: 'FALL',
    frameCount: 5,
    firstSrcSn: 100,
    lastUpdatedAt: '2026-06-01T10:00:00',
    myLabelExpiresAt: '2026-06-08T10:00:00',
    ...over,
  };
}

function mockVideos(content: DatamartVideo[], isLoading = false) {
  useDatamartVideosMock.mockReturnValue({
    data: isLoading ? undefined : { content, totalElements: content.length, totalPages: 1, number: 0, size: 20 },
    isLoading,
    isError: false,
  });
}

afterEach(() => {
  useDatamartVideosMock.mockReset();
});

describe('PortalHomePage', () => {
  it('포털_홈_라벨링_카드_표시_내업로드_진입_오토라벨_미노출', () => {
    mockVideos([]);
    renderWithProviders(<PortalHomePage />);

    // 라벨링 카드 + Phase 5 '내 업로드' 진입 링크 노출. 오토라벨/드롭존은 홈에 미노출(ADR-013 정신 유지).
    expect(screen.getByRole('heading', { name: '라벨링' })).toBeInTheDocument();
    const uploadLink = screen.getByRole('link', { name: /내 업로드/ });
    expect(uploadLink).toHaveAttribute('href', expect.stringContaining('/portal/uploads'));
    expect(screen.queryByRole('button', { name: /오토라벨/ })).toBeNull();
    expect(screen.queryByTestId('upload-dropzone')).toBeNull();
  });

  it('영상_0건이면_시작하기_비활성_빈상태', () => {
    mockVideos([]);
    renderWithProviders(<PortalHomePage />);

    // 데이터마트 노출 영상이 없으므로 진입 대상이 없어 비활성.
    // WCAG 2.1.1: native disabled 대신 aria-disabled 로 포커스 순서는 유지하되 활성화는 차단한다.
    const cta = screen.getByRole('button', { name: /시작하기/ });
    expect(cta).toHaveAttribute('aria-disabled', 'true');
    expect(cta).not.toBeDisabled(); // native disabled 가 아니어야 Tab 으로 도달 가능
  });

  it('영상_목록_렌더_카드_표시', () => {
    mockVideos([video({ rawSn: 10, title: 'CLIP-10', firstSrcSn: 100 }), video({ rawSn: 20, title: 'CLIP-20', firstSrcSn: 200, eventName: 'FIRE' })]);
    renderWithProviders(<PortalHomePage />);

    // 영상 카드(링크/버튼 시맨틱)가 목록으로 렌더된다
    const items = screen.getAllByTestId('datamart-video-item');
    expect(items).toHaveLength(2);
    expect(screen.getByText('CLIP-10')).toBeInTheDocument();
    expect(screen.getByText('CLIP-20')).toBeInTheDocument();
  });

  it('영상_카드_클릭_시_첫_srcSn_라벨링으로_라우팅', async () => {
    const user = userEvent.setup();
    mockVideos([video({ rawSn: 10, title: 'CLIP-10', firstSrcSn: 100 })]);

    const Probe = () => <div data-testid="label-route">labeling</div>;
    renderWithProviders(<PortalHomePage />, {
      initialEntries: ['/portal'],
      routes: [
        { path: '/portal', element: <PortalHomePage /> },
        { path: '/portal/label/:id', element: <Probe /> },
      ],
    });

    await user.click(screen.getByTestId('datamart-video-item'));

    // 첫 프레임 srcSn(100) 으로 라벨링 진입
    await waitFor(() => expect(screen.getByTestId('label-route')).toBeInTheDocument());
  });

  it('시작하기_CTA_영상_있으면_활성_첫_영상_진입', async () => {
    const user = userEvent.setup();
    mockVideos([video({ rawSn: 10, firstSrcSn: 100 }), video({ rawSn: 20, firstSrcSn: 200 })]);

    const Probe = () => <div data-testid="label-route">labeling</div>;
    renderWithProviders(<PortalHomePage />, {
      initialEntries: ['/portal'],
      routes: [
        { path: '/portal', element: <PortalHomePage /> },
        { path: '/portal/label/:id', element: <Probe /> },
      ],
    });

    const cta = screen.getByRole('button', { name: /시작하기/ });
    expect(cta).not.toHaveAttribute('aria-disabled', 'true');

    await user.click(cta);
    await waitFor(() => expect(screen.getByTestId('label-route')).toBeInTheDocument());
  });

  /*
   * ★ 반전된 가드 — 구 케이스 `다운로드_UI_미제공_V1_5_포털_자체_책임` 을 대체한다(지우지 않고 뒤집는다).
   *
   * 구 단언: 포털 홈에 «다운로드» 관련 요소가 **하나도 없어야 한다**(`[aria-label*="다운로드"]`·
   *          `[data-testid*="download"]` 각 0건).
   * 새 단언: 영상 카드마다 다운로드 버튼이 **있어야 한다**.
   *
   * 왜 뒤집혔나 — 구 V1.5 는 "포털 다운로드는 포털 시스템 자체 책임"이라 저작도구가 제공하지
   * 않는다는 정책이었다. 그 정책이 폐기되고 저작도구가 본인 작업 데이터 다운로드를 제공하는 것으로
   * 확정됐다(서버 경로·보존기간 만료 표기까지 함께). 구 단언을 그대로 두면 확정된 사양이 결함으로
   * 잡힌다.
   */
  it('영상_카드마다_작업_데이터_다운로드_버튼이_있다_V1_5_미제공_정책_폐기', () => {
    mockVideos([video({ rawSn: 10, title: 'CLIP-10' })]);
    renderWithProviders(<PortalHomePage />);

    expect(screen.getByRole('heading', { name: '라벨링' })).toBeInTheDocument();
    expect(
      screen.getByRole('button', { name: 'CLIP-10 작업 데이터 다운로드' }),
    ).toBeInTheDocument();
    expect(screen.getByTestId('datamart-download-button')).toBeInTheDocument();
  });

  it('KPI_2_표시_영상수_라벨링수', () => {
    mockVideos([video(), video({ rawSn: 20, firstSrcSn: 200 })]);
    renderWithProviders(<PortalHomePage />);

    expect(screen.getByText(/영상 수/)).toBeInTheDocument();
    expect(screen.getByText(/라벨링 완료/)).toBeInTheDocument();
  });
});
