/**
 * 포털 업로드 영상 마킹 — 재생 주소 구성과 재시도 상한.
 * [@design API-114] [@design API-239] [@design SCREEN-045]
 *
 * 관제 채널 마킹과 <b>같은 결함</b>을 갖고 있었다 — 발급 창구가 돌려주는 주소가 배포 접두를 뺀
 * API 기준 경로(`/api/v1/portal/uploads/{uldSn}/stream?...`)인데 화면이 그대로 물었다.
 * 접두가 있는 배포에서만 드러나므로, 여기서도 그 조건을 반드시 재현한다.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { act, fireEvent, screen, waitFor } from '@testing-library/react';

import { STREAM_REISSUE_LIMIT } from '@/features/marking/hooks/useStreamPlaybackRetry';
import { apiClient } from '@/lib/api/client';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useUiStore } from '@/stores/useUiStore';

import { PortalUploadMarkingPage } from '../PortalUploadMarkingPage';

vi.mock('@/features/marking/components/VideoPlayer', () => ({
  VideoPlayer: ({
    src,
    onSrcError,
    onSrcRecovered,
  }: {
    src: string;
    onSrcError?: () => void;
    onSrcRecovered?: () => void;
  }) => (
    <div data-testid="video-player-stub" data-src={src}>
      <button type="button" data-testid="fire-src-error" onClick={() => onSrcError?.()}>
        재생 실패
      </button>
      <button type="button" data-testid="fire-src-recovered" onClick={() => onSrcRecovered?.()}>
        재생 회복
      </button>
    </div>
  ),
}));

const ULD_SN = 501;
const MARKING_PATH = `/portal/uploads/${ULD_SN}/marking`;
const SIGNED_PATH = `/api/v1/portal/uploads/${ULD_SN}/stream?sig=x`;
const ORIGINAL_BASE_URL = apiClient.defaults.baseURL;

function ok(data: unknown) {
  return { success: true, data, message: null, errorCode: null };
}

function renderPage() {
  return renderWithProviders(<div data-testid="no-match" />, {
    initialEntries: [MARKING_PATH],
    routes: [{ path: '/portal/uploads/:uldSn/marking', element: <PortalUploadMarkingPage /> }],
  });
}

function countStreamUrlRequests(mock: MockAdapter): number {
  return mock.history.get.filter((r) => (r.url ?? '').endsWith('/stream-url')).length;
}

describe('포털 업로드 영상 마킹 — 재생 주소 구성 · 재시도 상한', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    useUiStore.setState({ toasts: [] });
    mock.onGet(`/portal/uploads/${ULD_SN}`).reply(
      200,
      ok({
        uldSn: ULD_SN,
        uldTypeCd: 'VIDEO',
        orgnlFileNm: 'my-clip.mp4',
        fileSz: 1024,
        mimeTypeNm: 'video/mp4',
        uldSttsCd: 'UPLOADED',
        frmeCnt: null,
        vdoLenSec: 10,
        fps: 30,
        regDt: '2026-09-02T00:00:00',
        mdfcnDt: null,
        expiresAt: null,
        frames: [],
      }),
    );
    mock
      .onGet(`/portal/uploads/${ULD_SN}/stream-url`)
      .reply(200, ok({ url: SIGNED_PATH, expiresAt: 1, ttlSeconds: 120 }));
    mock
      .onGet(`/portal/uploads/${ULD_SN}/markings`)
      .reply(200, ok({ uldSn: ULD_SN, markings: [] }));
  });

  afterEach(() => {
    mock.restore();
    apiClient.defaults.baseURL = ORIGINAL_BASE_URL;
  });

  it('★배포_접두가_있는_배포에서_재생주소에_그_접두가_붙는다', async () => {
    apiClient.defaults.baseURL = '/label-studio/api/v1';

    renderPage();

    await waitFor(() => {
      expect(screen.getByTestId('video-player-stub')).toHaveAttribute(
        'data-src',
        `/label-studio/api/v1/portal/uploads/${ULD_SN}/stream?sig=x`,
      );
    });
  });

  it('루트_배포에서는_서버가_준_주소가_그대로다_기존_동작_유지', async () => {
    apiClient.defaults.baseURL = '/api/v1';

    renderPage();

    await waitFor(() => {
      expect(screen.getByTestId('video-player-stub')).toHaveAttribute('data-src', SIGNED_PATH);
    });
  });

  it('★재발급은_상한에서_멈추고_재생_실패_안내로_바뀐다', async () => {
    apiClient.defaults.baseURL = '/label-studio/api/v1';

    renderPage();
    await screen.findByTestId('video-player-stub');
    await waitFor(() => expect(countStreamUrlRequests(mock)).toBe(1));

    // 예산을 끝까지 쓴다.
    for (let i = 0; i < STREAM_REISSUE_LIMIT; i += 1) {
      fireEvent.click(screen.getByTestId('fire-src-error'));
      // eslint-disable-next-line no-await-in-loop
      await waitFor(() => expect(countStreamUrlRequests(mock)).toBe(i + 2));
    }

    // ★상한 분기는 <b>가짜 시계 아래에서</b> 밟는다 — 지연 없이 「더 안 나간다」를 단언하면
    //   나중에 백오프(setTimeout)로 재발급을 미루는 변경이 들어와도 그대로 통과한다(실측: 상한
    //   분기에 setTimeout(reissue, 1500) 을 심어도 전건 통과). 가짜 시계는 그 아래에서 잡힌
    //   타이머만 진행시키므로, 상한 분기를 밟는 클릭 자체가 그 안에 있어야 한다.
    //   ⚠ 이 화면은 상한에 이르면 재생 요소 자리가 실패 안내로 바뀌어 더 흘릴 실패가 없다 —
    //     그래서 그 <b>마지막 클릭</b>이 유일한 기회다.
    vi.useFakeTimers();
    try {
      fireEvent.click(screen.getByTestId('fire-src-error'));
      await act(async () => {
        await vi.advanceTimersByTimeAsync(30_000);
      });
      expect(countStreamUrlRequests(mock)).toBe(STREAM_REISSUE_LIMIT + 1);
    } finally {
      vi.useRealTimers();
    }

    expect(countStreamUrlRequests(mock)).toBe(STREAM_REISSUE_LIMIT + 1);
    // 상한에 이르면 재생 요소 자리가 실패 안내로 바뀐다.
    expect(screen.queryByTestId('fire-src-error')).not.toBeInTheDocument();
    // 이 화면이 이미 갖고 있던 재생 실패 안내를 그대로 쓴다(새 표면을 만들지 않는다).
    expect(await screen.findByText('영상을 재생할 수 없습니다.')).toBeInTheDocument();
  });
  it('★재생이_회복되면_예산이_되살아나_다시_상한까지_재발급한다', async () => {
    // 포털 업로드 영상도 서명 수명이 짧아 마킹 도중 만료가 여러 번 일어난다. 예산을 생애 누적으로
    // 세면 네 번째 만료에서 플레이어 자리가 「영상을 재생할 수 없습니다.」로 굳어 새로고침 말고는
    // 길이 없다.
    apiClient.defaults.baseURL = '/label-studio/api/v1';

    renderPage();
    await screen.findByTestId('video-player-stub');
    await waitFor(() => expect(countStreamUrlRequests(mock)).toBe(1));

    for (let i = 0; i < STREAM_REISSUE_LIMIT; i += 1) {
      fireEvent.click(screen.getByTestId('fire-src-error'));
      // eslint-disable-next-line no-await-in-loop
      await waitFor(() => expect(countStreamUrlRequests(mock)).toBe(i + 2));
    }

    fireEvent.click(screen.getByTestId('fire-src-recovered'));

    for (let i = 0; i < STREAM_REISSUE_LIMIT; i += 1) {
      fireEvent.click(screen.getByTestId('fire-src-error'));
      // eslint-disable-next-line no-await-in-loop
      await waitFor(() =>
        expect(countStreamUrlRequests(mock)).toBe(STREAM_REISSUE_LIMIT + i + 2),
      );
    }
    expect(countStreamUrlRequests(mock)).toBe(STREAM_REISSUE_LIMIT * 2 + 1);

    // then: 재생 요소가 그대로 있다 — 회복된 재생을 실패 안내로 덮지 않는다.
    expect(screen.getByTestId('video-player-stub')).toBeInTheDocument();
    expect(screen.queryByText('영상을 재생할 수 없습니다.')).not.toBeInTheDocument();
  });
});
