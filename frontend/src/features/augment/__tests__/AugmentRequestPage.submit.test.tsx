import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { apiClient } from '@/lib/api/client';
import { AugmentRequestPage } from '@/pages/AugmentRequestPage';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';

const navigateMock = vi.fn();

vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual<typeof import('react-router-dom')>(
    'react-router-dom',
  );
  return {
    ...actual,
    useNavigate: () => navigateMock,
  };
});

/**
 * SCR-AUG-001 통합 단일 선택 UI (Phase 2) — 선택 후 실행 시나리오 분기 검증.
 *
 * - 증강 종류(WINTER/NIGHT/RAIN) 실행 → POST /augments/request (videoIds·types 길이 1)
 * - 해상도 변경(RESOLUTION) 실행 → POST /videos/{rawSn}/resolution (presets 전달)
 * - 미선택 시 실행 버튼 비활성
 * - 증강 성공 시 결과화면 네비게이션
 * - 해상도 성공 시 결과 카드(파생영상 목록·검수 대기) 표시
 */
describe('AugmentRequestPage 실행 시나리오 분기 (Phase 2)', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    navigateMock.mockClear();
    mock = new MockAdapter(apiClient);
    useAuthStore.setState({
      token: 'tok',
      claims: { sub: 'u', role: 'REVIEWER', channel: 'INTERNAL', exp: 9999999999 },
    });
    mock.onGet('/augments').reply(200, {
      success: true,
      data: { content: [], totalElements: 0, totalPages: 0, number: 0, size: 6 },
      message: null,
      errorCode: null,
    });
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
  });

  const replyVideos = (count: number) => {
    mock.onGet('/videos').reply(200, {
      success: true,
      data: {
        content: Array.from({ length: count }, (_, i) => ({
          id: i + 1,
          cctvName: `CCTV-${i + 1}`,
          vmsClipId: `V${i + 1}`,
          eventName: '쓰러짐',
          eventTypeCd: 'FALL',
          frameCount: 100,
          status: 'COMPLETED',
          capturedAt: '2026-05-01T12:00:00Z',
        })),
        totalElements: count,
        totalPages: 1,
        number: 0,
        size: 20,
      },
      message: null,
      errorCode: null,
    });
  };

  it('증강종류와_영상을_선택하고_실행하면_augments_request가_길이1_배열로_호출된다', async () => {
    replyVideos(2);
    let body: { videoIds?: number[]; types?: string[] } | null = null;
    mock.onPost('/augments/request').reply((config) => {
      body = JSON.parse(config.data as string);
      return [
        200,
        {
          success: true,
          data: {
            jobId: 99,
            requestedAt: '2026-06-16T10:00:00Z',
            videoCount: 1,
            typeCount: 1,
          },
          message: null,
          errorCode: null,
        },
      ];
    });

    const user = userEvent.setup();
    renderWithProviders(<AugmentRequestPage />);

    await user.click(await screen.findByTestId('process-kind-WINTER'));
    await user.click(await screen.findByRole('radio', { name: /CCTV-1 선택/ }));
    await user.click(screen.getByTestId('augment-submit'));

    await waitFor(() => {
      expect(body).not.toBeNull();
    });
    const sent = body as unknown as { videoIds: number[]; types: string[] };
    expect(sent.videoIds).toHaveLength(1);
    expect(sent.videoIds).toEqual([1]);
    expect(sent.types).toHaveLength(1);
    expect(sent.types).toEqual(['WINTER']);
  });

  it('해상도변경_영상_선택후_실행하면_videos_resolution이_presets배열로_호출된다', async () => {
    replyVideos(2);
    let url: string | null = null;
    let body: { presets?: string[] } | null = null;
    mock.onPost(/\/videos\/\d+\/resolution/).reply((config) => {
      url = config.url ?? null;
      body = JSON.parse(config.data as string);
      return [
        201,
        {
          success: true,
          data: {
            derivatives: [
              { rawSn: 101, goalResCd: 'RES_720P', targetW: 1280, targetH: 720, status: 'CREATED' },
            ],
          },
          message: null,
          errorCode: null,
        },
      ];
    });

    const user = userEvent.setup();
    renderWithProviders(<AugmentRequestPage />);

    await user.click(await screen.findByTestId('process-kind-RESOLUTION'));
    await user.click(await screen.findByRole('radio', { name: /CCTV-2 선택/ }));
    // 3종 고정 → 기본 전체 선택. 720P 만 남기고 나머지 2개 해제.
    await user.click(await screen.findByLabelText(/1080P/));
    await user.click(await screen.findByLabelText(/480P/));
    await user.click(screen.getByTestId('augment-submit'));

    await waitFor(() => {
      expect(body).not.toBeNull();
    });
    expect(url).toBe('/videos/2/resolution');
    const sent = body as unknown as { presets: string[] };
    expect(sent.presets).toEqual(['RES_720P']);
  });

  it('영상_미선택이면_실행버튼이_비활성이다', async () => {
    replyVideos(2);
    const user = userEvent.setup();
    renderWithProviders(<AugmentRequestPage />);

    await user.click(await screen.findByTestId('process-kind-WINTER'));
    // 영상 미선택 상태
    expect(screen.getByTestId('augment-submit')).toBeDisabled();
  });

  it('해상도카드_기본전체선택이면_실행버튼이_활성이고_전부해제하면_비활성이다', async () => {
    replyVideos(2);
    const user = userEvent.setup();
    renderWithProviders(<AugmentRequestPage />);

    await user.click(await screen.findByTestId('process-kind-RESOLUTION'));
    await user.click(await screen.findByRole('radio', { name: /CCTV-1 선택/ }));
    // 3종 고정 → 기본 전체 선택이므로 실행 가능.
    expect(screen.getByTestId('augment-submit')).toBeEnabled();

    // 3종 모두 해제 → 생성할 해상도 0개 → 비활성.
    await user.click(await screen.findByLabelText(/1080P/));
    await user.click(await screen.findByLabelText(/720P/));
    await user.click(await screen.findByLabelText(/480P/));
    expect(screen.getByTestId('augment-submit')).toBeDisabled();
  });

  it('증강_실행_성공시_결과화면으로_네비게이션한다', async () => {
    replyVideos(2);
    mock.onPost('/augments/request').reply(200, {
      success: true,
      data: {
        jobId: 42,
        requestedAt: '2026-06-16T10:00:00Z',
        videoCount: 1,
        typeCount: 1,
      },
      message: null,
      errorCode: null,
    });

    const user = userEvent.setup();
    renderWithProviders(<AugmentRequestPage />);

    await user.click(await screen.findByTestId('process-kind-NIGHT'));
    await user.click(await screen.findByRole('radio', { name: /CCTV-1 선택/ }));
    await user.click(screen.getByTestId('augment-submit'));

    await waitFor(() => {
      expect(navigateMock).toHaveBeenCalledWith('/augment/result/42');
    });
  });

  it('해상도_실행_성공시_결과(파생영상목록·검수대기)가_표시된다', async () => {
    replyVideos(2);
    mock.onPost(/\/videos\/\d+\/resolution/).reply(201, {
      success: true,
      data: {
        derivatives: [
          { rawSn: 201, goalResCd: 'RES_1080P', targetW: 1920, targetH: 1080, status: 'CREATED' },
          { rawSn: 202, goalResCd: 'RES_720P', targetW: 1280, targetH: 720, status: 'CREATED' },
          { rawSn: 203, goalResCd: 'RES_480P', targetW: 854, targetH: 480, status: 'CREATED' },
        ],
      },
      message: null,
      errorCode: null,
    });

    const user = userEvent.setup();
    renderWithProviders(<AugmentRequestPage />);

    await user.click(await screen.findByTestId('process-kind-RESOLUTION'));
    await user.click(await screen.findByRole('radio', { name: /CCTV-1 선택/ }));
    // 기본 전체 선택 상태로 실행.
    await user.click(screen.getByTestId('augment-submit'));

    const result = await screen.findByTestId('resolution-derivative-result');
    expect(result).toHaveTextContent('파생영상 3건 생성됨');
    expect(result).toHaveTextContent('검수 대기');
    expect(result).toHaveTextContent('영상 #201');
    // 네비게이션은 발생하지 않음 (해상도는 inline 결과)
    expect(navigateMock).not.toHaveBeenCalled();
  });

  it('해상도_실행_부분실패시_생성N_실패M이_함께_표시된다', async () => {
    replyVideos(2);
    mock.onPost(/\/videos\/\d+\/resolution/).reply(201, {
      success: true,
      data: {
        derivatives: [
          { rawSn: 301, goalResCd: 'RES_1080P', targetW: 1920, targetH: 1080, status: 'CREATED' },
          { rawSn: null, goalResCd: 'RES_480P', targetW: 854, targetH: 480, status: 'FAILED' },
        ],
      },
      message: null,
      errorCode: null,
    });

    const user = userEvent.setup();
    renderWithProviders(<AugmentRequestPage />);

    await user.click(await screen.findByTestId('process-kind-RESOLUTION'));
    await user.click(await screen.findByRole('radio', { name: /CCTV-1 선택/ }));
    await user.click(screen.getByTestId('augment-submit'));

    const result = await screen.findByTestId('resolution-derivative-result');
    expect(result).toHaveTextContent('파생영상 1건 생성됨');
    expect(result).toHaveTextContent('1건 실패');
    expect(result).toHaveTextContent('실패');
  });

  it('해상도_전부실패(500)시_에러메시지가_표시된다', async () => {
    replyVideos(2);
    mock.onPost(/\/videos\/\d+\/resolution/).reply(500, {
      success: false,
      data: null,
      message: '해상도 파생영상 생성에 모두 실패했습니다.',
      errorCode: 'INTERNAL_ERROR',
    });

    const user = userEvent.setup();
    renderWithProviders(<AugmentRequestPage />);

    await user.click(await screen.findByTestId('process-kind-RESOLUTION'));
    await user.click(await screen.findByRole('radio', { name: /CCTV-1 선택/ }));
    await user.click(screen.getByTestId('augment-submit'));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('모두 실패');
  });

  it('해상도_실행후_종류를_바꾸면_결과가_초기화된다', async () => {
    replyVideos(2);
    mock.onPost(/\/videos\/\d+\/resolution/).reply(201, {
      success: true,
      data: {
        derivatives: [
          { rawSn: 401, goalResCd: 'RES_480P', targetW: 854, targetH: 480, status: 'CREATED' },
        ],
      },
      message: null,
      errorCode: null,
    });

    const user = userEvent.setup();
    renderWithProviders(<AugmentRequestPage />);

    await user.click(await screen.findByTestId('process-kind-RESOLUTION'));
    await user.click(await screen.findByRole('radio', { name: /CCTV-1 선택/ }));
    await user.click(screen.getByTestId('augment-submit'));

    expect(
      await screen.findByTestId('resolution-derivative-result'),
    ).toBeInTheDocument();

    // 종류를 증강으로 변경 → 해상도 결과 초기화
    await user.click(screen.getByTestId('process-kind-WINTER'));
    expect(
      screen.queryByTestId('resolution-derivative-result'),
    ).not.toBeInTheDocument();
  });
});
