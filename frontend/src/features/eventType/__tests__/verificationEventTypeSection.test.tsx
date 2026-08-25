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
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { VerificationEventTypeSection } from '@/features/eventType/components/VerificationEventTypeSection';
import { ApiError } from '@/lib/api/errors';
import { useUiStore } from '@/stores/useUiStore';

import * as verificationApi from '../verificationApi';

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

    await screen.findByTestId('vrfc-question-editor');
    expect(draftValues()).toEqual(['첫 번째 질문', '두 번째 질문']);
  });

  it('기본_질문_표기가_첫_줄에만_붙고_순서를_바꾸면_따라_옮겨간다', async () => {
    const user = userEvent.setup();
    renderSection();
    await screen.findByTestId('vrfc-question-editor');

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
    await screen.findByTestId('vrfc-question-editor');

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
    await screen.findByTestId('vrfc-question-editor');

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
    await screen.findByTestId('vrfc-question-editor');

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
    await screen.findByTestId('vrfc-question-editor');

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
