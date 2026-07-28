import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';

import { apiClient } from '@/lib/api/client';
import { AugmentResultPage } from '@/pages/AugmentResultPage';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';

/**
 * Phase 3 — 해상도 파생(RESL_*) 증강 결과 비교 화면.
 *
 * 사용자 신고: "해상도 증강을 했을 경우 증강 결과 확인 화면에 비교할 이미지들이 안 나와".
 * 반증된 FE 결함 4건에 대한 회귀 테스트.
 *  ① 이미지 401 (raw <img> → 인증 blob 로딩)
 *  ② 해상도 타입 라벨 undefined
 *  ③ BE 페이징 미소비 (13장째 접근 불가)
 *  ④ 내부 생성물인데 채택/거부 카드 오표시
 */
describe('AugmentResultPage 해상도 파생 결과', () => {
  let mock: MockAdapter;

  const TOTAL_PAIRS = 15;

  const pairsFor = (page: number, size: number) => {
    const from = page * size;
    const to = Math.min(TOTAL_PAIRS, from + size);
    return Array.from({ length: Math.max(0, to - from) }, (_, i) => {
      const frameNo = from + i;
      return {
        srcSn: 9000 + frameNo,
        frameNo,
        originalUrl: `/v1/frames/${8000 + frameNo}/deid-image`,
        augmentedUrl: `/v1/frames/${9000 + frameNo}/deid-image`,
      };
    });
  };

  const resolutionResult = (page: number, size: number) => ({
    id: 55,
    videoId: 101,
    cctvName: 'CCTV-01',
    type: 'RESL_720P',
    decision: 'ACCEPTED',
    decidedAt: '2026-07-28T10:00:00',
    rejectReason: null,
    derivativeRawSn: 102,
    totalFramePairs: TOTAL_PAIRS,
    reviewable: false,
    framePairs: pairsFor(page, size),
  });

  const renderPage = () =>
    renderWithProviders(
      <Routes>
        <Route path="/augment/result/:jobId" element={<AugmentResultPage />} />
      </Routes>,
      { initialEntries: ['/augment/result/101'] },
    );

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    let seq = 0;
    Object.defineProperty(URL, 'createObjectURL', {
      configurable: true,
      writable: true,
      value: vi.fn(() => `blob:mock/${++seq}`),
    });
    Object.defineProperty(URL, 'revokeObjectURL', {
      configurable: true,
      writable: true,
      value: vi.fn(),
    });
    mock.onGet(/\/frames\/\d+\/deid-image$/).reply(200, new Blob(['img']));
    useAuthStore.setState({
      token: 'dummy-token',
      claims: { sub: 'u', role: 'REVIEWER', channel: 'INTERNAL', exp: 9999999999 },
    });
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
  });

  const mockResult = () => {
    mock.onGet('/augments/101/result').reply((config) => {
      const page = Number(config.params?.page ?? 0);
      const size = Number(config.params?.size ?? 12);
      return [
        200,
        {
          success: true,
          data: {
            jobId: 101,
            status: 'COMPLETED',
            page,
            size,
            results: [resolutionResult(page, size)],
            message: null,
          },
          message: null,
          errorCode: null,
        },
      ];
    });
  };

  it('해상도_파생_비교이미지가_인증blob으로_렌더된다', async () => {
    // given
    mockResult();

    // when
    renderPage();

    // then — 좌(원본 비식별)·우(파생) 슬롯이 모두 <img blob:...> 로 렌더된다
    await waitFor(() => {
      expect(screen.getByTestId('frame-pair-9000-original').tagName).toBe('IMG');
    });
    expect(screen.getByTestId('frame-pair-9000-processed').tagName).toBe('IMG');
    expect(screen.getByTestId('frame-pair-9000-original')).toHaveAttribute(
      'src',
      expect.stringContaining('blob:'),
    );
    // raw <img src="/v1/frames/..."> 사용 금지 — 401 로 깨진다
    const imgs = document.querySelectorAll('img');
    for (const img of Array.from(imgs)) {
      expect(img.getAttribute('src') ?? '').not.toContain('/v1/frames/');
    }
  });

  it('해상도_타입_라벨이_undefined가_아니라_사람이_읽는_문구로_표시된다', async () => {
    // given
    mockResult();

    // when
    renderPage();

    // then
    await waitFor(() => {
      expect(screen.getByTestId('augment-result-summary')).toBeInTheDocument();
    });
    expect(screen.getAllByText(/해상도 720p/).length).toBeGreaterThan(0);
    expect(document.body.textContent).not.toContain('undefined');
    // 기술 코드(RESL_*) 가 화면에 새면 안 된다
    expect(document.body.textContent).not.toContain('RESL_');
  });

  it('프레임이_12장을_넘으면_다음_페이지로_나머지_쌍에_접근할_수_있다', async () => {
    // given
    mockResult();
    const user = userEvent.setup();
    renderPage();
    await waitFor(() => {
      expect(screen.getByTestId('frame-pair-9000-original')).toBeInTheDocument();
    });
    // 1페이지는 12쌍 — 13번째(frameNo 12)는 아직 없다
    expect(screen.queryByTestId('frame-pair-9012')).not.toBeInTheDocument();
    // BE 페이징 파라미터를 실제로 보낸다
    const first = mock.history.get.find((r) => r.url === '/augments/101/result');
    expect(first?.params).toMatchObject({ page: 0, size: 12 });

    // when
    const pager = screen.getByTestId('augment-frame-pager');
    await user.click(within(pager).getByLabelText('다음 페이지'));

    // then
    await waitFor(() => {
      expect(screen.getByTestId('frame-pair-9012-original')).toBeInTheDocument();
    });
    expect(screen.getByTestId('frame-pair-9014-original')).toBeInTheDocument();
  });

  it('해상도_파생결과에는_채택거부_카드를_표시하지_않는다', async () => {
    // given
    mockResult();

    // when
    renderPage();

    // then
    await waitFor(() => {
      expect(screen.getByTestId('frame-grid-12')).toBeInTheDocument();
    });
    expect(screen.queryByTestId('decision-card')).not.toBeInTheDocument();
  });

  it('해상도_파생_결과가_있으면_외부연동_대기_안내를_표시하지_않는다', async () => {
    // given
    mockResult();

    // when
    renderPage();

    // then
    await waitFor(() => {
      expect(screen.getByTestId('frame-grid-12')).toBeInTheDocument();
    });
    expect(
      screen.queryByTestId('augment-result-completed-empty'),
    ).not.toBeInTheDocument();
  });

  it('외부증강_WINTER_결과는_기존대로_채택거부_카드를_표시한다', async () => {
    // given — reviewable=true (외부 위탁 증강)
    mock.onGet('/augments/101/result').reply(200, {
      success: true,
      data: {
        jobId: 101,
        status: 'COMPLETED',
        page: 0,
        size: 12,
        results: [
          {
            id: 7,
            videoId: 101,
            cctvName: 'CCTV-01',
            type: 'WINTER',
            decision: 'PENDING',
            totalFramePairs: 1,
            reviewable: true,
            framePairs: [
              {
                srcSn: 9100,
                frameNo: 0,
                originalUrl: '/v1/frames/8100/deid-image',
                augmentedUrl: '/v1/frames/9100/deid-image',
              },
            ],
          },
        ],
        message: null,
      },
      message: null,
      errorCode: null,
    });

    // when
    renderPage();

    // then
    await waitFor(() => {
      expect(screen.getByTestId('decision-card')).toBeInTheDocument();
    });
    const section = screen.getByTestId('augment-result-video-101');
    expect(within(section).getAllByText('겨울').length).toBeGreaterThan(0);
  });
});
