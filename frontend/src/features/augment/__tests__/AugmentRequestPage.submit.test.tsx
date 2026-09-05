import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { fireEvent, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { apiClient } from '@/lib/api/client';
import { AugmentRequestPage } from '@/pages/AugmentRequestPage';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';
import { useUiStore } from '@/stores/useUiStore';

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
 * 증강 요청의 생성 조건 축 — BE 필수 계약(연동명세서 v1.3 · ADR-059).
 *
 * 생성 조건 5항목이 모두 채워져야 제출 버튼이 활성화된다. 값은 자유 문자열이 아니라
 * **허용 코드**이며 화면은 드롭다운으로만 고르게 한다. 해상도 변경 경로는 외부 위탁이
 * 아니라 이 입력이 필요 없다.
 *
 * ⚠ 이벤트 유형 입력은 걷어냈다(ADR-059) — 되살리지 말 것.
 */
const fillPromptFields = () => {
  const values: Record<string, string> = {
    시간대: 'NIGHT',
    계절: 'WINTER',
    날씨: 'RAIN',
    지형: 'ROAD',
    심각도: 'HIGH',
  };
  Object.entries(values).forEach(([label, value]) => {
    fireEvent.change(screen.getByLabelText(new RegExp(label)), {
      target: { value },
    });
  });
};

/**
 * SCR-AUG-001 통합 단일 선택 UI (Phase 2) — 선택 후 실행 시나리오 분기 검증.
 *
 * - 증강 AI(단일값 AUGMENT) 실행 → POST /augments/request (videoIds·types 길이 1)
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
      token: 'dummy-token',
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

    await user.click(await screen.findByTestId('process-kind-AUGMENT'));
    await user.click(await screen.findByRole('radio', { name: /CCTV-1 선택/ }));
    fillPromptFields();
    await user.click(screen.getByTestId('augment-submit'));

    await waitFor(() => {
      expect(body).not.toBeNull();
    });
    const sent = body as unknown as { videoIds: number[]; types: string[] };
    expect(sent.videoIds).toHaveLength(1);
    expect(sent.videoIds).toEqual([1]);
    expect(sent.types).toHaveLength(1);
    expect(sent.types).toEqual(['AUGMENT']);
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
              { rawSn: 101, goalResCd: 'RESL_720P', targetW: 1280, targetH: 720, status: 'CREATED' },
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
    expect(sent.presets).toEqual(['RESL_720P']);
  });

  it('영상_미선택이면_실행버튼이_비활성이다', async () => {
    replyVideos(2);
    const user = userEvent.setup();
    renderWithProviders(<AugmentRequestPage />);

    await user.click(await screen.findByTestId('process-kind-AUGMENT'));
    fillPromptFields();
    // 영상 미선택 상태 — 생성 조건을 다 채워도 제출 불가
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

  /**
   * ⚠ 이 테스트는 한때 `'/augment/result/42'`(= 응답 jobId) 를 성공으로 단언해 **죽은 링크를
   * 굳히고 있었다**. BE 의 `jobId` 는 placeholder 카운터(`AugmentRequestService#jobIdSeq`)이고
   * 결과 API 의 경로변수는 **원본 영상 RAW_SN** 이라, 그 값으로 이동하면 결과 0건 화면에 고착된다.
   * 이동 축은 "요청한 영상" 이어야 하므로 선택한 영상 id(=RAW_SN)로 단언한다.
   */
  it('증강_실행_성공시_요청한_영상의_결과화면으로_이동한다', async () => {
    replyVideos(2);
    mock.onPost('/augments/request').reply(200, {
      success: true,
      data: {
        // placeholder jobId — 어떤 엔티티의 식별자도 아니다(이 값으로 이동하면 안 된다)
        jobId: 1_753_900_000_042,
        requestedAt: '2026-06-16T10:00:00Z',
        videoCount: 1,
        typeCount: 1,
      },
      message: null,
      errorCode: null,
    });

    const user = userEvent.setup();
    renderWithProviders(<AugmentRequestPage />);

    await user.click(await screen.findByTestId('process-kind-AUGMENT'));
    // CCTV-2 = videoId 2 (RAW_SN). 첫 영상이 아니어야 "우연히 맞음" 을 배제할 수 있다.
    await user.click(await screen.findByRole('radio', { name: /CCTV-2 선택/ }));
    fillPromptFields();
    await user.click(screen.getByTestId('augment-submit'));

    await waitFor(() => {
      expect(navigateMock).toHaveBeenCalledWith('/augment/result/2');
    });
    // placeholder jobId 는 어떤 경로에도 쓰이지 않는다
    expect(navigateMock).not.toHaveBeenCalledWith(
      '/augment/result/1753900000042',
    );
  });

  it('해상도_실행_성공시_결과(파생영상목록·검수대기)가_표시된다', async () => {
    replyVideos(2);
    mock.onPost(/\/videos\/\d+\/resolution/).reply(201, {
      success: true,
      data: {
        derivatives: [
          { rawSn: 201, goalResCd: 'RESL_1080P', targetW: 1920, targetH: 1080, status: 'CREATED' },
          { rawSn: 202, goalResCd: 'RESL_720P', targetW: 1280, targetH: 720, status: 'CREATED' },
          { rawSn: 203, goalResCd: 'RESL_480P', targetW: 854, targetH: 480, status: 'CREATED' },
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
          { rawSn: 301, goalResCd: 'RESL_1080P', targetW: 1920, targetH: 1080, status: 'CREATED' },
          { rawSn: null, goalResCd: 'RESL_480P', targetW: 854, targetH: 480, status: 'FAILED' },
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

  it('증강_요청_실패시_BE_사용자메시지가_토스트로_노출된다', async () => {
    // given: 구 버그 — onError 가 항상 고정 문자열 '증강 요청 실패' 만 띄워
    // BE 가 내려준 실패 사유(예: 검증 실패 안내)가 사용자에게 전달되지 않았다.
    replyVideos(2);
    mock.onPost('/augments/request').reply(400, {
      success: false,
      data: null,
      message: '동일 조건으로 처리 중인 요청이 있습니다.',
      errorCode: 'AUGMENT_DUPLICATE_IN_PROGRESS',
    });

    const user = userEvent.setup();
    renderWithProviders(<AugmentRequestPage />);

    await user.click(await screen.findByTestId('process-kind-AUGMENT'));
    await user.click(await screen.findByRole('radio', { name: /CCTV-1 선택/ }));
    fillPromptFields();
    await user.click(screen.getByTestId('augment-submit'));

    await waitFor(() => {
      const toasts = useUiStore.getState().toasts;
      expect(toasts.some((t) => t.message === '동일 조건으로 처리 중인 요청이 있습니다.')).toBe(
        true,
      );
    });
    expect(
      useUiStore.getState().toasts.some((t) => t.message === '증강 요청 실패'),
    ).toBe(false);
  });

  it('해상도_실행후_종류를_바꾸면_결과가_초기화된다', async () => {
    replyVideos(2);
    mock.onPost(/\/videos\/\d+\/resolution/).reply(201, {
      success: true,
      data: {
        derivatives: [
          { rawSn: 401, goalResCd: 'RESL_480P', targetW: 854, targetH: 480, status: 'CREATED' },
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
    await user.click(screen.getByTestId('process-kind-AUGMENT'));
    expect(
      screen.queryByTestId('resolution-derivative-result'),
    ).not.toBeInTheDocument();
  });
});
