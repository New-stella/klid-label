import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { apiClient } from '@/lib/api/client';
import { AugmentRequestPage } from '@/pages/AugmentRequestPage';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';

describe('AugmentRequestPage', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    useAuthStore.setState({
      token: 'tok',
      claims: { sub: 'u', role: 'REVIEWER', channel: 'INTERNAL', exp: 9999999999 },
    });
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
  });

  it('처리종류_카드_2종_렌더_단일선택', async () => {
    mock.onGet('/augments').reply(200, {
      success: true,
      data: { content: [], totalElements: 0, totalPages: 0, number: 0, size: 6 },
      message: null,
      errorCode: null,
    });
    mock.onGet('/videos').reply(200, {
      success: true,
      data: { content: [], totalElements: 0, totalPages: 0, number: 0, size: 20 },
      message: null,
      errorCode: null,
    });

    const user = userEvent.setup();
    renderWithProviders(<AugmentRequestPage />);

    await waitFor(() => {
      expect(screen.getByTestId('process-kind-list')).toBeInTheDocument();
    });

    const augment = screen.getByTestId('process-kind-AUGMENT');
    const resolution = screen.getByTestId('process-kind-RESOLUTION');

    expect(augment).toBeInTheDocument();
    // 통합 단일 선택 UI 에서는 해상도 변경(RESOLUTION)도 같은 카드 그리드에 포함된다.
    expect(resolution).toBeInTheDocument();
    // 구 3종(겨울·야간·우천)은 카드가 아니라 생성 조건 프리셋이 됐다(ADR-059).
    expect(screen.queryByTestId('process-kind-WINTER')).not.toBeInTheDocument();
    expect(screen.queryByTestId('process-kind-NIGHT')).not.toBeInTheDocument();
    expect(screen.queryByTestId('process-kind-RAIN')).not.toBeInTheDocument();

    // 단일 선택: 한 카드를 고르면 나머지는 해제 상태
    await user.click(augment);
    expect(augment).toHaveAttribute('aria-checked', 'true');
    expect(resolution).toHaveAttribute('aria-checked', 'false');
  });

  it('AugmentRequestPage_영상_목록_size_20_페이지_로드', async () => {
    let videosCall: { page?: number; size?: number; dataSttsCd?: string } | null =
      null;
    mock.onGet('/augments').reply(200, {
      success: true,
      data: { content: [], totalElements: 0, totalPages: 0, number: 0, size: 6 },
      message: null,
      errorCode: null,
    });
    mock.onGet('/videos').reply((config) => {
      videosCall = config.params as typeof videosCall;
      return [
        200,
        {
          success: true,
          data: {
            content: [],
            totalElements: 0,
            totalPages: 0,
            number: 0,
            size: 20,
          },
          message: null,
          errorCode: null,
        },
      ];
    });

    renderWithProviders(<AugmentRequestPage />);

    await waitFor(() => {
      expect(videosCall).not.toBeNull();
    });
    const params = videosCall as unknown as {
      size?: number;
      page?: number;
      dataSttsCd?: string;
    };
    expect(params.size).toBe(20);
    expect(params.page).toBe(0);
    expect(params.dataSttsCd).toBe('COMPLETED');
  });

  it('AugmentRequestPage_다음_페이지_클릭_시_BE_호출_page_1', async () => {
    const calls: number[] = [];
    mock.onGet('/augments').reply(200, {
      success: true,
      data: { content: [], totalElements: 0, totalPages: 0, number: 0, size: 6 },
      message: null,
      errorCode: null,
    });
    mock.onGet('/videos').reply((config) => {
      const page = Number((config.params as { page?: number })?.page ?? 0);
      calls.push(page);
      const all = Array.from({ length: 25 }, (_, i) => ({
        id: i + 1,
        cctvName: `CCTV-${i + 1}`,
        vmsClipId: `V${i + 1}`,
        eventName: '쓰러짐',
        eventTypeCd: 'FALL',
        frameCount: 100,
        status: 'COMPLETED',
        capturedAt: '2026-05-01T12:00:00Z',
      }));
      const content = all.slice(page * 20, page * 20 + 20);
      return [
        200,
        {
          success: true,
          data: {
            content,
            totalElements: 25,
            totalPages: 2,
            number: page,
            size: 20,
          },
          message: null,
          errorCode: null,
        },
      ];
    });

    const user = userEvent.setup();
    renderWithProviders(<AugmentRequestPage />);

    await waitFor(() => {
      expect(screen.getByText('CCTV-1')).toBeInTheDocument();
    });

    const nextBtn = screen.getByRole('button', { name: /다음/ });
    await user.click(nextBtn);

    await waitFor(() => {
      expect(screen.getByText('CCTV-21')).toBeInTheDocument();
    });
    expect(calls).toContain(1);
  });

  it('AugmentRequestPage_페이지_이동_후_선택된_videoId_보존_단일선택', async () => {
    mock.onGet('/augments').reply(200, {
      success: true,
      data: { content: [], totalElements: 0, totalPages: 0, number: 0, size: 6 },
      message: null,
      errorCode: null,
    });
    mock.onGet('/videos').reply((config) => {
      const page = Number((config.params as { page?: number })?.page ?? 0);
      const all = Array.from({ length: 25 }, (_, i) => ({
        id: i + 1,
        cctvName: `CCTV-${i + 1}`,
        vmsClipId: `V${i + 1}`,
        eventName: '쓰러짐',
        eventTypeCd: 'FALL',
        frameCount: 100,
        status: 'COMPLETED',
        capturedAt: '2026-05-01T12:00:00Z',
      }));
      const content = all.slice(page * 20, page * 20 + 20);
      return [
        200,
        {
          success: true,
          data: {
            content,
            totalElements: 25,
            totalPages: 2,
            number: page,
            size: 20,
          },
          message: null,
          errorCode: null,
        },
      ];
    });

    const user = userEvent.setup();
    renderWithProviders(<AugmentRequestPage />);

    // 1페이지에서 CCTV-1 단일 선택
    await waitFor(() => {
      expect(screen.getByText('CCTV-1')).toBeInTheDocument();
    });
    await user.click(screen.getByRole('radio', { name: /CCTV-1 선택/ }));

    await waitFor(() => {
      expect(screen.getByText(/영상 #1 선택됨/)).toBeInTheDocument();
    });

    // 다음 페이지 이동 후에도 선택 상태(단일) 유지
    await user.click(screen.getByRole('button', { name: /다음/ }));
    await waitFor(() => {
      expect(screen.getByText('CCTV-21')).toBeInTheDocument();
    });
    expect(screen.getByText(/영상 #1 선택됨/)).toBeInTheDocument();

    // 2페이지에서 CCTV-21 선택 → 단일 선택이므로 #21 로 교체
    await user.click(screen.getByRole('radio', { name: /CCTV-21 선택/ }));
    await waitFor(() => {
      expect(screen.getByText(/영상 #21 선택됨/)).toBeInTheDocument();
    });
  });

  it('SFR_06_03_해상도_변경_종류_선택시_타겟해상도_UI_노출', async () => {
    mock.onGet('/augments').reply(200, {
      success: true,
      data: { content: [], totalElements: 0, totalPages: 0, number: 0, size: 6 },
      message: null,
      errorCode: null,
    });
    mock.onGet('/videos').reply(200, {
      success: true,
      data: { content: [], totalElements: 0, totalPages: 0, number: 0, size: 20 },
      message: null,
      errorCode: null,
    });

    const user = userEvent.setup();
    renderWithProviders(<AugmentRequestPage />);

    const resolution = await screen.findByTestId('process-kind-RESOLUTION');

    // 종류 선택 전엔 생성할 해상도 UI 가 노출되지 않는다
    expect(screen.queryByTestId('target-resolution-block')).not.toBeInTheDocument();

    await user.click(resolution);

    expect(await screen.findByTestId('target-resolution-block')).toBeInTheDocument();
  });

  it('잡_카드_5초_폴링_상태_변화_반영', async () => {
    let callCount = 0;
    mock.onGet('/augments').reply(() => {
      callCount += 1;
      const status = callCount === 1 ? 'IN_PROGRESS' : 'COMPLETED';
      return [
        200,
        {
          success: true,
          data: {
            content: [
              {
                jobId: 1,
                videoId: 10,
                cctvName: 'CCTV-A',
                types: ['WINTER'],
                status,
                requestedAt: '2026-05-07T10:00:00Z',
                videoCount: 1,
              },
            ],
            totalElements: 1,
            totalPages: 1,
            number: 0,
            size: 6,
          },
          message: null,
          errorCode: null,
        },
      ];
    });
    mock.onGet('/videos').reply(200, {
      success: true,
      data: { content: [], totalElements: 0, totalPages: 0, number: 0, size: 20 },
      message: null,
      errorCode: null,
    });

    renderWithProviders(<AugmentRequestPage />);

    await waitFor(() => {
      const card = screen.getByTestId('job-card-1');
      expect(card.dataset.status).toBe('IN_PROGRESS');
    });

    expect(callCount).toBeGreaterThanOrEqual(1);
  });
});
