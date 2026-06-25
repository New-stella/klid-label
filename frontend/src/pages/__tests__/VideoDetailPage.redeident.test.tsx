import MockAdapter from 'axios-mock-adapter';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { apiClient } from '@/lib/api/client';
import { VideoDetailPage } from '@/pages/VideoDetailPage';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';
import { useUiStore } from '@/stores/useUiStore';

const navigateMock = vi.fn();

vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual<typeof import('react-router-dom')>('react-router-dom');
  return { ...actual, useNavigate: () => navigateMock };
});

function setRole(role: 'REVIEWER' | 'WORKER') {
  useAuthStore.setState({
    token: 'placeholder-jwt',
    claims: { sub: 'u-1', role, channel: 'INTERNAL', exp: 9999999999 },
  });
}

// BE 실제 응답 형태를 모사한다:
//   - status/dataSttsCd = 배치단계(LS_DATA_RAW.DATA_STTS_CD) — 종착이 COMPLETED 라 절대 APPROVED 가 아님.
//   - reviewSttsCd = 검수상태(LS_RAW_DATA_STATUS.DATA_STTS_CD) — APPROVED 가 검수완료 진실원.
// SC-009 재비식별 버튼 노출은 reviewSttsCd 로 판정해야 한다(과거엔 status 로 판정해 영구 미노출 결함).
function mockVideo(mock: MockAdapter, overrides: Record<string, unknown>) {
  mock.onGet('/videos/42').reply(200, {
    success: true,
    data: {
      rawSn: 42,
      vmsCctvId: 'CCTV-42',
      evntTypeCd: 'FIGHT',
      status: 'COMPLETED',
      dataSttsCd: 'COMPLETED',
      regDt: '2026-06-01T10:00:00',
      framePreviews: [],
      ...overrides,
    },
    message: null,
    errorCode: null,
  });
}

const REDEIDENT_LABEL = '재비식별';

function renderPage() {
  return renderWithProviders(<VideoDetailPage />, {
    initialEntries: ['/videos/42'],
    routes: [{ path: '/videos/:id', element: <VideoDetailPage /> }],
  });
}

describe('VideoDetailPage 재비식별 버튼 (SC-009)', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    navigateMock.mockReset();
    useUiStore.setState({ toasts: [] });
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
  });

  it('REVIEWER_검수완료_미비식별_영상에_재비식별_버튼_노출', async () => {
    setRole('REVIEWER');
    mockVideo(mock, { reviewSttsCd: 'APPROVED', deIdntfYn: 'N' });
    renderPage();

    await waitFor(() => expect(screen.getByText('CCTV-42')).toBeInTheDocument());
    expect(screen.getByRole('button', { name: REDEIDENT_LABEL })).toBeInTheDocument();
  });

  // 버그 재현(회귀 가드): 배치단계 status 는 종착 COMPLETED 라 절대 APPROVED 가 아니지만,
  // 검수상태 reviewSttsCd 는 APPROVED. 과거엔 status 로 판정해 버튼이 영구 미노출됐다.
  // reviewSttsCd 로 판정하므로 버튼이 노출되어야 한다(status='COMPLETED' 인데도).
  it('배치단계_COMPLETED_지만_검수완료_APPROVED_미비식별이면_버튼_노출', async () => {
    setRole('REVIEWER');
    mockVideo(mock, { status: 'COMPLETED', reviewSttsCd: 'APPROVED', deIdntfYn: 'N' });
    renderPage();

    await waitFor(() => expect(screen.getByText('CCTV-42')).toBeInTheDocument());
    expect(screen.getByRole('button', { name: REDEIDENT_LABEL })).toBeInTheDocument();
  });

  // 회귀 가드(역방향): status 가 'APPROVED' 처럼 보여도 reviewSttsCd 가 비-APPROVED 면 미노출.
  it('status_가_APPROVED_라도_reviewSttsCd_비APPROVED_면_버튼_숨김', async () => {
    setRole('REVIEWER');
    mockVideo(mock, { status: 'APPROVED', reviewSttsCd: 'IN_REVIEW', deIdntfYn: 'N' });
    renderPage();

    await waitFor(() => expect(screen.getByText('CCTV-42')).toBeInTheDocument());
    expect(screen.queryByRole('button', { name: REDEIDENT_LABEL })).not.toBeInTheDocument();
  });

  it('WORKER_는_재비식별_버튼_숨김', async () => {
    setRole('WORKER');
    mockVideo(mock, { reviewSttsCd: 'APPROVED', deIdntfYn: 'N' });
    renderPage();

    await waitFor(() => expect(screen.getByText('CCTV-42')).toBeInTheDocument());
    expect(screen.queryByRole('button', { name: REDEIDENT_LABEL })).not.toBeInTheDocument();
  });

  it('이미_비식별된_영상_deIdntfYn_Y_이면_버튼_숨김', async () => {
    setRole('REVIEWER');
    mockVideo(mock, { reviewSttsCd: 'APPROVED', deIdntfYn: 'Y' });
    renderPage();

    await waitFor(() => expect(screen.getByText('CCTV-42')).toBeInTheDocument());
    expect(screen.queryByRole('button', { name: REDEIDENT_LABEL })).not.toBeInTheDocument();
  });

  it('검수미완료_영상이면_버튼_숨김', async () => {
    setRole('REVIEWER');
    mockVideo(mock, { reviewSttsCd: 'PENDING', deIdntfYn: 'N' });
    renderPage();

    await waitFor(() => expect(screen.getByText('CCTV-42')).toBeInTheDocument());
    expect(screen.queryByRole('button', { name: REDEIDENT_LABEL })).not.toBeInTheDocument();
  });

  it('버튼_클릭_확인시_POST_호출_성공토스트', async () => {
    const user = userEvent.setup();
    setRole('REVIEWER');
    mockVideo(mock, { reviewSttsCd: 'APPROVED', deIdntfYn: 'N' });
    mock.onPost('/videos/42/redeident').reply(202, {
      success: true,
      data: { rawSn: 42, procLogSn: 7, kpstPrjId: 3, status: 'ACCEPTED' },
      message: null,
      errorCode: null,
    });
    renderPage();

    await waitFor(() => expect(screen.getByText('CCTV-42')).toBeInTheDocument());
    await user.click(screen.getByRole('button', { name: REDEIDENT_LABEL }));

    const dialog = await screen.findByRole('dialog');
    await user.click(within(dialog).getByRole('button', { name: '확인' }));

    await waitFor(() => {
      const posted = mock.history.post.find((r) => r.url === '/videos/42/redeident');
      expect(posted).toBeTruthy();
    });
    await waitFor(() => {
      const toasts = useUiStore.getState().toasts;
      expect(toasts.some((t) => t.variant === 'success')).toBe(true);
    });
  });

  it('409_응답시_에러_토스트', async () => {
    const user = userEvent.setup();
    setRole('REVIEWER');
    mockVideo(mock, { reviewSttsCd: 'APPROVED', deIdntfYn: 'N' });
    mock.onPost('/videos/42/redeident').reply(409, {
      success: false,
      data: null,
      message: '충돌',
      errorCode: 'CONFLICT',
    });
    renderPage();

    await waitFor(() => expect(screen.getByText('CCTV-42')).toBeInTheDocument());
    await user.click(screen.getByRole('button', { name: REDEIDENT_LABEL }));
    const dialog = await screen.findByRole('dialog');
    await user.click(within(dialog).getByRole('button', { name: '확인' }));

    await waitFor(() => {
      const toasts = useUiStore.getState().toasts;
      expect(toasts.some((t) => t.variant === 'error')).toBe(true);
    });
  });

  it('403_응답시_권한없음_에러_토스트', async () => {
    const user = userEvent.setup();
    setRole('REVIEWER');
    mockVideo(mock, { reviewSttsCd: 'APPROVED', deIdntfYn: 'N' });
    mock.onPost('/videos/42/redeident').reply(403, {
      success: false,
      data: null,
      message: '권한없음',
      errorCode: 'FORBIDDEN',
    });
    renderPage();

    await waitFor(() => expect(screen.getByText('CCTV-42')).toBeInTheDocument());
    await user.click(screen.getByRole('button', { name: REDEIDENT_LABEL }));
    const dialog = await screen.findByRole('dialog');
    await user.click(within(dialog).getByRole('button', { name: '확인' }));

    await waitFor(() => {
      const toasts = useUiStore.getState().toasts;
      expect(toasts.some((t) => t.variant === 'error' && t.message.includes('권한'))).toBe(true);
    });
  });
});
