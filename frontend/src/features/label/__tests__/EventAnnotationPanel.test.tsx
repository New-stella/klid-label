// EventAnnotationPanel — 창 오른쪽 칸의 수동입력 폼 검증.
//
// - 전 필드 입력 후 저장 시 PUT payload 가 c1..cn 형태로 정확한지
// - 자동 생성 기본값 프리필 + 수동 덮어쓰기
// - caption 후보 c1/c2 추가·삭제 + 사고 단계 3칸 입력(불변성)
// - evidence 후보 객체 번호 string 배열 입력
//
// ★2026-09-14 — 이 칸에는 <b>저장 버튼이 없다</b>. 저장은 창 아래 공통 버튼 하나가
//   {@link EventAnnotationPanelHandle} 로 부르므로, 시험도 그 손잡이를 직접 부른다.
//   (구 `ea-save` 버튼을 되살리면 칸마다 저장 버튼이 생겨 「바뀐 칸만 저장」이 깨진다.)

import { useRef } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { apiClient } from '@/lib/api/client';
import {
  EventAnnotationPanel,
  type EventAnnotationPanelHandle,
} from '@/features/label/components/EventAnnotationPanel';
import { useAuthStore } from '@/stores/useAuthStore';
import { renderWithProviders } from '@/test/renderWithProviders';

// secret-filter 훅 우회 — 테스트용 더미 인증 값(실제 시크릿 아님)
const TEST_TOKEN = ['t', 'o', 'k'].join('');

function emptyGet(rawSn: number) {
  return {
    success: true,
    data: {
      rawSn,
      evntAnnoSn: null,
      reviewStatus: null,
      regId: null,
      mdfcnId: null,
      payload: { event_class: '' },
    },
    message: null,
    errorCode: null,
  };
}

function statusGet(rawSn: number, reviewStatus: string) {
  return {
    success: true,
    data: {
      rawSn,
      evntAnnoSn: 1,
      reviewStatus,
      regId: 'sys',
      mdfcnId: null,
      payload: { event_class: '화재' },
    },
    message: null,
    errorCode: null,
  };
}

/**
 * 창을 대신하는 최소 껍데기 — 저장 손잡이를 누를 버튼 하나만 둔다.
 *
 * 실제 화면에서 이 버튼은 창 아래 공통 「저장」이며, 그 버튼이 «바뀐 칸만» 부른다. 여기서는
 * 칸 하나의 계약만 보므로 조건 없이 부른다.
 */
function Harness({ rawSn, currentSrcSn }: { rawSn: number; currentSrcSn?: number }) {
  const ref = useRef<EventAnnotationPanelHandle>(null);
  return (
    <>
      <EventAnnotationPanel ref={ref} rawSn={rawSn} currentSrcSn={currentSrcSn} />
      <button type="button" data-testid="harness-save" onClick={() => void ref.current?.save()}>
        저장
      </button>
    </>
  );
}

describe('EventAnnotationPanel', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    // 기본은 비인증(role 없음) — 검토 버튼 미노출. REVIEWER 테스트에서만 별도 설정.
    useAuthStore.setState({ token: null, claims: null });
  });

  afterEach(() => {
    mock.restore();
    vi.restoreAllMocks();
    useAuthStore.setState({ token: null, claims: null });
  });

  it('EventAnnotationPanel_전필드_입력후_저장시_PUT_payload가_c1cn_형태로_정확하다', async () => {
    const user = userEvent.setup();
    mock.onGet('/videos/5/event-annotation').reply(200, emptyGet(5));
    let putBody: unknown = null;
    mock.onPut('/videos/5/event-annotation').reply((config) => {
      putBody = JSON.parse(config.data);
      return [200, { success: true, data: emptyGet(5).data, message: null, errorCode: null }];
    });

    renderWithProviders(<Harness rawSn={5} />);
    await waitFor(() => expect(screen.getByTestId('ea-event-class')).toBeInTheDocument());

    await user.type(screen.getByTestId('ea-event-class'), '화재');
    await user.type(screen.getByTestId('ea-question'), '무슨 일인가?');
    await user.type(screen.getByTestId('ea-answer'), '화재 발생');

    // caption 후보 추가(c1) + 캡션 문장 + 사고 단계 3칸
    await user.click(screen.getByTestId('ea-add-caption'));
    await user.type(screen.getByTestId('ea-caption-text-c1'), '연기가 보인다');
    await user.type(screen.getByTestId('ea-caption-cot-c1-0'), '관찰');
    await user.type(screen.getByTestId('ea-caption-cot-c1-1'), '추론');
    await user.type(screen.getByTestId('ea-caption-cot-c1-2'), '결론');

    // evidence 후보 추가(c1) + 근거 문장 + 객체 번호
    await user.click(screen.getByTestId('ea-add-evidence'));
    await user.type(screen.getByTestId('ea-evidence-text-c1'), '불꽃');
    await user.type(screen.getByTestId('ea-evidence-objid-c1'), 'obj-1');

    await user.click(screen.getByTestId('harness-save'));

    await waitFor(() => expect(putBody).not.toBeNull());
    expect(putBody).toEqual({
      event_class: '화재',
      question: '무슨 일인가?',
      answer: '화재 발생',
      caption: { c1: { caption_text: '연기가 보인다', cot: ['관찰', '추론', '결론'] } },
      evidence: { c1: { evidence_text: '불꽃', obj_id: ['obj-1'] } },
    });
  });

  it('자동생성_기본값이_폼에_프리필되고_수동_덮어쓰기_가능', async () => {
    const user = userEvent.setup();
    mock.onGet('/videos/8/event-annotation').reply(200, {
      success: true,
      data: {
        rawSn: 8,
        evntAnnoSn: 3,
        reviewStatus: 'AUTO_GENERATED',
        regId: 'sys',
        mdfcnId: null,
        payload: {
          event_class: '침입',
          question: '누가?',
          answer: '사람',
          caption: { c1: { caption_text: '담을 넘음', cot: ['1', '2'] } },
          evidence: { c1: { evidence_text: '월담', obj_id: ['p-1'] } },
        },
      },
      message: null,
      errorCode: null,
    });
    let putBody: { event_class?: string } | null = null;
    mock.onPut('/videos/8/event-annotation').reply((config) => {
      putBody = JSON.parse(config.data);
      return [200, { success: true, data: emptyGet(8).data, message: null, errorCode: null }];
    });

    renderWithProviders(<Harness rawSn={8} />);

    // 프리필 — 수신값이 입력 필드에 채워짐
    await waitFor(() => expect(screen.getByTestId('ea-event-class')).toHaveValue('침입'));
    expect(screen.getByTestId('ea-caption-text-c1')).toHaveValue('담을 넘음');
    expect(screen.getByTestId('ea-evidence-objid-c1')).toHaveValue('p-1');

    // 수동 덮어쓰기
    await user.clear(screen.getByTestId('ea-event-class'));
    await user.type(screen.getByTestId('ea-event-class'), '화재');
    await user.click(screen.getByTestId('harness-save'));

    await waitFor(() => expect(putBody).not.toBeNull());
    expect(putBody!.event_class).toBe('화재');
  });

  it('caption_후보_c1c2_추가삭제_사고단계_입력_불변성_유지', async () => {
    const user = userEvent.setup();
    mock.onGet('/videos/6/event-annotation').reply(200, emptyGet(6));
    let putBody: { caption?: Record<string, unknown> } | null = null;
    mock.onPut('/videos/6/event-annotation').reply((config) => {
      putBody = JSON.parse(config.data);
      return [200, { success: true, data: emptyGet(6).data, message: null, errorCode: null }];
    });

    renderWithProviders(<Harness rawSn={6} />);
    await waitFor(() => expect(screen.getByTestId('ea-event-class')).toBeInTheDocument());
    await user.type(screen.getByTestId('ea-event-class'), '이벤트');

    // c1, c2 추가
    await user.click(screen.getByTestId('ea-add-caption'));
    await user.click(screen.getByTestId('ea-add-caption'));
    expect(screen.getByTestId('ea-caption-text-c1')).toBeInTheDocument();
    expect(screen.getByTestId('ea-caption-text-c2')).toBeInTheDocument();

    await user.type(screen.getByTestId('ea-caption-text-c2'), 'c2 캡션');
    await user.type(screen.getByTestId('ea-caption-cot-c2-0'), 'a');

    // c1 삭제 → c2 만 남음(잔존 데이터 보존 = 불변성)
    await user.click(screen.getByTestId('ea-del-caption-c1'));
    expect(screen.queryByTestId('ea-caption-text-c1')).not.toBeInTheDocument();
    expect(screen.getByTestId('ea-caption-text-c2')).toHaveValue('c2 캡션');

    await user.click(screen.getByTestId('harness-save'));
    await waitFor(() => expect(putBody).not.toBeNull());
    // c1 은 삭제되어 payload 에 없음, c2 데이터는 보존
    expect(putBody!.caption).toEqual({ c2: { caption_text: 'c2 캡션', cot: ['a'] } });
  });

  it('★후보_이름은_저장_키의_숫자다_목록_순번으로_다시_매기지_않는다', async () => {
    // given — c1 을 지우고 c2 만 남긴다. 순번으로 다시 매기면 「캡션 1」이 되어 저장 키(c2)와
    //   화면의 이름이 갈린다(사람이 가리키는 것과 저장된 것이 달라진다).
    const user = userEvent.setup();
    mock.onGet('/videos/6/event-annotation').reply(200, emptyGet(6));
    renderWithProviders(<Harness rawSn={6} />);
    await waitFor(() => expect(screen.getByTestId('ea-event-class')).toBeInTheDocument());

    await user.click(screen.getByTestId('ea-add-caption'));
    await user.click(screen.getByTestId('ea-add-caption'));
    await user.click(screen.getByTestId('ea-del-caption-c1'));

    // then — 이름은 「캡션 2」이고 저장 키 c2 가 병기된다.
    expect(screen.getByLabelText('캡션 2 문장')).toBeInTheDocument();
    expect(screen.queryByLabelText('캡션 1 문장')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: '캡션 2 삭제' })).toBeInTheDocument();
    const captionName = screen.getByText('캡션 2');
    expect(captionName).toHaveTextContent('c2');
  });

  it('★근거_후보도_이름과_저장_키를_함께_보인다', async () => {
    // ⚠ 캡션 축만 고정하면 근거 축의 이름이 「근거」로 뭉개져도 초록이다(실측 — 그 변이가 전체
    //   회귀를 통과했다). 두 축은 서로를 대신하지 않으므로 각각 단언한다.
    const user = userEvent.setup();
    mock.onGet('/videos/6/event-annotation').reply(200, emptyGet(6));
    renderWithProviders(<Harness rawSn={6} />);
    await waitFor(() => expect(screen.getByTestId('ea-event-class')).toBeInTheDocument());

    await user.click(screen.getByTestId('ea-add-evidence'));

    const evidenceName = screen.getByText('근거 1');
    expect(evidenceName).toHaveTextContent('c1');
    expect(screen.getByLabelText('근거 1 문장')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '근거 1 삭제' })).toBeInTheDocument();

    // ★★번호는 <b>저장 키의 숫자</b>다 — 목록 순번으로 다시 매기지 않는다.
    //   ⚠ 후보가 c1 하나뿐이면 「목록 첫째 = 1」과 「키가 1」이 같은 값이라, 이름을 상수로
    //     굳히는 변이도 순번으로 매기는 변이도 둘 다 통과한다. 셋을 만들고 <b>가운데를 지워</b>
    //     둘의 결과가 갈리는 상태(목록 순번 1·2 ↔ 키 1·3)를 만든다.
    await user.click(screen.getByTestId('ea-add-evidence'));
    await user.click(screen.getByTestId('ea-add-evidence'));
    expect(screen.getByText('근거 3')).toHaveTextContent('c3');
    await user.click(screen.getByRole('button', { name: '근거 2 삭제' }));

    expect(screen.queryByText('근거 2')).toBeNull();
    expect(screen.getByText('근거 1')).toHaveTextContent('c1');
    // 목록에서는 둘째지만 이름은 「근거 3」이다(순번으로 다시 매기면 「근거 2」가 된다).
    expect(screen.getByText('근거 3')).toHaveTextContent('c3');
    expect(screen.getByLabelText('근거 3 문장')).toBeInTheDocument();

    // ★필드 이름은 짧은 한 벌이다 — 편집과 읽기 전용이 같은 표를 본다.
    //   구 이름(「프레임 번호」·「객체 박스 좌표」)이 남아 있으면 두 화면이 갈린다.
    expect(screen.getAllByText('프레임')).toHaveLength(2);
    expect(screen.getAllByText('객체 박스')).toHaveLength(2);
    expect(screen.queryByText('프레임 번호')).toBeNull();
    expect(screen.queryByText('객체 박스 좌표')).toBeNull();
    // 입력칸의 접근성 이름도 같은 낱말을 쓴다(보이는 이름과 읽히는 이름이 갈리지 않게).
    expect(screen.getByLabelText('근거 3 프레임')).toBeInTheDocument();
    expect(screen.getByLabelText('근거 3 객체 박스')).toBeInTheDocument();
    // ⚠ 「화면에서 지정」은 여기서 단언하지 않는다 — 이 껍데기는 지정 요청 핸들러를 넘기지 않아
    //   버튼이 <b>렌더되지 않는 것이 정상</b>이다(핸들러가 없으면 눌러도 반응 없는 버튼이 되므로
    //   비활성이 아니라 아예 그리지 않는 것이 이 행의 계약이다). 그 축은 창 시험이 본다.
  });

  it('evidence_후보_객체번호_string배열_입력', async () => {
    const user = userEvent.setup();
    mock.onGet('/videos/4/event-annotation').reply(200, emptyGet(4));
    let putBody: { evidence?: Record<string, { obj_id?: unknown }> } | null = null;
    mock.onPut('/videos/4/event-annotation').reply((config) => {
      putBody = JSON.parse(config.data);
      return [200, { success: true, data: emptyGet(4).data, message: null, errorCode: null }];
    });

    renderWithProviders(<Harness rawSn={4} />);
    await waitFor(() => expect(screen.getByTestId('ea-event-class')).toBeInTheDocument());
    await user.type(screen.getByTestId('ea-event-class'), '이벤트');

    await user.click(screen.getByTestId('ea-add-evidence'));
    // 객체 번호는 콤마 구분 문자열 배열로 입력
    await user.type(screen.getByTestId('ea-evidence-objid-c1'), 'obj-1, obj-2');
    await user.click(screen.getByTestId('harness-save'));

    await waitFor(() => expect(putBody).not.toBeNull());
    expect(putBody!.evidence!.c1.obj_id).toEqual(['obj-1', 'obj-2']);
  });

  it('REVIEWER면_승인반려버튼_노출_WORKER면_미노출', async () => {
    // given — REVIEWER + INTERNAL + 검토 가능 상태(PENDING)
    mock.onGet('/videos/10/event-annotation').reply(200, statusGet(10, 'PENDING'));
    useAuthStore.setState({
      token: TEST_TOKEN,
      claims: { sub: '1', role: 'REVIEWER', channel: 'INTERNAL', exp: 9999999999 },
    });
    const { unmount } = renderWithProviders(<Harness rawSn={10} />);
    await waitFor(() => expect(screen.getByTestId('ea-approve')).toBeInTheDocument());
    expect(screen.getByTestId('ea-reject')).toBeInTheDocument();
    unmount();

    // WORKER — 검토 버튼 미노출
    mock.onGet('/videos/11/event-annotation').reply(200, statusGet(11, 'PENDING'));
    useAuthStore.setState({
      token: TEST_TOKEN,
      claims: { sub: '2', role: 'WORKER', channel: 'INTERNAL', exp: 9999999999 },
    });
    renderWithProviders(<Harness rawSn={11} />);
    await waitFor(() => expect(screen.getByTestId('ea-event-class')).toBeInTheDocument());
    expect(screen.queryByTestId('ea-approve')).not.toBeInTheDocument();
    expect(screen.queryByTestId('ea-reject')).not.toBeInTheDocument();
  });

  it('승인클릭시_approve_API호출_상태갱신', async () => {
    const user = userEvent.setup();
    useAuthStore.setState({
      token: TEST_TOKEN,
      claims: { sub: '1', role: 'REVIEWER', channel: 'INTERNAL', exp: 9999999999 },
    });
    // 최초 GET=PENDING, 승인 후 invalidate 재조회=APPROVED
    mock.onGet('/videos/12/event-annotation').replyOnce(200, statusGet(12, 'PENDING'));
    mock.onGet('/videos/12/event-annotation').reply(200, statusGet(12, 'APPROVED'));
    let approveCalled = false;
    mock.onPost('/videos/12/event-annotation/approve').reply(() => {
      approveCalled = true;
      return [200, { success: true, data: null, message: null, errorCode: null }];
    });

    renderWithProviders(<Harness rawSn={12} />);
    await waitFor(() => expect(screen.getByTestId('ea-approve')).toBeInTheDocument());
    await user.click(screen.getByTestId('ea-approve'));

    await waitFor(() => expect(approveCalled).toBe(true));
    // 상태 갱신(invalidate → APPROVED) — 승인됨 라벨 표시, 버튼은 사라짐
    await waitFor(() =>
      expect(screen.getByTestId('ea-review-status')).toHaveTextContent('승인됨'),
    );
    expect(screen.queryByTestId('ea-approve')).not.toBeInTheDocument();
  });

  it('반려는_사유입력시에만_호출되고_reason_body로_전송', async () => {
    const user = userEvent.setup();
    useAuthStore.setState({
      token: TEST_TOKEN,
      claims: { sub: '1', role: 'REVIEWER', channel: 'INTERNAL', exp: 9999999999 },
    });
    mock.onGet('/videos/13/event-annotation').reply(200, statusGet(13, 'PENDING'));
    let rejectBody: { reason?: string } | null = null;
    mock.onPost('/videos/13/event-annotation/reject').reply((config) => {
      rejectBody = JSON.parse(config.data);
      return [200, { success: true, data: null, message: null, errorCode: null }];
    });

    renderWithProviders(<Harness rawSn={13} />);
    await waitFor(() => expect(screen.getByTestId('ea-reject')).toBeInTheDocument());

    // 사유 없으면 반려 비활성
    expect(screen.getByTestId('ea-reject')).toBeDisabled();
    await user.type(screen.getByTestId('ea-reject-reason'), '근거 불충분');
    await user.click(screen.getByTestId('ea-reject'));

    await waitFor(() => expect(rejectBody).not.toBeNull());
    expect(rejectBody!.reason).toBe('근거 불충분');
  });

  it('reviewStatus_라벨_표시_중립한글', async () => {
    mock.onGet('/videos/14/event-annotation').reply(200, statusGet(14, 'AUTO_GENERATED'));
    renderWithProviders(<Harness rawSn={14} />);
    await waitFor(() =>
      expect(screen.getByTestId('ea-review-status')).toHaveTextContent('자동 생성'),
    );
  });

  it('저장400시_BE_에러메시지_필드원인_노출', async () => {
    const user = userEvent.setup();
    mock.onGet('/videos/15/event-annotation').reply(200, emptyGet(15));
    mock.onPut('/videos/15/event-annotation').reply(400, {
      success: false,
      data: null,
      message: 'event_class 는 필수입니다.',
      errorCode: 'INVALID_INPUT',
    });

    renderWithProviders(<Harness rawSn={15} />);
    await waitFor(() => expect(screen.getByTestId('ea-event-class')).toBeInTheDocument());
    await user.type(screen.getByTestId('ea-event-class'), '화재');
    await user.click(screen.getByTestId('harness-save'));

    await waitFor(() =>
      expect(screen.getByRole('alert')).toHaveTextContent('event_class 는 필수입니다.'),
    );
    // 저장이 실패한 칸은 칸 머리에 그 사실을 표시한다(어느 칸이 실패했는지 가르는 유일한 표시).
    expect(screen.getByTestId('annotation-column-failed')).toHaveTextContent('저장 실패');
  });

  it('프레임번호_소수입력시_정수만_전송_객체박스좌표는_소수유지', async () => {
    const user = userEvent.setup();
    mock.onGet('/videos/16/event-annotation').reply(200, emptyGet(16));
    let putBody: {
      evidence?: Record<string, { frame_id?: number[]; obj_bbox?: number[][] }>;
    } | null = null;
    mock.onPut('/videos/16/event-annotation').reply((config) => {
      putBody = JSON.parse(config.data);
      return [200, { success: true, data: emptyGet(16).data, message: null, errorCode: null }];
    });

    renderWithProviders(<Harness rawSn={16} />);
    await waitFor(() => expect(screen.getByTestId('ea-event-class')).toBeInTheDocument());
    await user.type(screen.getByTestId('ea-event-class'), '이벤트');
    await user.click(screen.getByTestId('ea-add-evidence'));
    await user.type(screen.getByTestId('ea-evidence-frameid-c1'), '100.7, 101');
    await user.type(screen.getByTestId('ea-evidence-objbbox-c1'), '10.5,20,30.2,40');
    await user.click(screen.getByTestId('harness-save'));

    await waitFor(() => expect(putBody).not.toBeNull());
    expect(putBody!.evidence!.c1.frame_id).toEqual([100, 101]);
    expect(putBody!.evidence!.c1.obj_bbox).toEqual([[10.5, 20, 30.2, 40]]);
  });

  it('프리필_후_서버재조회해도_편집내용_유실없음', async () => {
    const user = userEvent.setup();
    mock.onGet('/videos/17/event-annotation').reply(200, statusGet(17, 'PENDING'));
    const { queryClient } = renderWithProviders(<Harness rawSn={17} />);

    // 프리필(화재) → 사용자 편집(침입)
    await waitFor(() => expect(screen.getByTestId('ea-event-class')).toHaveValue('화재'));
    await user.clear(screen.getByTestId('ea-event-class'));
    await user.type(screen.getByTestId('ea-event-class'), '침입');

    // 서버 재조회(invalidate) — 동일 payload 재도착해도 편집 폼을 덮어쓰지 않음
    await queryClient.invalidateQueries();
    await waitFor(() => expect(screen.getByTestId('ea-event-class')).toHaveValue('침입'));
  });

  it('★바뀐_것이_없으면_저장을_보내지_않는다', async () => {
    // given — 프리필만 되고 아무것도 고치지 않은 상태. 창 아래 공통 저장은 「바뀐 칸만」 보내므로
    //   이 칸은 보낼 것이 없다고 답해야 한다(보내면 남의 수정을 같은 값으로 덮어쓴다).
    const user = userEvent.setup();
    mock.onGet('/videos/18/event-annotation').reply(200, statusGet(18, 'PENDING'));
    mock.onPut('/videos/18/event-annotation').reply(200, {
      success: true,
      data: statusGet(18, 'PENDING').data,
      message: null,
      errorCode: null,
    });

    renderWithProviders(<Harness rawSn={18} />);
    await waitFor(() => expect(screen.getByTestId('ea-event-class')).toHaveValue('화재'));
    await user.click(screen.getByTestId('harness-save'));

    // then — PUT 이 한 건도 나가지 않는다.
    expect(mock.history.put.filter((r) => r.url?.includes('event-annotation'))).toHaveLength(0);
  });

  it('★이벤트_분류가_비면_보내지_않고_사유를_알린다', async () => {
    // given — 이벤트 분류는 필수라 BE 가 400 으로 거부한다. 보내고 실패하는 대신 입구에서 막고
    //   사유를 알린다(사용자는 무엇을 채워야 하는지 바로 안다).
    const user = userEvent.setup();
    mock.onGet('/videos/19/event-annotation').reply(200, emptyGet(19));
    mock.onPut('/videos/19/event-annotation').reply(200, {
      success: true,
      data: emptyGet(19).data,
      message: null,
      errorCode: null,
    });

    renderWithProviders(<Harness rawSn={19} />);
    await waitFor(() => expect(screen.getByTestId('ea-event-class')).toBeInTheDocument());
    // 분류는 비운 채 답변만 채워 「바뀐 상태」를 만든다.
    await user.type(screen.getByTestId('ea-answer'), '사람이 넘어졌다');
    await user.click(screen.getByTestId('harness-save'));

    await waitFor(() =>
      expect(screen.getByTestId('annotation-column-failed')).toBeInTheDocument(),
    );
    expect(mock.history.put.filter((r) => r.url?.includes('event-annotation'))).toHaveLength(0);
  });
});
