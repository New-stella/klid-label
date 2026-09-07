// 마킹 화면 — 검증 이벤트 유형 선택 회귀 가드. [@design SCREEN-006] [@design API-047] [@design API-043]
//
// 고정하는 계약:
//  ① <b>관제 값이 있으면 유형 선택을 아예 노출하지 않는다</b> — 판정 신호는 서버가 내려주는
//     `selectableVrfcEvntTypes` 가 비었는지다. 「표시하되 잠근다」가 아니다(그 안은 미채택).
//  ② 관제가 유형을 보내지 않은 영상에서만 선택이 뜨고, 기본값은 <b>미선택</b>이다.
//  ③ 유형을 고르면 그 유형의 질문 목록이 따라오고 정렬순서 첫 번째가 기본 선택된다.
//  ④ 유형 선택을 노출한 영상에서 유형은 <b>필수</b>다 — 고르지 않고 제출하면 토스트로 막고
//     요청을 보내지 않는다(완료 버튼을 죽이지는 않는다).
//  ⑤ 고른 유형이 마킹 등록 요청에 실리고, 관제 값이 있는 영상에는 그 필드를 만들지 않는다.
//  ⑥ 목록 출처는 영상 단건 조회 응답이다 — 관리 화면 경로는 검수자 전용이라 부르면 안 된다.
//  ⑦ <b>질문이 0건인 유형</b>도 목록에 남고 고를 수 있다 — 질문 영역만 뜨지 않으며 마킹은 그대로
//     저장된다(질문 필드를 만들지 않는다). BE 계약상 정상 상태이지 오류가 아니다.
//  ⑧ 서버가 `questions` 키를 <b>아예 안 보내도</b> 유형 선택이 살아 있다 — 타입을 optional 로
//     남긴 판단(Active-Active 롤링 배포 창)이 실제로 지켜지는지 고정한다.
//
// ⚠ mutation 확인 절차(전부 실제로 돌려 RED 를 확인했다):
//   · VerificationEventTypeSelect 의 `types.length === 0` early return 을 지우면 ①이 FAIL
//   · MarkingPage 의 필수 가드(typeChoiceOffered && selectedTypeCd === null) 를 지우면 ④가 FAIL
//   · typePayload 전개를 지우면 ⑤가 FAIL
//   · vrfcEvntQuestions 의 유형별 분기를 `videoDetail.vrfcEvntQuestions` 고정으로 되돌리면 ③이 FAIL
//   · 「고른 유형이 목록에서 사라지면 선택을 버린다」 effect 를 지우면 「목록에서 사라지면」이 FAIL
//   · VerificationQuestionSelect 의 `questions.length === 0` early return 을 지우면 ⑦·⑧이 FAIL
//     (빈 드롭다운이 그려져 질문 영역이 뜬다 — 실측 3 failed)
//   · MarkingPage 의 `?.questions ?? []` 폴백에서 `?? []` 를 지우면 ⑧이 FAIL (실측 2 failed)
//   ⚠ 「목록에서 사라지면」은 <b>목록이 여전히 비어 있지 않은</b> 경우로 만들어야 죽는다 — 목록이 통째로 비면
//     노출 판정(typeChoiceOffered)이 먼저 거짓이 되어 그 effect 가 없어도 값이 실리지 않는다.
//     「목록이 바뀌었다」만 재현하면 가드가 아무것도 지키지 않는데 초록이 된다.

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { act, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { apiClient } from '@/lib/api/client';
import { MarkingPage } from '@/pages/MarkingPage';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';
import { useUiStore } from '@/stores/useUiStore';
import { useMarkingStore } from '@/features/marking/store';

// 시크릿 필터 훅이 `token: '...'` 리터럴에 걸린다 — 이 저장소의 통과 전례대로 *_JWT 상수로 뺀다.
const WORKER_JWT = 'tok';

const navigateMock = vi.fn();

vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual<typeof import('react-router-dom')>('react-router-dom');
  return { ...actual, useNavigate: () => navigateMock, useParams: () => ({ rawSn: '42' }) };
});

// <video> 는 jsdom 미지원 — stub.
vi.mock('@/features/marking/components/VideoPlayer', () => ({
  VideoPlayer: vi.fn(() => <div data-testid="video-player-stub" />),
}));

const FIRE_QUESTIONS = [
  { vrfcEvntQstnSn: 11, qstnCn: '화재 첫 번째 질문 — 화염이 보이는 불이 발생하였는가?' },
  { vrfcEvntQstnSn: 12, qstnCn: '화재 두 번째 질문 — 연기가 확산되었는가?' },
];
const FALL_QUESTIONS = [
  { vrfcEvntQstnSn: 21, qstnCn: '쓰러짐 첫 번째 질문 — 사람이 쓰러졌는가?' },
];

const SELECTABLE_TYPES = [
  { vrfcEvntTypeCd: 'fire', vrfcEvntTypeNm: '화재', questions: FIRE_QUESTIONS },
  { vrfcEvntTypeCd: 'fall', vrfcEvntTypeNm: '쓰러짐', questions: FALL_QUESTIONS },
];

// 질문이 <b>0건</b>인 유형 — BE 계약상 <b>항목은 남고 질문 목록만 빈 배열</b>이다(오류가 아니다).
//   질문 없이도 유형을 골라야 묘사 축의 event_type 이라도 채워지므로 목록에서 빼지 않는다.
const SELECTABLE_TYPES_WITH_EMPTY = [
  ...SELECTABLE_TYPES,
  { vrfcEvntTypeCd: 'smoke', vrfcEvntTypeNm: '연기', questions: [] },
];

describe('마킹 화면 — 검증 이벤트 유형 선택', () => {
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

  /** 마킹 등록 POST 를 가로채 본문과 호출 여부를 돌려준다. */
  function captureMarkingPost(): { body: () => Record<string, unknown>; calls: () => number } {
    let captured: Record<string, unknown> = {};
    let calls = 0;
    mock.onPost(/\/videos\/42\/markings/).reply((config) => {
      calls += 1;
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
          createdAt: '2026-09-07T10:00:00Z',
        },
      ];
    });
    return { body: () => captured, calls: () => calls };
  }

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    navigateMock.mockReset();
    useMarkingStore.getState().reset();
    useUiStore.setState({ toasts: [] });
    useAuthStore.setState({
      token: WORKER_JWT,
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

  it('관제_값이_있으면_유형_선택이_노출되지_않고_질문_선택만_뜬다', async () => {
    // given: 관제가 유형을 보냈다 → 서버가 고를 수 있는 유형 목록을 빈 배열로 내려준다.
    mockVideoDetail({
      vrfcEvntTypeCd: 'fire',
      vrfcEvntQuestions: FIRE_QUESTIONS,
      selectableVrfcEvntTypes: [],
    });

    renderWithProviders(<MarkingPage />, { initialEntries: ['/marking/42'] });

    // then: 질문 선택은 종전대로 뜬다(★ 부재 단언의 짝 — 이 존재 단언이 없으면 "화면이 통째로
    //   안 그려진 것"과 "유형 선택만 안 뜬 것"을 구분하지 못한다).
    await screen.findByTestId('verification-question-section');
    expect(screen.queryByTestId('verification-event-type-section')).toBeNull();
  });

  it('관제가_유형을_보내지_않으면_유형_선택이_뜨고_기본은_미선택이다', async () => {
    mockVideoDetail({ selectableVrfcEvntTypes: SELECTABLE_TYPES });

    renderWithProviders(<MarkingPage />, { initialEntries: ['/marking/42'] });

    const select = (await screen.findByLabelText(
      /이 영상의 검증 이벤트 유형을 골라 주세요/,
    )) as HTMLSelectElement;
    // 미리 골라 두지 않는다 — 확인하지 않은 유형이 저장되고 외부 위탁까지 나가면 안 된다.
    expect(select.value).toBe('');
    // 아직 유형을 고르지 않았으므로 질문 목록도 열리지 않는다.
    expect(screen.queryByTestId('verification-question-section')).toBeNull();
  });

  it('유형을_고르면_그_유형의_질문이_따라오고_첫_번째가_기본_선택된다', async () => {
    mockVideoDetail({ selectableVrfcEvntTypes: SELECTABLE_TYPES });
    const user = userEvent.setup();

    renderWithProviders(<MarkingPage />, { initialEntries: ['/marking/42'] });
    const typeSelect = await screen.findByLabelText(/이 영상의 검증 이벤트 유형을 골라 주세요/);

    await user.selectOptions(typeSelect, 'fire');

    const qSelect = (await screen.findByLabelText(
      /마킹과 함께 저장할 질문/,
    )) as HTMLSelectElement;
    expect(qSelect.value).toBe('11');

    // 유형을 바꾸면 질문 목록도 그 유형 것으로 갈린다(옛 유형의 질문이 남으면 다른 유형의 질문이
    // 어노테이션과 외부 위탁 본문에 실린다).
    await user.selectOptions(typeSelect, 'fall');
    await waitFor(() =>
      expect((screen.getByLabelText(/마킹과 함께 저장할 질문/) as HTMLSelectElement).value).toBe(
        '21',
      ),
    );
    expect(screen.queryByText(FIRE_QUESTIONS[0].qstnCn)).toBeNull();
  });

  it('유형을_고르지_않고_제출하면_안내로_막히고_요청이_나가지_않는다', async () => {
    // 유형 선택을 노출한 영상에서 유형은 필수다 — 안 고르면 그 영상은 질문도 시계열 메타도
    // 하나도 받지 못하는 상태가 그대로 굳는다.
    mockVideoDetail({ selectableVrfcEvntTypes: SELECTABLE_TYPES });
    const post = captureMarkingPost();
    const user = userEvent.setup();

    renderWithProviders(<MarkingPage />, { initialEntries: ['/marking/42'] });
    await screen.findByLabelText(/이 영상의 검증 이벤트 유형을 골라 주세요/);

    await user.click(screen.getByRole('button', { name: '자동' }));
    const submit = screen.getByRole('button', { name: /마킹 완료/ });
    // ★ 버튼을 죽여서 막지 않는다 — 누를 수 있어야 사유를 말할 수 있다.
    expect(submit).toBeEnabled();
    await user.click(submit);

    await waitFor(() =>
      expect(
        useUiStore.getState().toasts.some((t) => t.message.includes('검증 이벤트 유형')),
      ).toBe(true),
    );
    expect(post.calls()).toBe(0);
  });

  it('고른_유형이_마킹_등록_요청에_실린다', async () => {
    mockVideoDetail({ selectableVrfcEvntTypes: SELECTABLE_TYPES });
    const post = captureMarkingPost();
    const user = userEvent.setup();

    renderWithProviders(<MarkingPage />, { initialEntries: ['/marking/42'] });
    const typeSelect = await screen.findByLabelText(/이 영상의 검증 이벤트 유형을 골라 주세요/);

    await user.selectOptions(typeSelect, 'fall');
    await user.click(screen.getByRole('button', { name: '자동' }));
    await user.click(screen.getByRole('button', { name: /마킹 완료/ }));

    await waitFor(() => expect(post.calls()).toBe(1));
    expect(post.body().vrfcEvntTypeCd).toBe('fall');
    // 자동 모드에서도 그 유형의 첫 번째 질문이 함께 실린다(확정 사양).
    expect(post.body().vrfcEvntQstnSn).toBe(21);
  });

  it('질문이_0건인_유형을_골라도_화면이_깨지지_않고_유형은_그대로_실린다', async () => {
    // BE 계약: 등록된 질문이 0건인 유형도 <b>목록에서 빠지지 않고</b> questions 만 빈 배열이다.
    //   그 상태는 오류가 아니라 정상이므로 ①유형은 고를 수 있고 ②질문 영역만 뜨지 않으며
    //   ③마킹은 그대로 저장된다(질문 칸이 비는 것뿐이다).
    mockVideoDetail({ selectableVrfcEvntTypes: SELECTABLE_TYPES_WITH_EMPTY });
    const post = captureMarkingPost();
    const user = userEvent.setup();

    renderWithProviders(<MarkingPage />, { initialEntries: ['/marking/42'] });
    const typeSelect = await screen.findByLabelText(/이 영상의 검증 이벤트 유형을 골라 주세요/);

    // 질문 있는 유형을 먼저 골라 질문 영역이 실제로 <b>열렸다가</b> 닫히는 것을 본다 —
    //   처음부터 0건 유형만 고르면 「원래 안 뜨는 화면」과 구분되지 않아 가드가 공허해진다.
    await user.selectOptions(typeSelect, 'fire');
    expect(
      ((await screen.findByLabelText(/마킹과 함께 저장할 질문/)) as HTMLSelectElement).value,
    ).toBe('11');

    await user.selectOptions(typeSelect, 'smoke');

    // 질문 영역은 닫히고, 옛 유형의 질문이 남지 않는다(남으면 다른 유형의 질문이 실린다).
    await waitFor(() => expect(screen.queryByTestId('verification-question-section')).toBeNull());
    expect(screen.queryByText(FIRE_QUESTIONS[0].qstnCn)).toBeNull();
    // 유형 선택 자체는 살아 있고 고른 값이 화면에 남는다.
    expect((typeSelect as HTMLSelectElement).value).toBe('smoke');

    await user.click(screen.getByRole('button', { name: '자동' }));
    await user.click(screen.getByRole('button', { name: /마킹 완료/ }));

    // 유형은 실리고 질문 필드는 <b>만들지 않는다</b> — null 을 실으면 서버가 「고르지 않음」과
    //   「없는 질문을 골랐다」를 구분하지 못한다.
    await waitFor(() => expect(post.calls()).toBe(1));
    expect(post.body().vrfcEvntTypeCd).toBe('smoke');
    expect(post.body()).not.toHaveProperty('vrfcEvntQstnSn');
  });

  it('서버가_questions_키를_아예_안_보내도_유형_선택은_동작한다', async () => {
    // questions 는 서버가 채우지만 타입은 optional 로 남겼다 — 구 BE 노드와 새 FE 가 함께 뜨는
    //   창(Active-Active 롤링 배포)이 존재하기 때문이다. 그 창에서도 유형 선택은 살아 있어야 한다.
    mockVideoDetail({
      selectableVrfcEvntTypes: [{ vrfcEvntTypeCd: 'fire', vrfcEvntTypeNm: '화재' }],
    });
    const post = captureMarkingPost();
    const user = userEvent.setup();

    renderWithProviders(<MarkingPage />, { initialEntries: ['/marking/42'] });
    const typeSelect = await screen.findByLabelText(/이 영상의 검증 이벤트 유형을 골라 주세요/);

    await user.selectOptions(typeSelect, 'fire');
    expect(screen.queryByTestId('verification-question-section')).toBeNull();

    await user.click(screen.getByRole('button', { name: '자동' }));
    await user.click(screen.getByRole('button', { name: /마킹 완료/ }));

    await waitFor(() => expect(post.calls()).toBe(1));
    expect(post.body().vrfcEvntTypeCd).toBe('fire');
    expect(post.body()).not.toHaveProperty('vrfcEvntQstnSn');
  });

  it('관제_값이_있는_영상에는_유형_필드를_만들지_않는다', async () => {
    // 서버가 관제 인입을 1순위로 쓰므로 실어 봐야 무시되고, 두 조달처가 맞붙는 것처럼 보인다.
    mockVideoDetail({
      vrfcEvntTypeCd: 'fire',
      vrfcEvntQuestions: FIRE_QUESTIONS,
      selectableVrfcEvntTypes: [],
    });
    const post = captureMarkingPost();
    const user = userEvent.setup();

    renderWithProviders(<MarkingPage />, { initialEntries: ['/marking/42'] });
    await screen.findByTestId('verification-question-section');

    await user.click(screen.getByRole('button', { name: '자동' }));
    await user.click(screen.getByRole('button', { name: /마킹 완료/ }));

    await waitFor(() => expect(post.calls()).toBe(1));
    expect(post.body()).not.toHaveProperty('vrfcEvntTypeCd');
    expect(post.body().vrfcEvntQstnSn).toBe(11);
  });

  it('고른_유형이_목록에서_사라지면_선택이_버려져_요청에_실리지_않는다', async () => {
    // 영상 상세는 배치 진행 중 폴링으로 다시 내려온다. 그 사이 운영자가 유형을 정리하면 화면이
    // 붙들고 있던 코드가 <b>더 이상 고를 수 없는 값</b>이 된다 — 그대로 실으면 화면에 보이지도
    // 않는 유형이 저장되고 외부 위탁까지 나가며, 작업자에게는 되돌릴 수단이 없다.
    mockVideoDetail({ selectableVrfcEvntTypes: SELECTABLE_TYPES });
    let post = captureMarkingPost();
    const user = userEvent.setup();

    const { queryClient } = renderWithProviders(<MarkingPage />, {
      initialEntries: ['/marking/42'],
    });
    const typeSelect = await screen.findByLabelText(/이 영상의 검증 이벤트 유형을 골라 주세요/);
    await user.selectOptions(typeSelect, 'fall');
    await waitFor(() => expect((typeSelect as HTMLSelectElement).value).toBe('fall'));

    // when: 재조회 결과에서 그 유형이 빠진다(★목록은 여전히 비어 있지 않다 — 노출 판정은 참을 유지).
    mock.resetHandlers();
    mock.onGet(/\/videos\/42\/stream-url/).reply(200, {
      url: '/api/v1/videos/42/stream?exp=9999999999&sig=s',
      expiresAt: 9999999999,
      ttlSeconds: 60,
    });
    mock.onGet(/\/videos\/42\/markings/).reply(200, []);
    mockVideoDetail({ selectableVrfcEvntTypes: [SELECTABLE_TYPES[0]] });
    post = captureMarkingPost();
    await act(async () => {
      await queryClient.refetchQueries();
    });

    // then: 선택이 미선택으로 되돌아가고 유형 선택은 그대로 노출된다.
    await waitFor(() =>
      expect(
        (screen.getByLabelText(/이 영상의 검증 이벤트 유형을 골라 주세요/) as HTMLSelectElement)
          .value,
      ).toBe(''),
    );

    // and: 그 상태로 제출하면 필수 가드에 걸려 요청이 나가지 않는다.
    await user.click(screen.getByRole('button', { name: '자동' }));
    await user.click(screen.getByRole('button', { name: /마킹 완료/ }));
    await waitFor(() =>
      expect(
        useUiStore.getState().toasts.some((t) => t.message.includes('검증 이벤트 유형')),
      ).toBe(true),
    );
    expect(post.calls()).toBe(0);
  });

  it('관리_화면_전용_경로를_호출하지_않는다', async () => {
    // 작업자에게 403 인 경로다 — 유형·질문 목록은 영상 단건 응답에서만 온다.
    mockVideoDetail({ selectableVrfcEvntTypes: SELECTABLE_TYPES });
    const user = userEvent.setup();

    renderWithProviders(<MarkingPage />, { initialEntries: ['/marking/42'] });
    const typeSelect = await screen.findByLabelText(/이 영상의 검증 이벤트 유형을 골라 주세요/);
    await user.selectOptions(typeSelect, 'fire');
    await screen.findByLabelText(/마킹과 함께 저장할 질문/);

    expect(mock.history.get.some((r) => (r.url ?? '').includes('/manage/'))).toBe(false);
  });

  it('질문_선택은_그_문구가_외부로_나간다는_사실을_알린다', async () => {
    // 종전에는 기록용이라 안내가 없었다. 창구가 바뀌어 고른 문구가 위탁 본문에 그대로 실려 나간다.
    mockVideoDetail({
      vrfcEvntTypeCd: 'fire',
      vrfcEvntQuestions: FIRE_QUESTIONS,
      selectableVrfcEvntTypes: [],
    });

    renderWithProviders(<MarkingPage />, { initialEntries: ['/marking/42'] });
    const section = await screen.findByTestId('verification-question-section');

    expect(section).toHaveTextContent('외부 시계열 위탁 요청에 그대로 실려 나갑니다');
  });
});
