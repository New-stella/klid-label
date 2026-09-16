/**
 * 마킹 화면 — 재생 주소 구성과 재시도 상한. [@design API-114] [@design SCREEN-006] [@design SEQ-036]
 *
 * <h3>왜 접두가 있는 조건을 반드시 재현하는가</h3>
 * 서명 발급 응답의 `url` 은 배포 접두를 뺀 API 기준 경로다. 앱이 루트에 서비스되는 배포에서는
 * 접두가 빈 문자열이라 <b>결합해도 안 해도 결과가 같아</b>, 로컬 조건만 두면 결합을 통째로
 * 지워도 시험이 통과한다. 실제 결함이 개발 내내 드러나지 않은 이유가 정확히 그것이다.
 * 그래서 「접두 있음」과 「접두 없음(현행 유지)」을 <b>함께</b> 둔다.
 *
 * <h3>왜 상한을 세는가</h3>
 * 재생이 실패할 때마다 주소를 다시 받아 물리면, 회복되지 않는 실패에서 발급→실패→발급이 끝없이
 * 돈다(실측: 7초에 150회 이상). 「무한히 돌지 않는다」는 <b>요청 건수</b>로만 확인할 수 있으므로
 * 상한을 넘겨 계속 실패를 흘린 뒤 요청이 더 늘지 않는지 센다.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { act, fireEvent, screen, waitFor } from '@testing-library/react';

import { ToastProvider } from '@/components/common/ToastProvider';
import { STREAM_REISSUE_LIMIT } from '@/features/marking/hooks/useStreamPlaybackRetry';
import { apiClient } from '@/lib/api/client';
import { MarkingPage } from '@/pages/MarkingPage';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';
import { useMarkingStore } from '@/features/marking/store';
import { useUiStore } from '@/stores/useUiStore';

vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual<typeof import('react-router-dom')>('react-router-dom');
  return { ...actual, useNavigate: () => vi.fn(), useParams: () => ({ rawSn: '42' }) };
});

// jsdom 에 <video> 가 없어 대역을 쓴다. src 를 그대로 노출하고, 재생 실패를 사람이 흘릴 수 있게
// 버튼 하나를 둔다 — 실패를 여러 번 흘려야 「상한에서 멈춘다」를 관측할 수 있다.
vi.mock('@/features/marking/components/VideoPlayer', () => ({
  VideoPlayer: ({
    src,
    onSrcError,
    onSrcRecovered,
    sourceKey,
  }: {
    src: string;
    onSrcError?: () => void;
    onSrcRecovered?: () => void;
    sourceKey?: string | number;
  }) => (
    <div
      data-testid="video-player-stub"
      data-src={src}
      data-source-key={sourceKey === undefined ? '' : String(sourceKey)}
    >
      <button type="button" data-testid="fire-src-error" onClick={() => onSrcError?.()}>
        재생 실패
      </button>
      {/* 회복(재생 가능)도 사람이 흘릴 수 있어야 「예산이 되살아난다」를 관측할 수 있다. */}
      <button type="button" data-testid="fire-src-recovered" onClick={() => onSrcRecovered?.()}>
        재생 회복
      </button>
    </div>
  ),
}));

const SIGNED_PATH = '/api/v1/videos/42/stream?exp=9999999999&u=2001&sig=testsig';
const ORIGINAL_BASE_URL = apiClient.defaults.baseURL;

function countStreamUrlRequests(mock: MockAdapter): number {
  return mock.history.get.filter((r) => (r.url ?? '').includes('/videos/42/stream-url')).length;
}

describe('MarkingPage — 재생 주소 구성 · 재시도 상한', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    useMarkingStore.getState().reset();
    useUiStore.setState({ toasts: [] });
    useAuthStore.setState({
      token: 'sample-token',
      claims: { sub: 'u-7', role: 'WORKER', channel: 'INTERNAL', exp: 9999999999 },
    });
    // 매 발급이 같은 주소를 돌려준다 — 이 시험의 관심은 주소의 <b>구성</b>과 <b>횟수</b>이지
    // 서명 값의 변화가 아니다.
    mock.onGet(/\/videos\/42\/stream-url/).reply(200, {
      url: SIGNED_PATH,
      expiresAt: 9999999999,
      ttlSeconds: 60,
    });
    mock.onGet(/\/videos\/42\/markings/).reply(200, []);
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
    apiClient.defaults.baseURL = ORIGINAL_BASE_URL;
  });

  it('★배포_접두가_있는_배포에서_재생주소에_그_접두가_붙는다', async () => {
    // given: 컨텍스트 경로 아래에 배포된 형상 — 이 조건에서만 결함이 드러난다.
    apiClient.defaults.baseURL = '/label-studio/api/v1';

    renderWithProviders(<MarkingPage />, { initialEntries: ['/marking/42'] });

    // then: 접두가 빠지면 요청이 같은 오리진의 다른 시스템 경로로 나가 영상이 오지 않는다.
    await waitFor(() => {
      expect(screen.getByTestId('video-player-stub')).toHaveAttribute(
        'data-src',
        '/label-studio/api/v1/videos/42/stream?exp=9999999999&u=2001&sig=testsig',
      );
    });
  });

  it('루트_배포에서는_서버가_준_주소가_그대로다_기존_동작_유지', async () => {
    apiClient.defaults.baseURL = '/api/v1';

    renderWithProviders(<MarkingPage />, { initialEntries: ['/marking/42'] });

    await waitFor(() => {
      expect(screen.getByTestId('video-player-stub')).toHaveAttribute('data-src', SIGNED_PATH);
    });
  });

  it('★재생기에_영상_식별값을_넘긴다_재발급_위치_보존이_같은_영상에만_걸리게', async () => {
    // [@design SCREEN-006] 재발급으로 src 가 바뀔 때 위치를 복원하는 것은 「같은 영상」일 때뿐이다.
    // 식별값이 빠지면 재생기는 영상이 바뀌었는지 가를 수 없다.
    apiClient.defaults.baseURL = '/api/v1';

    renderWithProviders(<MarkingPage />, { initialEntries: ['/marking/42'] });

    await waitFor(() => {
      expect(screen.getByTestId('video-player-stub')).toHaveAttribute('data-source-key', '42');
    });
  });

  it('★재발급은_상한에서_멈추고_그_이후에는_요청이_늘지_않는다', async () => {
    apiClient.defaults.baseURL = '/label-studio/api/v1';

    renderWithProviders(
      <ToastProvider>
        <MarkingPage />
      </ToastProvider>,
      { initialEntries: ['/marking/42'] },
    );
    await screen.findByTestId('video-player-stub');
    await waitFor(() => expect(countStreamUrlRequests(mock)).toBe(1)); // 최초 발급

    // when: 상한을 넉넉히 넘겨 실패를 계속 흘린다. 한 발이 끝날 때까지 기다렸다 다음을 흘려야
    //       「진행 중 무시」가 아니라 <b>누적 상한</b>이 멈춘 것임이 드러난다.
    const attempts = STREAM_REISSUE_LIMIT + 5;
    for (let i = 0; i < attempts; i += 1) {
      fireEvent.click(screen.getByTestId('fire-src-error'));
      // eslint-disable-next-line no-await-in-loop
      await waitFor(() =>
        expect(countStreamUrlRequests(mock)).toBe(
          Math.min(i + 1, STREAM_REISSUE_LIMIT) + 1,
        ),
      );
    }

    // then: 재발급은 상한만큼만 나갔다(최초 발급 1건 + 재발급 상한).
    expect(countStreamUrlRequests(mock)).toBe(STREAM_REISSUE_LIMIT + 1);

    // then: ★<b>시간이 지나도</b> 늘지 않는다. 지연 없이 「더 안 나간다」를 단언하면 나중에
    //       백오프(setTimeout)로 재발급을 미루는 변경이 들어와도 이 가드가 통과한다(실측: 상한
    //       분기에 setTimeout(reissue, 1500) 을 심어도 전건 통과). 상한 분기를 가짜 시계 아래에서
    //       한 번 더 밟고 시간을 크게 진행시킨다 — 그 아래에서 잡힌 타이머만 진행되기 때문이다.
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

    // then: 멈춘 사실을 사용자에게 알린다 — 이 화면이 이미 쓰는 안내 수단(토스트)을 재사용한다.
    expect(
      await screen.findByText('영상을 재생할 수 없습니다. 잠시 후 다시 시도해 주세요.'),
    ).toBeInTheDocument();

    // then: 통지는 한 번만 — 실패가 계속 흘러도 같은 안내가 쌓이지 않는다.
    expect(
      useUiStore
        .getState()
        .toasts.filter((t) => t.message.startsWith('영상을 재생할 수 없습니다')),
    ).toHaveLength(1);
  });
  it('★재생이_회복되면_예산이_되살아나_다시_상한까지_재발급한다', async () => {
    // 서명 수명은 짧고(기본 60초) 주기 갱신이 없어, 한 영상을 몇 분간 탐색하며 마킹하는
    // <b>정상 동선</b>에서 만료가 여러 번 일어난다. 예산을 생애 누적으로 세면 네 번째 만료에서
    // 회복이 영구히 막혀 새로고침 말고는 길이 없다.
    apiClient.defaults.baseURL = '/label-studio/api/v1';

    renderWithProviders(
      <ToastProvider>
        <MarkingPage />
      </ToastProvider>,
      { initialEntries: ['/marking/42'] },
    );
    await screen.findByTestId('video-player-stub');
    await waitFor(() => expect(countStreamUrlRequests(mock)).toBe(1));

    // given: 예산을 끝까지 쓴다(아직 멈추지는 않은 지점)
    for (let i = 0; i < STREAM_REISSUE_LIMIT; i += 1) {
      fireEvent.click(screen.getByTestId('fire-src-error'));
      // eslint-disable-next-line no-await-in-loop
      await waitFor(() => expect(countStreamUrlRequests(mock)).toBe(i + 2));
    }

    // when: 새 주소로 재생이 실제로 회복됐다
    fireEvent.click(screen.getByTestId('fire-src-recovered'));

    // then: 예산이 되살아나 다시 상한만큼 재발급한다
    for (let i = 0; i < STREAM_REISSUE_LIMIT; i += 1) {
      fireEvent.click(screen.getByTestId('fire-src-error'));
      // eslint-disable-next-line no-await-in-loop
      await waitFor(() =>
        expect(countStreamUrlRequests(mock)).toBe(STREAM_REISSUE_LIMIT + i + 2),
      );
    }
    expect(countStreamUrlRequests(mock)).toBe(STREAM_REISSUE_LIMIT * 2 + 1);

    // then: 회복된 재생은 실패가 아니다 — 실패 안내를 띄우지 않는다
    expect(
      useUiStore
        .getState()
        .toasts.filter((t) => t.message.startsWith('영상을 재생할 수 없습니다')),
    ).toHaveLength(0);
  });
});
