// 마킹 화면 — 검증 질문 선택 회귀 가드. [@design SCREEN-006] [@design API-047] [@design API-043]
//
// 고정하는 계약:
//  ① 목록 출처는 <b>영상 단건 조회 응답</b>이다 — 관리 화면 경로(/manage/verification-event-types)는
//     검수자 전용이라 작업자에게 403 이므로 마킹 화면이 그걸 부르면 안 된다.
//  ② 기본 선택 = 정렬순서 첫 번째. BE 가 정렬해 내려주므로 화면이 다시 정렬하지 않는다.
//  ③ 고른 값이 마킹 등록 요청에 실린다.
//  ④ 유형·질문이 없으면 선택 UI 를 띄우지 않고, 그래도 마킹은 그대로 저장된다.
//
// ⚠ mutation 확인 절차: MarkingPage 의 questionPayload 전개를 지우면 ③이, 기본 선택 useEffect 를
//   지우면 ②가, VerificationQuestionSelect 의 `questions.length === 0` early return 을 지우면 ④가
//   각각 FAIL 해야 한다.

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { apiClient } from '@/lib/api/client';
import { MarkingPage } from '@/pages/MarkingPage';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';
import { useUiStore } from '@/stores/useUiStore';
import { useMarkingStore } from '@/features/marking/store';

const navigateMock = vi.fn();

vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual<typeof import('react-router-dom')>('react-router-dom');
  return { ...actual, useNavigate: () => navigateMock, useParams: () => ({ rawSn: '42' }) };
});

// <video> 는 jsdom 미지원 — stub.
vi.mock('@/features/marking/components/VideoPlayer', () => ({
  VideoPlayer: vi.fn(() => <div data-testid="video-player-stub" />),
}));

const QUESTIONS = [
  { vrfcEvntQstnSn: 11, qstnCn: '첫 번째 질문 — 화염이 보이는 불이 발생하였는가?' },
  { vrfcEvntQstnSn: 12, qstnCn: '두 번째 질문 — 연기가 확산되었는가?' },
];

describe('마킹 화면 — 검증 질문 선택', () => {
  let mock: MockAdapter;

  function mockVideoDetail(extra: Record<string, unknown>) {
    mock.onGet('/videos/42').reply(200, {
      success: true,
      data: {
        id: 42,
        rawSn: 42,
        cctvName: 'CCTV-42',
        deIdntfYn: 'Y',
        deidentStatus: 'DONE',
        dataSttsCd: 'MARKING_READY',
        ...extra,
      },
      message: null,
      errorCode: null,
    });
  }

  /** 마킹 등록 POST 를 가로채 본문을 돌려준다. */
  function captureMarkingPost(): { body: () => Record<string, unknown> } {
    let captured: Record<string, unknown> = {};
    mock.onPost(/\/videos\/42\/markings/).reply((config) => {
      captured = JSON.parse(config.data ?? '{}') as Record<string, unknown>;
      return [
        200,
        {
          markingSn: 1,
          rawSn: 42,
          eventName: '화재',
          markingMode: 'AUTO',
          intervalFrames: 300,
          videoPath: '/p',
          marks: [],
          status: 'PENDING',
          createdAt: '2026-08-25T10:00:00Z',
        },
      ];
    });
    return { body: () => captured };
  }

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    navigateMock.mockReset();
    useMarkingStore.getState().reset();
    useUiStore.setState({ toasts: [] });
    useAuthStore.setState({
      token: 'tok',
      claims: { sub: 'w-1', role: 'WORKER', channel: 'INTERNAL', exp: 9999999999 },
    });
    mock.onGet(/\/videos\/42\/stream-url/).reply(200, {
      url: '/api/v1/videos/42/stream?exp=9999999999&sig=s',
      expiresAt: 9999999999,
      ttlSeconds: 60,
    });
    mock.onGet(/\/videos\/42\/markings/).reply(200, []);
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
  });

  it('질문_목록이_있으면_첫_번째가_기본_선택된다', async () => {
    // given: 영상 단건 응답이 검증 유형 + 질문 2건을 싣는다.
    mockVideoDetail({ vrfcEvntTypeCd: 'fire', vrfcEvntQuestions: QUESTIONS });

    // when
    renderWithProviders(<MarkingPage />, { initialEntries: ['/marking/42'] });

    // then: 드롭다운이 뜨고 기본 선택이 정렬순서 첫 번째다.
    const select = await screen.findByLabelText(/마킹과 함께 저장할 질문/);
    expect((select as HTMLSelectElement).value).toBe('11');
    // 전문 병기 — 드롭다운에서 잘릴 수 있는 긴 문구를 그대로 확인할 수 있어야 한다.
    expect(screen.getAllByText(QUESTIONS[0].qstnCn).length).toBeGreaterThan(0);
  });

  it('관리_화면_전용_경로를_호출하지_않는다', async () => {
    // 작업자에게 403 인 경로다 — 목록은 영상 단건 응답에서만 온다.
    mockVideoDetail({ vrfcEvntTypeCd: 'fire', vrfcEvntQuestions: QUESTIONS });

    renderWithProviders(<MarkingPage />, { initialEntries: ['/marking/42'] });
    await screen.findByLabelText(/마킹과 함께 저장할 질문/);

    expect(mock.history.get.some((r) => (r.url ?? '').includes('/manage/'))).toBe(false);
  });

  it('고른_질문이_마킹_등록_요청에_실린다', async () => {
    // given
    mockVideoDetail({ vrfcEvntTypeCd: 'fire', vrfcEvntQuestions: QUESTIONS });
    const post = captureMarkingPost();
    const user = userEvent.setup();

    renderWithProviders(<MarkingPage />, { initialEntries: ['/marking/42'] });
    const select = await screen.findByLabelText(/마킹과 함께 저장할 질문/);

    // when: 두 번째 질문으로 바꾸고 자동 모드로 전환한 뒤 제출
    //   (기본 모드는 수동이고 마크 0건이면 제출이 토스트로 막힌다 — 질문 축과 무관한 축이다.)
    await user.selectOptions(select, '12');
    await user.click(screen.getByRole('button', { name: '자동' }));
    await user.click(screen.getByRole('button', { name: /마킹 완료/ }));

    // then
    await waitFor(() => expect(post.body().vrfcEvntQstnSn).toBe(12));
    expect(post.body().mode).toBe('AUTO');
  });

  it('바꾸지_않으면_첫_번째_질문이_실린다', async () => {
    mockVideoDetail({ vrfcEvntTypeCd: 'fire', vrfcEvntQuestions: QUESTIONS });
    const post = captureMarkingPost();
    const user = userEvent.setup();

    renderWithProviders(<MarkingPage />, { initialEntries: ['/marking/42'] });
    await screen.findByLabelText(/마킹과 함께 저장할 질문/);
    await user.click(screen.getByRole('button', { name: '자동' }));
    await user.click(screen.getByRole('button', { name: /마킹 완료/ }));

    await waitFor(() => expect(post.body().vrfcEvntQstnSn).toBe(11));
  });

  it('질문이_0건이면_선택_UI가_뜨지_않고_마킹은_그대로_저장된다', async () => {
    // given: 검증 유형은 있으나 등록된 질문이 없다 — 고를 것이 없다.
    mockVideoDetail({ vrfcEvntTypeCd: 'fire', vrfcEvntQuestions: [] });
    const post = captureMarkingPost();
    const user = userEvent.setup();

    renderWithProviders(<MarkingPage />, { initialEntries: ['/marking/42'] });
    await screen.findByText(/마킹 — 영상 #42/);

    // then: 선택 영역 자체가 없다(빈 드롭다운은 "고를 수 있는데 비어 있다"로 읽힌다).
    await waitFor(() =>
      expect(screen.queryByTestId('verification-question-section')).toBeNull(),
    );

    // and: 질문이 없다고 마킹이 막히지는 않는다 — 필드 없이 그대로 저장된다.
    await user.click(screen.getByRole('button', { name: '자동' }));
    await user.click(screen.getByRole('button', { name: /마킹 완료/ }));
    await waitFor(() => expect(post.body().mode).toBe('AUTO'));
    expect(post.body()).not.toHaveProperty('vrfcEvntQstnSn');
  });

  it('검증_유형이_미수신이면_선택_UI가_뜨지_않는다', async () => {
    // 구 응답(필드 부재)도 같은 경로로 떨어진다 — 빈 배열로 정규화된다.
    mockVideoDetail({});

    renderWithProviders(<MarkingPage />, { initialEntries: ['/marking/42'] });
    await screen.findByText(/마킹 — 영상 #42/);

    await waitFor(() =>
      expect(screen.queryByTestId('verification-question-section')).toBeNull(),
    );
  });
});
