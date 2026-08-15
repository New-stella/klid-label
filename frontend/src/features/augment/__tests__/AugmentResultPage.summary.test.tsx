import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen, waitFor, within } from '@testing-library/react';
import { Route, Routes } from 'react-router-dom';

import { apiClient } from '@/lib/api/client';
import { AugmentResultPage } from '@/pages/AugmentResultPage';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';

/**
 * DEV_FIX 회귀 — **현재 페이지 부분 집계를 잡 전체값처럼 표시하지 않는다**.
 *
 * 결함: 요약 카드의 "증강 유형"·"총 처리 이미지" 는 `data.results`(= 현재 **항목 페이지**)만 순회한
 * 값인데 잡 전체 이름으로 렌더됐다. 같은 잡인데 페이지를 넘기면 값이 바뀌고(항목 22건 잡의 1페이지
 * "0장"), 외부 위탁만 있는 완료 잡은 실제로 처리를 마쳐도 항상 "0장" 이었다.
 *
 * BE 가 잡 전체 집계를 주지 않으므로 **추정하지 않고**(외삽 금지) 라벨에 범위를 명시한다.
 *
 * ⚠ 그 뒤 "증강 유형" 칸 자체가 확정 사양(5칸)에 없어 제거됐다 — 범위 표기 규칙의 대표 사례는
 *   이제 "대상 영상"·"비교 프레임 쌍" 이다. 위 서술은 결함 당시 기록이다.
 */
describe('AugmentResultPage 요약 카드 집계 축', () => {
  let mock: MockAdapter;

  const externalItem = (id: number) => ({
    id,
    videoId: 101,
    cctvName: 'CCTV-01',
    type: 'WINTER',
    framePairs: [],
    decision: 'ACCEPTED',
    decidedAt: '2026-07-30T10:00:00',
    rejectReason: null,
    derivativeRawSn: null,
    totalFramePairs: 0,
    reviewable: false,
    prompt: null,
  });

  const mockResult = (results: Record<string, unknown>[], totalElements: number) => {
    mock.onGet('/augments/101/result').reply(200, {
      success: true,
      data: {
        jobId: 101,
        status: 'COMPLETED',
        results,
        message: null,
        page: 0,
        size: 12,
        itemPage: 0,
        itemSize: 20,
        totalElements,
        totalPages: Math.ceil(totalElements / 20),
      },
      message: null,
      errorCode: null,
    });
  };

  const renderPage = () =>
    renderWithProviders(
      <Routes>
        <Route path="/augment/result/:rawSn" element={<AugmentResultPage />} />
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
    mock.onGet(/\/augments\/\d+\/progress$/).reply(200, {
      success: true,
      data: {
        id: 1,
        augTypeCd: 'WINTER',
        status: 'SUCCEEDED',
        progress: 100,
        unavailableReason: null,
        totalJobCount: 1,
        terminalJobCount: 1,
        cancelable: false,
        nextPollAfterMs: 0,
      },
      message: null,
      errorCode: null,
    });
    useAuthStore.setState({
      token: 'dummy-token',
      claims: { sub: 'u', role: 'REVIEWER', channel: 'INTERNAL', exp: 9999999999 },
    });
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
  });

  it('항목이_여러_페이지면_부분집계에_페이지_범위를_명시한다', async () => {
    // given — 항목 22건 중 20건만 이 페이지에 실렸다
    mockResult(
      Array.from({ length: 20 }, (_, i) => externalItem(100 + i)),
      22,
    );

    // when
    renderPage();

    // then — 페이지 부분합은 "이 페이지" 라고 밝히고, 잡 전체값(결과 항목)만 총량으로 말한다
    const summary = await screen.findByTestId('augment-result-summary');
    expect(within(summary).getByText(/대상 영상 \(이 페이지\)/)).toBeInTheDocument();
    expect(
      within(summary).getByText(/비교 프레임 쌍 \(이 페이지\)/),
    ).toBeInTheDocument();
    expect(within(summary).getByText('22건')).toBeInTheDocument();
    // 처리 이미지 수를 아는 것처럼 말하지 않는다
    expect(summary.textContent ?? '').not.toContain('총 처리 이미지');
  });

  it('한_페이지가_전체_항목을_담으면_범위표기를_붙이지_않는다', async () => {
    // given — 항목 1건 = 전체
    mockResult([externalItem(100)], 1);

    // when
    renderPage();

    // then
    const summary = await screen.findByTestId('augment-result-summary');
    await waitFor(() => {
      expect(within(summary).getByText('대상 영상')).toBeInTheDocument();
    });
    expect(summary.textContent ?? '').not.toContain('(이 페이지)');
    expect(within(summary).getByText('비교 프레임 쌍')).toBeInTheDocument();
  });

  it('프레임_쌍이_없는_외부위탁_잡을_처리_0장이라고_말하지_않는다', async () => {
    // given — 외부 위탁 항목은 프레임별 산출물 연동 전이라 쌍이 0건이다(처리량이 0이 아니다)
    mockResult([externalItem(100)], 1);

    // when
    renderPage();

    // then — "0쌍(비교 프레임 쌍)" 은 사실이지만 "처리 이미지 0장" 은 사실이 아니다
    const summary = await screen.findByTestId('augment-result-summary');
    expect(screen.getByTestId('augment-result-page-pairs')).toHaveTextContent('0쌍');
    expect(summary.textContent ?? '').not.toContain('장');
  });

  /**
   * 확정 사양 회귀 — 작업 요약은 **5칸**이며 "증강 유형" 칸을 두지 않는다.
   *
   * 구 구현은 요청일시를 더하면서 6칸이 됐고, 사양에 없는 "증강 유형"(항목 유형 집합)이 남아 있었다.
   * 유형은 결과 항목 단위 정보라 항목 탭 라벨과 비교 이미지 라벨이 항목마다 이미 보여 준다.
   *
   * ⚠ 칸 **개수만** 세면 어느 칸이 사라졌는지 모르므로 라벨 목록까지 순서대로 고정한다.
   */
  it('작업_요약은_5칸이고_증강_유형_칸을_두지_않는다', async () => {
    // given — 항목 1건 = 전체(범위 표기 없는 형상)
    mockResult([externalItem(100)], 1);

    // when
    renderPage();

    // then — 칸 수와 각 칸의 라벨을 함께 고정한다
    const summary = await screen.findByTestId('augment-result-summary');
    await waitFor(() => {
      expect(summary.querySelectorAll('dt')).toHaveLength(5);
    });
    expect(
      Array.from(summary.querySelectorAll('dt')).map((dt) => dt.textContent),
    ).toEqual(['작업 ID', '대상 영상', '결과 항목', '비교 프레임 쌍', '요청일시']);
    expect(summary.textContent ?? '').not.toContain('증강 유형');
  });

  it('요약_그리드_열수는_칸수와_같은_5열이다', async () => {
    // given
    mockResult([externalItem(100)], 1);

    // when
    renderPage();

    // then — 칸을 지우면서 열 수(6)를 그대로 두면 마지막 열이 빈 채로 남는다.
    //   `md` 는 살아 있는 브레이크포인트다(theme.screens = md·xl). `sm`/`lg`/`2xl` 은 죽은 접두사라
    //   그 접두사로 되돌리면 반응형 자체가 사라진다.
    const summary = await screen.findByTestId('augment-result-summary');
    const dl = summary.querySelector('dl');
    expect(dl).not.toBeNull();
    expect(dl?.className).toContain('md:grid-cols-5');
    expect(dl?.className).not.toContain('grid-cols-6');
  });

  it('증강_유형은_요약이_아니라_결과_항목_단위로_보인다', async () => {
    // given — 요약에서 유형 칸을 지워도 유형 정보 자체가 화면에서 사라지면 안 된다
    mockResult([externalItem(100)], 1);

    // when
    renderPage();

    // then — 항목 탭 라벨이 유형(WINTER=겨울)을 그대로 보여 준다
    await screen.findByTestId('augment-result-summary');
    const tab = await screen.findByRole('tab', { name: /겨울/ });
    expect(tab).toBeInTheDocument();
  });

  it('증강_이미지_생성률은_계산_근거를_함께_밝힌다', async () => {
    // given — 프레임 쌍 2건 중 1건만 증강 이미지가 있다(현재 프레임 페이지 기준)
    mockResult(
      [
        {
          ...externalItem(200),
          type: 'RESL_720P',
          totalFramePairs: 30,
          framePairs: [
            {
              srcSn: 9000,
              frameNo: 0,
              originalUrl: '/v1/frames/8000/deid-image',
              augmentedUrl: '/v1/frames/9000/deid-image',
            },
            {
              srcSn: 9001,
              frameNo: 1,
              originalUrl: '/v1/frames/8001/deid-image',
              augmentedUrl: null,
            },
          ],
        },
      ],
      1,
    );

    // when
    renderPage();

    // then — 라벨을 검사한 값이 아니므로 "라벨 무결성" 이라고 말하지 않는다
    const card = await screen.findByTestId('augment-result-integrity');
    expect(card).toHaveTextContent('증강 이미지 생성률');
    expect(card).toHaveTextContent('50%');
    expect(card).toHaveTextContent('현재 화면에 표시된 프레임 2쌍 기준');
    expect(document.body.textContent).not.toContain('라벨 무결성');
  });
});
