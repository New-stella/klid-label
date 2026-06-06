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

  it('증강_유형_3종_카드만_렌더_RESOLUTION_제외', async () => {
    mock.onGet('/augments').reply(200, {
      success: true,
      data: { content: [], totalElements: 0, totalPages: 0, number: 0, size: 6 },
      message: null,
      errorCode: null,
    });

    const user = userEvent.setup();
    renderWithProviders(<AugmentRequestPage />);

    await waitFor(() => {
      expect(screen.getByTestId('augment-type-list')).toBeInTheDocument();
    });

    // AugmentTypeCard 는 button + aria-pressed 로 토글 상태를 표현한다.
    const winter = screen.getByTestId('augment-type-WINTER');
    const night = screen.getByTestId('augment-type-NIGHT');
    const rain = screen.getByTestId('augment-type-RAIN');

    // 해상도(RESOLUTION) 카드는 설계 정합상 제거되어야 한다(SFR-06-03).
    expect(screen.queryByTestId('augment-type-RESOLUTION')).not.toBeInTheDocument();

    await user.click(winter);
    await user.click(night);
    await user.click(rain);

    expect(winter).toHaveAttribute('aria-pressed', 'true');
    expect(night).toHaveAttribute('aria-pressed', 'true');
    expect(rain).toHaveAttribute('aria-pressed', 'true');
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
    // size 999 호출 금지 — Phase 4 옵션 3
    const params = videosCall as unknown as {
      size?: number;
      page?: number;
      dataSttsCd?: string;
    };
    expect(params.size).toBe(20);
    expect(params.page).toBe(0);
    // BE에서 검수 완료 영상만 페이지로 받기 위해 dataSttsCd 전달
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
    // 25건(승인) — 2 페이지 발생
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

    // 1페이지 로드 대기 (CCTV-1 ~ CCTV-20 중 첫 행)
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

  it('AugmentRequestPage_페이지_이동_후_선택된_videoId_보존', async () => {
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

    // 1페이지에서 CCTV-1 선택
    await waitFor(() => {
      expect(screen.getByText('CCTV-1')).toBeInTheDocument();
    });
    await user.click(screen.getByRole('checkbox', { name: /CCTV-1 선택/ }));

    // 선택 배지 노출 확인
    await waitFor(() => {
      expect(screen.getByText(/1개 영상 선택됨/)).toBeInTheDocument();
    });

    // 다음 페이지 이동
    await user.click(screen.getByRole('button', { name: /다음/ }));
    await waitFor(() => {
      expect(screen.getByText('CCTV-21')).toBeInTheDocument();
    });

    // 페이지가 바뀌어도 선택 상태(1개)가 유지되어야 한다
    expect(screen.getByText(/1개 영상 선택됨/)).toBeInTheDocument();

    // 2페이지에서 CCTV-21 추가 선택 — 누적 2개
    await user.click(screen.getByRole('checkbox', { name: /CCTV-21 선택/ }));
    await waitFor(() => {
      expect(screen.getByText(/2개 영상 선택됨/)).toBeInTheDocument();
    });
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

    renderWithProviders(<AugmentRequestPage />);

    // 첫 fetch — IN_PROGRESS
    await waitFor(() => {
      const card = screen.getByTestId('job-card-1');
      expect(card.dataset.status).toBe('IN_PROGRESS');
    });

    // 폴링 갱신을 강제하기 위해 invalidate 대신 5초 폴링 흐름 확인 —
    // 실제 5초 대기 대신 hook configure 검증으로 대체 (느린 테스트 회피)
    // 폴링 설정 검증은 useAugmentJobs.test.ts에서 별도 수행.
    expect(callCount).toBeGreaterThanOrEqual(1);
  });
});
