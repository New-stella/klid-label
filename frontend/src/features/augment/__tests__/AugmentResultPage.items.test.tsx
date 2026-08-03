import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';

import { apiClient } from '@/lib/api/client';
import { AugmentResultPage } from '@/pages/AugmentResultPage';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';

/**
 * Phase 5 (D·F·G·H) — 결과 항목 축.
 *
 * 같은 (영상 × 종류) 재요청이 허용된 뒤로 같은 `type` 항목이 여러 건 나온다. 탭을 `type` 으로
 * 키잉하면 오래된 1건만 렌더되고 최신 PENDING 항목의 채택/반려·생성조건에 **도달할 수 없다**.
 * 또 같은 value 탭이 여럿이면 DOM id 가 중복되고 aria-selected 가 복수 true 가 된다(WCAG 위반).
 */
describe('AugmentResultPage 결과 항목(탭 키잉·프롬프트·무결성·취소)', () => {
  let mock: MockAdapter;

  const PROMPT_OLD = JSON.stringify({
    time: 'NIGHT',
    season: 'WINTER',
    weather: 'SNOW',
    terrain: 'ROAD',
    severity: 'LOW',
  });
  const PROMPT_NEW = JSON.stringify({
    time: 'DAWN',
    season: 'WINTER',
    weather: 'RAIN',
    terrain: 'ALLEY',
    severity: 'HIGH',
  });

  const item = (over: Record<string, unknown>) => ({
    id: 1,
    videoId: 101,
    cctvName: 'CCTV-01',
    type: 'WINTER',
    framePairs: [],
    decision: 'PENDING',
    decidedAt: null,
    rejectReason: null,
    derivativeRawSn: null,
    totalFramePairs: 0,
    reviewable: true,
    prompt: null,
    ...over,
  });

  const mockResult = (results: Record<string, unknown>[], status = 'COMPLETED') => {
    mock.onGet('/augments/101/result').reply(200, {
      success: true,
      data: {
        jobId: 101,
        status,
        results,
        message: null,
        page: 0,
        size: 12,
        itemPage: 0,
        itemSize: 20,
        totalElements: results.length,
        totalPages: 1,
      },
      message: null,
      errorCode: null,
    });
  };

  const mockProgress = (id: number, over: Record<string, unknown> = {}) => {
    mock.onGet(`/augments/${id}/progress`).reply(200, {
      success: true,
      data: {
        id,
        augTypeCd: 'WINTER',
        status: 'RUNNING',
        progress: 10,
        unavailableReason: null,
        totalJobCount: 1,
        terminalJobCount: 0,
        cancelable: false,
        nextPollAfterMs: 3000,
        ...over,
      },
      message: null,
      errorCode: null,
    });
  };

  const renderPage = () =>
    renderWithProviders(
      <Routes>
        <Route path="/augment/result/:jobId" element={<AugmentResultPage />} />
      </Routes>,
      { initialEntries: ['/augment/result/101'] },
    );

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    useAuthStore.setState({
      token: 'dummy-token',
      claims: { sub: 'u', role: 'REVIEWER', channel: 'INTERNAL', exp: 9999999999 },
    });
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
  });

  it('같은_종류_증강이_2건이면_각각_별도_탭으로_보인다', async () => {
    // given — WINTER 2건 (최신 PENDING + 과거 REJECTED)
    mockResult([
      item({ id: 21, decision: 'PENDING', prompt: PROMPT_NEW }),
      item({
        id: 20,
        decision: 'REJECTED',
        reviewable: false,
        rejectReason: '품질 미달',
        decidedAt: '2026-07-30T10:00:00',
        prompt: PROMPT_OLD,
      }),
    ]);
    mockProgress(21);
    mockProgress(20, { status: 'SUCCEEDED', progress: 100, nextPollAfterMs: 0 });

    // when
    renderPage();

    // then — 탭이 2개, 각각 다른 접근 이름을 가진다
    await waitFor(() => {
      expect(screen.getAllByRole('tab')).toHaveLength(2);
    });
    const names = screen.getAllByRole('tab').map((t) => t.textContent ?? '');
    expect(new Set(names).size).toBe(2);
    // 선택 상태는 정확히 1개만 true (WCAG)
    expect(
      screen.getAllByRole('tab').filter((t) => t.getAttribute('aria-selected') === 'true'),
    ).toHaveLength(1);
  });

  it('같은_종류_2건_중_최신_PENDING_의_채택반려_버튼에_도달할_수_있다', async () => {
    // given — BE 는 최신순(DESC)으로 내려준다: 21(PENDING) → 20(REJECTED)
    mockResult([
      item({ id: 21, decision: 'PENDING', prompt: PROMPT_NEW }),
      item({
        id: 20,
        decision: 'REJECTED',
        reviewable: false,
        rejectReason: '품질 미달',
        decidedAt: '2026-07-30T10:00:00',
        prompt: PROMPT_OLD,
      }),
    ]);
    mockProgress(21);
    mockProgress(20, { status: 'SUCCEEDED', progress: 100, nextPollAfterMs: 0 });

    // when
    renderPage();

    // then — 첫 탭(최신 PENDING)에서 채택/거부 버튼이 보인다
    await waitFor(() => {
      expect(screen.getByTestId('decision-card')).toHaveAttribute(
        'data-decision',
        'PENDING',
      );
    });
    expect(screen.getByRole('button', { name: '채택' })).toBeInTheDocument();

    // when — 과거 항목 탭으로 전환
    const user = userEvent.setup();
    const tabs = screen.getAllByRole('tab');
    await user.click(tabs[1]);

    // then — 과거 항목은 반려 상태만 보인다(액션 없음)
    await waitFor(() => {
      expect(screen.getByTestId('decision-card')).toHaveAttribute(
        'data-decision',
        'REJECTED',
      );
    });
    expect(screen.getByTestId('decision-reject-reason')).toHaveTextContent('품질 미달');
  });

  it('중복종류_안내가_번호로_최신을_단언하지_않는다', async () => {
    // given — 항목 축은 페이징되므로(itemPage/itemSize) 이 페이지의 #1 은 잡 전체의 최신이 아니다.
    //         구 안내문("#1 이 가장 최근 요청")은 항목이 20건을 넘는 잡에서 거짓이었다.
    mockResult([
      item({ id: 21, decision: 'PENDING' }),
      item({ id: 20, decision: 'REJECTED', reviewable: false }),
    ]);
    mockProgress(21);
    mockProgress(20, { status: 'SUCCEEDED', progress: 100, nextPollAfterMs: 0 });

    // when
    renderPage();

    // then — 근거로 삼을 수 있는 것은 BE 정렬(최신순)뿐이므로 순서로만 말한다
    const section = await screen.findByTestId('augment-result-video-101');
    expect(section).toHaveTextContent('왼쪽일수록 최근 요청');
    expect(section.textContent ?? '').not.toContain('#1 이 가장 최근');
  });

  it('탭_DOM_id_가_중복되지_않는다', async () => {
    // given — 같은 종류 3건
    mockResult([
      item({ id: 31, decision: 'PENDING' }),
      item({ id: 30, decision: 'ACCEPTED', reviewable: false }),
      item({ id: 29, decision: 'REJECTED', reviewable: false }),
    ]);
    mockProgress(31);
    mockProgress(30, { status: 'SUCCEEDED', progress: 100, nextPollAfterMs: 0 });
    mockProgress(29, { status: 'SUCCEEDED', progress: 100, nextPollAfterMs: 0 });

    // when
    renderPage();
    await waitFor(() => {
      expect(screen.getAllByRole('tab')).toHaveLength(3);
    });

    // then — 문서 전체에서 id 중복 0
    const ids = Array.from(document.querySelectorAll('[id]')).map((el) => el.id);
    expect(new Set(ids).size).toBe(ids.length);
  });

  it('항목별_생성조건_프롬프트가_표시된다', async () => {
    // given
    mockResult([item({ id: 21, decision: 'PENDING', prompt: PROMPT_NEW })]);
    mockProgress(21);

    // when
    renderPage();

    // then — 5필드가 라벨과 함께 보인다
    const panel = await screen.findByTestId('augment-prompt-21');
    expect(within(panel).getByText('시간대')).toBeInTheDocument();
    expect(within(panel).getByText('DAWN')).toBeInTheDocument();
    expect(within(panel).getByText('계절')).toBeInTheDocument();
    expect(within(panel).getByText('WINTER')).toBeInTheDocument();
    expect(within(panel).getByText('심각도')).toBeInTheDocument();
    expect(within(panel).getByText('HIGH')).toBeInTheDocument();
  });

  it('프롬프트가_JSON이_아니어도_화면이_깨지지_않는다', async () => {
    // given — 파싱 불가 원문
    mockResult([item({ id: 22, decision: 'PENDING', prompt: 'not-a-json{' })]);
    mockProgress(22);

    // when
    renderPage();

    // then — 원문을 그대로 보여주고 예외로 화면이 죽지 않는다
    const panel = await screen.findByTestId('augment-prompt-22');
    expect(panel).toHaveTextContent('not-a-json{');
    expect(screen.getByTestId('decision-card')).toBeInTheDocument();
  });

  it('해상도파생이_없는_잡에서_라벨무결성_오경보가_뜨지_않는다', async () => {
    // given — 외부 위탁 항목만(프레임 쌍 0건). 구 코드는 0/0 을 0% 로 계산해 "주의" 를 띄웠다.
    mockResult([item({ id: 21, decision: 'PENDING' })], 'COMPLETED');
    mockProgress(21, { status: 'SUCCEEDED', progress: 100, nextPollAfterMs: 0 });

    // when
    renderPage();

    // then
    await waitFor(() => {
      expect(screen.getByTestId('augment-result-video-101')).toBeInTheDocument();
    });
    expect(screen.queryByTestId('augment-result-integrity')).not.toBeInTheDocument();
    expect(document.body.textContent).not.toContain('라벨 무결성');
    expect(document.body.textContent).not.toContain('주의 — 라벨 검수가 필요합니다');
  });

  it('취소된_항목이_완료로_표시되지_않는다', async () => {
    // given — BE 잡 집계는 CANCELED 를 종료로 세어 COMPLETED 를 준다(enum 확장 안 함).
    mockResult([item({ id: 23, decision: 'CANCELED', reviewable: false })], 'COMPLETED');
    mockProgress(23, {
      status: 'CANCELED',
      progress: 100,
      cancelable: false,
      nextPollAfterMs: 0,
    });

    // when
    renderPage();

    // then — 항목 카드가 '취소됨' 으로 보이고 채택/거부 액션이 없다
    await waitFor(() => {
      expect(screen.getByTestId('decision-card')).toHaveAttribute(
        'data-decision',
        'CANCELED',
      );
    });
    expect(screen.getByTestId('decision-card')).toHaveTextContent('취소됨');
    expect(screen.queryByRole('button', { name: '채택' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '거부' })).not.toBeInTheDocument();
    // 페이지 상태 뱃지도 '완료' 로 오표시하지 않는다
    expect(screen.getByTestId('augment-result-status-badge')).toHaveTextContent('취소');
    expect(screen.getByTestId('augment-result-status-badge')).not.toHaveTextContent(
      '완료',
    );
  });
});
