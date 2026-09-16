// 검증 이벤트 유형·질문 관리 절 — 회귀 가드. [@design SCREEN-038] [@design API-219] [@design API-220]
//
// 고정하는 계약:
//  ① 유형이 응답 순서(정렬순서 오름차순) 그대로 보이고, 질문도 그 순서로 보인다 — FE 가 재정렬하지 않는다.
//  ② 첫 번째 질문에만 「기본 질문」 표기가 붙고, 순서를 바꾸면 표기가 따라 옮겨간다.
//  ③ 관제 이벤트유형 짝이 보이고, 짝이 없으면 '짝 없음'으로 보인다(빈 상태가 정상).
//  ④ 추가·수정·삭제·순서 변경이 <b>저장 한 번</b>으로 반영되고, 요청 본문은 화면 순서 그대로의
//     문구 배열이다(일련번호·정렬순서를 되돌려 보내지 않는다 — Mass Assignment 방어).
//  ⑤ 서버가 요청 전체를 거부하면 사유를 보여주고 <b>편집 내용을 지우지 않는다</b>.
//
// ⚠ mutation 확인 절차: ②는 `i === 0` 조건을 지우면, ④는 `drafts.map` 을 원본 questions 로 되돌리면,
//   ⑤는 catch 의 setSaveError 를 지우면 각각 FAIL 해야 한다.

import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { VerificationEventTypeSection } from '@/features/eventType/components/VerificationEventTypeSection';
import { ApiError } from '@/lib/api/errors';
import { useUiStore } from '@/stores/useUiStore';

import * as verificationApi from '../verificationApi';
import { QSTN_CN_MAX_LENGTH } from '../verificationQuestionRules';

vi.mock('../verificationApi');

const TYPES: verificationApi.VerificationEventType[] = [
  {
    vrfcEvntTypeCd: 'fire',
    vrfcEvntTypeNm: '화재',
    vrfcEvntTypeExpln: '불꽃 등 화재 상황',
    sortSeq: 1,
    // 한 검증 유형에 관제 코드가 여러 개 붙을 수 있다.
    evntTypeCds: ['EV01000101', 'EV01000102'],
    questions: [
      { vrfcEvntQstnSn: 11, sortSeq: 1, qstnCn: '첫 번째 질문' },
      { vrfcEvntQstnSn: 12, sortSeq: 2, qstnCn: '두 번째 질문' },
    ],
  },
  {
    vrfcEvntTypeCd: 'smoke',
    vrfcEvntTypeNm: '연기',
    vrfcEvntTypeExpln: null,
    sortSeq: 2,
    // 짝이 하나도 없는 것이 정상 상태다.
    evntTypeCds: [],
    questions: [],
  },
];

function renderSection() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <VerificationEventTypeSection />
    </QueryClientProvider>,
  );
}

/** 질문 편집 영역 안의 문구 입력칸 값들을 화면 순서대로 뽑는다. */
function draftValues(): string[] {
  const editor = screen.getByTestId('vrfc-question-editor');
  return within(editor)
    .getAllByRole('textbox')
    .map((el) => (el as HTMLTextAreaElement).value);
}

/**
 * 편집 영역(`vrfc-question-editor`)이 뜬 뒤, 선택된 유형의 저장된 질문 목록이 실제로
 * 드래프트에 반영될 때까지 기다린다.
 *
 * ⚠ `vrfc-question-editor` 자체는 `selectedCode` 가 정해지는 첫 effect 만으로도 나타나고,
 * 그 안의 문구는 `drafts` 를 채우는 두 번째 effect(선택된 유형의 questions 를 보고 초기화)로
 * 한 박자 늦게 채워진다. 단독 실행처럼 여유 있는 환경에서는 두 커밋이 같은 act() 플러시
 * 안에서 끝나 안 드러나지만, full-suite 처럼 스레드 경합이 심한 환경에서는 `findByTestId` 가
 * 드래프트 채움 전 커밋에서 먼저 resolve 될 수 있다 — 그 레이스를 여기서 흡수한다.
 */
async function waitForQuestionsLoaded(expected: string[]): Promise<void> {
  await screen.findByTestId('vrfc-question-editor');
  await waitFor(() => expect(draftValues()).toEqual(expected));
}

describe('검증 이벤트 유형·질문 관리', () => {
  beforeEach(() => {
    useUiStore.setState({ toasts: [] });
    vi.mocked(verificationApi.getVerificationEventTypes).mockResolvedValue(
      // 깊은 복사 — 테스트끼리 픽스처를 공유하며 오염되지 않게.
      JSON.parse(JSON.stringify(TYPES)) as verificationApi.VerificationEventType[],
    );
    vi.mocked(verificationApi.replaceVerificationEventQuestions).mockImplementation(
      async (code, questions) => ({
        vrfcEvntTypeCd: code,
        questions: questions.map((q, i) => ({
          vrfcEvntQstnSn: 100 + i,
          sortSeq: i + 1,
          qstnCn: q.qstnCn,
        })),
      }),
    );
  });

  it('유형이_응답_순서대로_보이고_질문_수와_관제_짝이_함께_보인다', async () => {
    renderSection();

    const fire = await screen.findByTestId('vrfc-event-type-row-fire');
    expect(within(fire).getByText('화재')).toBeInTheDocument();
    expect(within(fire).getByText('불꽃 등 화재 상황')).toBeInTheDocument();
    // 관제 짝 — 인입 원장에서 읽은 값 그대로 나열한다(화면이 매핑을 만들지 않는다).
    expect(within(fire).getByText('EV01000101, EV01000102')).toBeInTheDocument();

    const smoke = screen.getByTestId('vrfc-event-type-row-smoke');
    // 짝이 없으면 '-' 가 아니라 '짝 없음' — '-' 면 "값을 못 받았다"와 구분되지 않는다.
    expect(within(smoke).getByText('짝 없음')).toBeInTheDocument();

    // 응답 순서 그대로(정렬순서 오름차순) — FE 가 재정렬하지 않는다.
    //   화면이 다시 정렬하면 화면이 보여준 「기본(첫 번째)」과 서버가 교정에 쓰는 「첫 번째」가
    //   조용히 어긋난다.
    const order = screen
      .getAllByRole('row')
      .map((r) => r.getAttribute('data-testid'))
      .filter((id): id is string => id !== null);
    expect(order).toEqual(['vrfc-event-type-row-fire', 'vrfc-event-type-row-smoke']);

    // 질문 수는 그 유형의 질문 목록 길이 그대로다.
    expect(within(fire).getByText('2')).toBeInTheDocument();
    expect(within(smoke).getByText('0')).toBeInTheDocument();
  });

  it('첫_유형이_자동으로_펼쳐지고_질문이_정렬순서대로_보인다', async () => {
    renderSection();

    await waitForQuestionsLoaded(['첫 번째 질문', '두 번째 질문']);
  });

  it('기본_질문_표기가_첫_줄에만_붙고_순서를_바꾸면_따라_옮겨간다', async () => {
    const user = userEvent.setup();
    renderSection();
    await waitForQuestionsLoaded(['첫 번째 질문', '두 번째 질문']);

    // given: 표기는 한 개뿐이고 첫 줄에 붙어 있다.
    expect(screen.getAllByText('기본 질문')).toHaveLength(1);

    // when: 두 번째 줄을 위로 올린다
    await user.click(screen.getByRole('button', { name: '2번째 질문 순서 올리기' }));

    // then: 순서가 뒤바뀌고 표기는 여전히 한 개이며 첫 줄(=새 기본 질문)에 붙는다.
    expect(draftValues()).toEqual(['두 번째 질문', '첫 번째 질문']);
    expect(screen.getAllByText('기본 질문')).toHaveLength(1);
  });

  it('추가_수정_삭제_정렬이_저장_한_번으로_화면_순서_그대로_전송된다', async () => {
    const user = userEvent.setup();
    renderSection();
    await waitForQuestionsLoaded(['첫 번째 질문', '두 번째 질문']);

    // when: ① 첫 줄 수정 ② 줄 추가 ③ 두 번째 줄 삭제 ④ 순서 변경
    const first = within(screen.getByTestId('vrfc-question-editor')).getAllByRole('textbox')[0];
    await user.clear(first);
    await user.type(first, '고친 질문');
    await user.click(screen.getByRole('button', { name: '질문 추가' }));
    await user.type(
      within(screen.getByTestId('vrfc-question-editor')).getAllByRole('textbox')[2],
      '새 질문',
    );
    await user.click(screen.getByRole('button', { name: '2번째 질문 삭제' }));
    await user.click(screen.getByRole('button', { name: '2번째 질문 순서 올리기' }));

    expect(draftValues()).toEqual(['새 질문', '고친 질문']);

    // and: 저장
    await user.click(screen.getByRole('button', { name: '질문 목록 저장' }));

    // then: 요청은 <b>한 번</b>이고 본문은 화면 순서 그대로의 문구 배열이다.
    await waitFor(() =>
      expect(verificationApi.replaceVerificationEventQuestions).toHaveBeenCalledTimes(1),
    );
    expect(verificationApi.replaceVerificationEventQuestions).toHaveBeenCalledWith('fire', [
      { qstnCn: '새 질문' },
      { qstnCn: '고친 질문' },
    ]);
  });

  it('질문을_전부_지우고_저장하면_빈_배열이_전송된다', async () => {
    const user = userEvent.setup();
    renderSection();
    await waitForQuestionsLoaded(['첫 번째 질문', '두 번째 질문']);

    // 삭제하면 아래 줄이 1번째가 되므로 같은 버튼을 두 번 누른다.
    await user.click(screen.getByRole('button', { name: '1번째 질문 삭제' }));
    await user.click(screen.getByRole('button', { name: '1번째 질문 삭제' }));
    await user.click(screen.getByRole('button', { name: '질문 목록 저장' }));

    await waitFor(() =>
      expect(verificationApi.replaceVerificationEventQuestions).toHaveBeenCalledWith('fire', []),
    );
  });

  it('서버가_요청_전체를_거부하면_사유를_보여주고_편집_내용을_지우지_않는다', async () => {
    // given: 서버가 개행·제어문자 등을 이유로 400 을 낸다.
    // ⚠ 평범한 객체로 거부하면 resolveApiMessage 가 fallback 으로 접는다 — 400 ApiError 여야
    //   서버 안내문이 그대로 화면에 나온다(그 계약을 고정하는 것이 이 케이스의 목적이다).
    vi.mocked(verificationApi.replaceVerificationEventQuestions).mockRejectedValue(
      ApiError.fromStatus(400, '질문 문구에는 개행·제어문자를 넣을 수 없습니다.'),
    );
    const user = userEvent.setup();
    renderSection();
    await waitForQuestionsLoaded(['첫 번째 질문', '두 번째 질문']);

    const first = within(screen.getByTestId('vrfc-question-editor')).getAllByRole('textbox')[0];
    await user.clear(first);
    await user.type(first, '거부될 질문');
    await user.click(screen.getByRole('button', { name: '질문 목록 저장' }));

    // then: 사유가 뜨고 편집 내용은 그대로 남는다(요청 전체가 거부됐으므로 되돌릴 것이 없다).
    expect(
      await screen.findByText('질문 문구에는 개행·제어문자를 넣을 수 없습니다.'),
    ).toBeInTheDocument();
    expect(draftValues()[0]).toBe('거부될 질문');
  });

  it('취소를_누르면_마지막으로_조회한_목록으로_되돌아간다', async () => {
    const user = userEvent.setup();
    renderSection();
    await waitForQuestionsLoaded(['첫 번째 질문', '두 번째 질문']);

    await user.click(screen.getByRole('button', { name: '1번째 질문 삭제' }));
    expect(draftValues()).toEqual(['두 번째 질문']);

    await user.click(screen.getByRole('button', { name: '취소' }));
    expect(draftValues()).toEqual(['첫 번째 질문', '두 번째 질문']);
  });

  it('다른_유형을_고르면_그_유형의_질문_목록으로_바뀐다', async () => {
    const user = userEvent.setup();
    renderSection();
    await screen.findByTestId('vrfc-question-editor');

    await user.click(screen.getByRole('button', { name: 'smoke' }));

    // 질문이 0건인 유형 — 빈 상태 안내가 뜬다(어노테이션 질문 칸이 빈다는 사실을 알린다).
    await waitFor(() => expect(screen.getByText('등록된 질문이 없습니다')).toBeInTheDocument());
  });
});

/**
 * CO-20260916 — 입력 값 없이 저장하면 <b>서버 에러 내용이 그대로 보이던</b> 발주처 오류 증적.
 *
 * 화면에 실제로 뜬 문자열:
 * <pre>questions[16].qstnCn: 질문 문구는 비어 있을 수 없습니다., questions[18].qstnCn: …</pre>
 *
 * <h3>두 축을 갈라서 결박한다</h3>
 * <ul>
 *   <li><b>선판정</b>(AC-1129) — 창구를 부르기 전에 화면이 막고 사유를 <b>그 줄 옆</b>에 보인다.</li>
 *   <li><b>거부 표시</b>(AC-1130) — 그래도 서버가 거절하면 그 <b>응답 문구·필드 경로·배열 순번</b>을
 *       그대로 내보내지 않는다.</li>
 * </ul>
 * ★뒤엣것이 이 증적의 본체다. 앞엣것만 결박하면 서버가 막아 준 경우에 <b>지금과 똑같은 화면</b>이
 * 남는데도 통과한다.
 *
 * @design SCREEN-038, API-220, UC-044, AC-1129, AC-1130
 */
describe('질문 문구 입력 판정과 거부 표시 (CO-20260916)', () => {
  beforeEach(() => {
    // ⚠ 호출 이력을 지운다 — 이 파일의 모의는 모듈 단위라 <b>describe 를 건너 누적</b>된다.
    //   지우지 않으면 「요청이 나가지 않는다」 단언이 앞 블록의 저장 3건을 보고 실패한다(실측).
    //   단언을 느슨하게 고치는 쪽으로 가면 그 가드가 지키려던 것이 통째로 사라진다.
    vi.clearAllMocks();
    useUiStore.setState({ toasts: [] });
    vi.mocked(verificationApi.getVerificationEventTypes).mockResolvedValue(
      JSON.parse(JSON.stringify(TYPES)) as verificationApi.VerificationEventType[],
    );
    vi.mocked(verificationApi.replaceVerificationEventQuestions).mockImplementation(
      async (code, questions) => ({
        vrfcEvntTypeCd: code,
        questions: questions.map((q, i) => ({
          vrfcEvntQstnSn: 100 + i,
          sortSeq: i + 1,
          qstnCn: q.qstnCn,
        })),
      }),
    );
  });

  const boxAt = (i: number) =>
    within(screen.getByTestId('vrfc-question-editor')).getAllByRole('textbox')[i] as HTMLTextAreaElement;
  const saveButton = () => screen.getByRole('button', { name: '질문 목록 저장' }) as HTMLButtonElement;

  /**
   * 줄 값을 통째로 바꾼다.
   *
   * ⚠ 개행·제어문자를 `user.type` 으로 넣지 않는다 — 그 경로는 키 입력을 흉내 내느라 원하는
   *   문자가 그대로 들어간다는 보장이 없고, 4000자 입력은 한 글자씩 치느라 느리다. 여기서 보려는
   *   것은 「그 값이 들어왔을 때의 판정」이므로 값을 직접 넣는다.
   *   (`textarea` 는 `input[type=text]` 과 달리 개행을 보존하므로 이 재현이 성립한다.)
   */
  const setRow = (i: number, value: string) => {
    fireEvent.change(boxAt(i), { target: { value } });
  };

  // ── AC-1129 — 저장 전에 화면이 먼저 판정한다 ────────────────────────────

  it('★빈_질문은_저장_전에_그_줄_옆에서_막히고_요청이_나가지_않는다', async () => {
    const user = userEvent.setup();
    renderSection();
    await waitForQuestionsLoaded(['첫 번째 질문', '두 번째 질문']);

    setRow(0, '');

    // 사유가 그 줄 옆에 붙는다
    expect(screen.getByTestId('vrfc-question-error-0')).toHaveTextContent('질문 문구를 입력하세요.');
    // 보조기술에도 닿는다 — 빨간 테두리만으로는 왜 막혔는지 알 수 없다.
    expect(boxAt(0)).toHaveAttribute('aria-invalid', 'true');
    // 저장 수단이 잠기고
    expect(saveButton().disabled).toBe(true);

    // ★요청 자체가 나가지 않는다(전체 교체라 부분 반영이 없다)
    await user.click(saveButton());
    expect(verificationApi.replaceVerificationEventQuestions).not.toHaveBeenCalled();
  });

  it('★공백만_남겨도_막힌다', async () => {
    renderSection();
    await waitForQuestionsLoaded(['첫 번째 질문', '두 번째 질문']);

    setRow(0, '   ');

    expect(screen.getByTestId('vrfc-question-error-0')).toBeInTheDocument();
    expect(saveButton().disabled).toBe(true);
  });

  it('★개행이_섞이면_막힌다', async () => {
    renderSection();
    await waitForQuestionsLoaded(['첫 번째 질문', '두 번째 질문']);

    setRow(0, '앞줄\n뒷줄');

    expect(screen.getByTestId('vrfc-question-error-0')).toHaveTextContent(
      '줄바꿈과 특수 제어문자는 넣을 수 없습니다. 한 줄로 입력하세요.',
    );
    expect(saveButton().disabled).toBe(true);
  });

  it('★길이_상한을_넘으면_막힌다', async () => {
    renderSection();
    await waitForQuestionsLoaded(['첫 번째 질문', '두 번째 질문']);

    setRow(0, '가'.repeat(QSTN_CN_MAX_LENGTH + 1));

    expect(screen.getByTestId('vrfc-question-error-0')).toHaveTextContent(
      `${QSTN_CN_MAX_LENGTH}자 이하여야 합니다`,
    );
    expect(saveButton().disabled).toBe(true);
  });

  it('★★여러_줄이_어긋나면_안내가_줄마다_따로_붙고_배너_한_줄로_합쳐지지_않는다', async () => {
    // 이 케이스가 AC-1129 의 본체다 — 사유를 한 줄에 이어 붙이면 사용자는 <b>어느 질문의 무엇을</b>
    // 고쳐야 하는지 알 수 없다(증적이 정확히 그 화면이었다).
    renderSection();
    await waitForQuestionsLoaded(['첫 번째 질문', '두 번째 질문']);

    setRow(0, '');
    setRow(1, '앞줄\n뒷줄');

    // 두 줄에 각각 붙는다
    expect(screen.getByTestId('vrfc-question-error-0')).toHaveTextContent('질문 문구를 입력하세요.');
    expect(screen.getByTestId('vrfc-question-error-1')).toHaveTextContent('줄바꿈과 특수 제어문자');
    // 그리고 한 줄짜리 배너로 합쳐지지 않는다
    expect(screen.queryByTestId('vrfc-question-save-error')).toBeNull();
  });

  it('★고치면_그_줄의_안내가_사라지고_다시_저장할_수_있다', async () => {
    // 막힘이 영구 상태로 남지 않는다.
    const user = userEvent.setup();
    renderSection();
    await waitForQuestionsLoaded(['첫 번째 질문', '두 번째 질문']);

    setRow(0, '');
    expect(saveButton().disabled).toBe(true);

    setRow(0, '고친 질문');

    expect(screen.queryByTestId('vrfc-question-error-0')).toBeNull();
    expect(boxAt(0)).not.toHaveAttribute('aria-invalid');
    expect(saveButton().disabled).toBe(false);

    await user.click(saveButton());
    await waitFor(() =>
      expect(verificationApi.replaceVerificationEventQuestions).toHaveBeenCalledWith('fire', [
        { qstnCn: '고친 질문' },
        { qstnCn: '두 번째 질문' },
      ]),
    );
  });

  // ── AC-1130 — 서버가 거절해도 그 문구를 그대로 내보내지 않는다 ──────────────

  it('★★서버가_필드_경로와_배열_순번으로_거절해도_그_문구가_화면에_나오지_않는다', async () => {
    // given: 서버 `GlobalExceptionHandler` 가 만드는 진단 문자열 — 증적에 뜬 바로 그 모양이다.
    //   ⚠ 화면 선판정을 통과한 값으로 저장해야 서버까지 닿는다. 그래야 「서버가 막아 준 경우」의
    //     화면을 보는 것이 된다.
    const 진단문자열 =
      'questions[0].qstnCn: 질문 문구는 비어 있을 수 없습니다., ' +
      'questions[1].qstnCn: 질문 문구에는 개행·제어문자를 넣을 수 없습니다.';
    vi.mocked(verificationApi.replaceVerificationEventQuestions).mockRejectedValue(
      ApiError.fromStatus(400, 진단문자열),
    );
    const user = userEvent.setup();
    renderSection();
    await waitForQuestionsLoaded(['첫 번째 질문', '두 번째 질문']);

    await user.click(saveButton());

    const banner = await screen.findByTestId('vrfc-question-save-error');

    // ① ★서버 응답 문구·필드 경로·배열 순번이 화면 어디에도 나타나지 않는다.
    const editor = screen.getByTestId('vrfc-question-editor');
    expect(editor.textContent).not.toContain('questions[');
    expect(editor.textContent).not.toContain('qstnCn');
    expect(editor.textContent).not.toContain('질문 문구는 비어 있을 수 없습니다.');
    expect(document.body.textContent).not.toContain('questions[0]');

    // ② 대신 사람이 읽는 위치 표기로 바뀐다(0부터 세는 순번이 아니라 1부터).
    expect(banner).toHaveTextContent('1번째, 2번째 질문의 문구를 고쳐 주세요.');

    // ③ 해당 줄이 짚인다
    expect(screen.getByTestId('vrfc-question-error-0')).toBeInTheDocument();
    expect(screen.getByTestId('vrfc-question-error-1')).toBeInTheDocument();

    // ④ 알림에도 서버 원문이 실리지 않는다 — 배너만 막고 토스트로 새면 같은 화면이 된다.
    const toasts = useUiStore.getState().toasts;
    expect(toasts).toHaveLength(1);
    expect(toasts[0]!.message).not.toContain('questions[');
    expect(toasts[0]!.message).toBe('1번째, 2번째 질문의 문구를 고쳐 주세요.');

    // ⑤ 요청 전체가 거부됐으므로 편집 내용을 지우지 않는다
    expect(draftValues()).toEqual(['첫 번째 질문', '두 번째 질문']);
  });

  it('★목록_자체가_거부돼_짚을_줄이_없으면_필드_이름을_내보내지_않고_일반_안내로_내린다', async () => {
    // `@NotNull` 위반은 색인이 없는 `questions: …` 꼴이다. 이것을 「사람이 읽는 안내」로 흘려보내면
    // 필드 이름이 그대로 화면에 뜬다.
    vi.mocked(verificationApi.replaceVerificationEventQuestions).mockRejectedValue(
      ApiError.fromStatus(400, 'questions: 질문 목록은 필수입니다.'),
    );
    const user = userEvent.setup();
    renderSection();
    await waitForQuestionsLoaded(['첫 번째 질문', '두 번째 질문']);

    await user.click(saveButton());

    const banner = await screen.findByTestId('vrfc-question-save-error');
    expect(banner).toHaveTextContent('저장에 실패했습니다.');
    expect(document.body.textContent).not.toContain('questions:');
    expect(document.body.textContent).not.toContain('질문 목록은 필수입니다.');
  });

  it('★사람이_읽는_단일_안내는_그대로_보여준다_업무_안내를_함께_삼키지_않는다', async () => {
    // ★갈라내는 쪽의 짝 — 「서버 문구를 감춘다」를 과하게 적용하면 사유가 통째로 사라져, 사용자는
    //   왜 막혔는지 알 수 없게 된다. 서비스 2차 방어선이 주는 이 문장은 이미 사람이 읽는 위치
    //   표기(3번째)를 담고 있어 그대로 보여주는 것이 맞다.
    const 안내 =
      '3번째 질문 문구가 올바르지 않습니다. 빈 값·개행·제어문자를 넣을 수 없고 4000자 이하여야 합니다.';
    vi.mocked(verificationApi.replaceVerificationEventQuestions).mockRejectedValue(
      ApiError.fromStatus(400, 안내),
    );
    const user = userEvent.setup();
    renderSection();
    await waitForQuestionsLoaded(['첫 번째 질문', '두 번째 질문']);

    await user.click(saveButton());

    expect(await screen.findByTestId('vrfc-question-save-error')).toHaveTextContent(안내);
  });
});
