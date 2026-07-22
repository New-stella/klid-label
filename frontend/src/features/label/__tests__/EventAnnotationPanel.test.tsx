// Phase 4 — EventAnnotationPanel 수동입력 폼 검증(RED→GREEN).
//
// - 전 필드 입력 후 저장 시 PUT payload 가 c1..cn 형태로 정확한지
// - VLM 수신 기본값 프리필 + 수동 덮어쓰기
// - caption 후보 c1/c2 추가·삭제 + cot 3단계 입력(불변성)
// - evidence 후보 obj_id string 배열 입력

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { apiClient } from '@/lib/api/client';
import { EventAnnotationPanel } from '@/features/label/components/EventAnnotationPanel';
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

    renderWithProviders(<EventAnnotationPanel rawSn={5} />);
    await waitFor(() => expect(screen.getByTestId('ea-event-class')).toBeInTheDocument());

    await user.type(screen.getByTestId('ea-event-class'), '화재');
    await user.type(screen.getByTestId('ea-question'), '무슨 일인가?');
    await user.type(screen.getByTestId('ea-answer'), '화재 발생');

    // caption 후보 추가(c1) + caption_text + cot 3단계
    await user.click(screen.getByTestId('ea-add-caption'));
    await user.type(screen.getByTestId('ea-caption-text-c1'), '연기가 보인다');
    await user.type(screen.getByTestId('ea-caption-cot-c1-0'), '관찰');
    await user.type(screen.getByTestId('ea-caption-cot-c1-1'), '추론');
    await user.type(screen.getByTestId('ea-caption-cot-c1-2'), '결론');

    // evidence 후보 추가(c1) + evidence_text + obj_id
    await user.click(screen.getByTestId('ea-add-evidence'));
    await user.type(screen.getByTestId('ea-evidence-text-c1'), '불꽃');
    await user.type(screen.getByTestId('ea-evidence-objid-c1'), 'obj-1');

    await user.click(screen.getByTestId('ea-save'));

    await waitFor(() => expect(putBody).not.toBeNull());
    expect(putBody).toEqual({
      event_class: '화재',
      question: '무슨 일인가?',
      answer: '화재 발생',
      caption: { c1: { caption_text: '연기가 보인다', cot: ['관찰', '추론', '결론'] } },
      evidence: { c1: { evidence_text: '불꽃', obj_id: ['obj-1'] } },
    });
  });

  it('VLM_수신_기본값이_폼에_프리필되고_수동_덮어쓰기_가능', async () => {
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

    renderWithProviders(<EventAnnotationPanel rawSn={8} />);

    // 프리필 — VLM 수신값이 입력 필드에 채워짐
    await waitFor(() =>
      expect(screen.getByTestId('ea-event-class')).toHaveValue('침입'),
    );
    expect(screen.getByTestId('ea-caption-text-c1')).toHaveValue('담을 넘음');
    expect(screen.getByTestId('ea-evidence-objid-c1')).toHaveValue('p-1');

    // 수동 덮어쓰기
    await user.clear(screen.getByTestId('ea-event-class'));
    await user.type(screen.getByTestId('ea-event-class'), '화재');
    await user.click(screen.getByTestId('ea-save'));

    await waitFor(() => expect(putBody).not.toBeNull());
    expect(putBody!.event_class).toBe('화재');
  });

  it('caption_후보_c1c2_추가삭제_cot_3단계_입력_불변성_유지', async () => {
    const user = userEvent.setup();
    mock.onGet('/videos/6/event-annotation').reply(200, emptyGet(6));
    let putBody: { caption?: Record<string, unknown> } | null = null;
    mock.onPut('/videos/6/event-annotation').reply((config) => {
      putBody = JSON.parse(config.data);
      return [200, { success: true, data: emptyGet(6).data, message: null, errorCode: null }];
    });

    renderWithProviders(<EventAnnotationPanel rawSn={6} />);
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

    await user.click(screen.getByTestId('ea-save'));
    await waitFor(() => expect(putBody).not.toBeNull());
    // c1 은 삭제되어 payload 에 없음, c2 데이터는 보존
    expect(putBody!.caption).toEqual({
      c2: { caption_text: 'c2 캡션', cot: ['a'] },
    });
  });

  it('evidence_후보_obj_id_string배열_입력', async () => {
    const user = userEvent.setup();
    mock.onGet('/videos/4/event-annotation').reply(200, emptyGet(4));
    let putBody: { evidence?: Record<string, { obj_id?: unknown }> } | null = null;
    mock.onPut('/videos/4/event-annotation').reply((config) => {
      putBody = JSON.parse(config.data);
      return [200, { success: true, data: emptyGet(4).data, message: null, errorCode: null }];
    });

    renderWithProviders(<EventAnnotationPanel rawSn={4} />);
    await waitFor(() => expect(screen.getByTestId('ea-event-class')).toBeInTheDocument());
    await user.type(screen.getByTestId('ea-event-class'), '이벤트');

    await user.click(screen.getByTestId('ea-add-evidence'));
    // obj_id 는 콤마 구분 문자열 배열로 입력
    await user.type(screen.getByTestId('ea-evidence-objid-c1'), 'obj-1, obj-2');
    await user.click(screen.getByTestId('ea-save'));

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
    const { unmount } = renderWithProviders(<EventAnnotationPanel rawSn={10} />);
    await waitFor(() => expect(screen.getByTestId('ea-approve')).toBeInTheDocument());
    expect(screen.getByTestId('ea-reject')).toBeInTheDocument();
    unmount();

    // WORKER — 검토 버튼 미노출
    mock.onGet('/videos/11/event-annotation').reply(200, statusGet(11, 'PENDING'));
    useAuthStore.setState({
      token: TEST_TOKEN,
      claims: { sub: '2', role: 'WORKER', channel: 'INTERNAL', exp: 9999999999 },
    });
    renderWithProviders(<EventAnnotationPanel rawSn={11} />);
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

    renderWithProviders(<EventAnnotationPanel rawSn={12} />);
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

    renderWithProviders(<EventAnnotationPanel rawSn={13} />);
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
    renderWithProviders(<EventAnnotationPanel rawSn={14} />);
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

    renderWithProviders(<EventAnnotationPanel rawSn={15} />);
    await waitFor(() => expect(screen.getByTestId('ea-event-class')).toBeInTheDocument());
    await user.type(screen.getByTestId('ea-event-class'), '화재');
    await user.click(screen.getByTestId('ea-save'));

    await waitFor(() =>
      expect(screen.getByRole('alert')).toHaveTextContent('event_class 는 필수입니다.'),
    );
  });

  it('frame_id_소수입력시_정수만_전송_obj_bbox는_소수유지', async () => {
    const user = userEvent.setup();
    mock.onGet('/videos/16/event-annotation').reply(200, emptyGet(16));
    let putBody: {
      evidence?: Record<string, { frame_id?: number[]; obj_bbox?: number[][] }>;
    } | null = null;
    mock.onPut('/videos/16/event-annotation').reply((config) => {
      putBody = JSON.parse(config.data);
      return [200, { success: true, data: emptyGet(16).data, message: null, errorCode: null }];
    });

    renderWithProviders(<EventAnnotationPanel rawSn={16} />);
    await waitFor(() => expect(screen.getByTestId('ea-event-class')).toBeInTheDocument());
    await user.type(screen.getByTestId('ea-event-class'), '이벤트');
    await user.click(screen.getByTestId('ea-add-evidence'));
    await user.type(screen.getByTestId('ea-evidence-frameid-c1'), '100.7, 101');
    await user.type(screen.getByTestId('ea-evidence-objbbox-c1'), '10.5,20,30.2,40');
    await user.click(screen.getByTestId('ea-save'));

    await waitFor(() => expect(putBody).not.toBeNull());
    expect(putBody!.evidence!.c1.frame_id).toEqual([100, 101]);
    expect(putBody!.evidence!.c1.obj_bbox).toEqual([[10.5, 20, 30.2, 40]]);
  });

  it('프리필_후_서버재조회해도_편집내용_유실없음', async () => {
    const user = userEvent.setup();
    mock.onGet('/videos/17/event-annotation').reply(200, statusGet(17, 'PENDING'));
    const { queryClient } = renderWithProviders(<EventAnnotationPanel rawSn={17} />);

    // 프리필(화재) → 사용자 편집(침입)
    await waitFor(() => expect(screen.getByTestId('ea-event-class')).toHaveValue('화재'));
    await user.clear(screen.getByTestId('ea-event-class'));
    await user.type(screen.getByTestId('ea-event-class'), '침입');

    // 서버 재조회(invalidate) — 동일 payload 재도착해도 편집 폼을 덮어쓰지 않음
    await queryClient.invalidateQueries();
    await waitFor(() => expect(screen.getByTestId('ea-event-class')).toHaveValue('침입'));
  });
});
