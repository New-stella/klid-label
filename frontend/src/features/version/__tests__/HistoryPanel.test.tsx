import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { act, fireEvent, screen, waitFor, within } from '@testing-library/react';
import MockAdapter from 'axios-mock-adapter';

import { apiClient } from '@/lib/api/client';
import { HistoryPanel } from '@/features/version/components/HistoryPanel';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';
import { useLabelStore } from '@/stores/useLabelStore';
import { useUiStore } from '@/stores/useUiStore';

// 테스트용 더미 인증값(비밀 아님 — 시크릿 스캐너 오탐 회피용 조합).
const FAKE_TOKEN = ['t', 'o', 'k'].join('');

function setRole(role: 'REVIEWER' | 'WORKER', sub = 'u-7') {
  useAuthStore.setState({
    token: FAKE_TOKEN,
    claims: { sub, role, channel: 'INTERNAL', exp: 9999999999 },
  });
}

const versionsPayload = {
  success: true,
  data: [
    {
      commitSha: 'aaa111aaa111aaa111aaa111aaa111aaa111aaa1',
      shortHash: 'aaa111a',
      authorName: '홍길동',
      authorNo: '2001',
      message: '라벨 수정',
      committedAt: '2026-05-07T10:00:00Z',
      isCurrent: true,
    },
    {
      commitSha: 'bbb222bbb222bbb222bbb222bbb222bbb222bbb2',
      shortHash: 'bbb222b',
      authorName: '김검수',
      authorNo: '1001',
      message: '초기 라벨',
      committedAt: '2026-05-06T10:00:00Z',
      isCurrent: false,
    },
  ],
  message: null,
  errorCode: null,
};

/** 라벨 변경 이력(LS_DATA_LBL_HSTRY) 빈 페이지 — 변경 이력 탭 기본 로드 노이즈 방지. */
const emptyHistoryPayload = {
  success: true,
  data: { content: [], number: 0, size: 20, totalElements: 0, totalPages: 0 },
  message: null,
  errorCode: null,
};

/** 커밋 목록을 보려면 통합 히스토리 패널에서 "버전" 탭을 먼저 눌러야 한다. */
async function openVersionsTab() {
  const tab = await screen.findByTestId('history-tab-versions');
  fireEvent.click(tab);
}

/**
 * 커밋 행의 본문 버튼(단일 선택) 클릭. 체크박스(두 버전 비교)와 구분된다.
 *
 * 체크박스도 `button[type="button"]` 이므로(3상태 컨트롤은 네이티브 input 이 아니다)
 * `role="checkbox"` 를 제외해야 본문 버튼이 잡힌다.
 */
function commitRowButton(shortHash: string): HTMLButtonElement {
  const row = screen.getByTestId(`commit-row-${shortHash}`);
  const button = row.querySelector(
    'button[type="button"]:not([role="checkbox"])',
  ) as HTMLButtonElement;
  return button;
}

function clickCommitRow(shortHash: string) {
  fireEvent.click(commitRowButton(shortHash));
}

/** 커밋 행의 체크박스(두 버전 비교) 체크. */
function checkCommitRow(shortHash: string) {
  const row = screen.getByTestId(`commit-row-${shortHash}`);
  fireEvent.click(within(row).getByRole('checkbox'));
}

const HASH_A = 'aaa111aaa111aaa111aaa111aaa111aaa111aaa1';
const HASH_B = 'bbb222bbb222bbb222bbb222bbb222bbb222bbb2';

function diffPayload(data: unknown[]) {
  return { success: true, data, message: null, errorCode: null };
}

const oneDiff = [
  {
    type: 'MODIFIED',
    frameId: 3,
    objectId: 'obj-1',
    before: { type: 'BBOX', left: 0, top: 0, right: 5, bottom: 5 },
    after: { type: 'BBOX', left: 1, top: 1, right: 6, bottom: 6 },
  },
];

/** 두 버전 비교(구 경로) 호출 여부 — `/versions/{hash}/diff` 로 끝나는 요청. */
function pairDiffCalls(mock: MockAdapter) {
  return mock.history.get.filter((r) => /\/versions\/[^/]+\/diff$/.test(r.url ?? ''));
}

/** 작업본 비교(신규 경로) 호출 목록. */
function workingDiffCalls(mock: MockAdapter) {
  return mock.history.get.filter((r) =>
    /\/versions\/[^/]+\/diff-with-working$/.test(r.url ?? ''),
  );
}

describe('HistoryPanel', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    // 기본 활성 탭(변경 이력)이 마운트 시 label-history 를 조회하므로 공통 빈 페이지 모킹.
    // 구체 응답이 필요한 테스트는 mock.reset() 후 개별 재등록한다(먼저 등록 핸들러 우선 회피).
    mock.onGet(/\/frames\/\d+\/label-history/).reply(200, emptyHistoryPayload);
    useLabelStore.getState().reset();
    useUiStore.setState({ toasts: [] });
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
    useLabelStore.getState().reset();
    useUiStore.setState({ toasts: [] });
  });

  it('두_개의_탭_변경_이력과_버전이_노출됨', async () => {
    setRole('WORKER');
    mock.onGet('/frames/42/versions').reply(200, versionsPayload);

    renderWithProviders(<HistoryPanel srcSn={42} />);

    expect(await screen.findByTestId('history-tab-changes')).toBeInTheDocument();
    expect(screen.getByTestId('history-tab-versions')).toBeInTheDocument();
  });

  it('기본_활성_탭은_변경_이력_저장이다', async () => {
    setRole('WORKER');
    // beforeEach 의 공통 빈 label-history 모킹을 제거하고 구체 응답을 우선 등록.
    mock.reset();
    mock.onGet('/frames/42/versions').reply(200, versionsPayload);
    mock.onGet('/frames/42/label-history').reply(200, {
      success: true,
      data: {
        content: [
          {
            lblHstrySn: 5,
            srcSn: 42,
            actor: 'worker-1',
            regDt: '2026-07-20T10:00:00',
            addCnt: 1,
            mdfcnCnt: 0,
            delCnt: 0,
            changes: [
              {
                lblSn: 50,
                changeKind: 'ADDED',
                labelName: 'person',
                before: null,
                after: { lblTypeCd: 'BBOX', labelId: 1, labelNm: 'person', pointCn: '[[0,0],[1,1]]' },
              },
            ],
          },
        ],
        number: 0,
        size: 20,
        totalElements: 1,
        totalPages: 1,
      },
      message: null,
      errorCode: null,
    });

    renderWithProviders(<HistoryPanel srcSn={42} />);

    // 변경 이력 탭이 기본 선택 상태(aria-selected=true)
    const changesTab = await screen.findByTestId('history-tab-changes');
    expect(changesTab).toHaveAttribute('aria-selected', 'true');
    expect(screen.getByTestId('history-tab-versions')).toHaveAttribute(
      'aria-selected',
      'false',
    );

    // 변경 이력(저장) 내용이 바로 보인다 — 커밋 목록이 아니라.
    // 저장 이벤트 카드의 요약 뱃지/작업자가 노출된다.
    await waitFor(() => {
      expect(screen.getByText(/worker-1/)).toBeInTheDocument();
    });
    expect(screen.getByText('추가 +1')).toBeInTheDocument();
    // 버전 탭을 누르기 전에는 커밋 목록이 보이지 않는다.
    expect(screen.queryByText('aaa111a')).not.toBeInTheDocument();
  });

  it('버전_탭_클릭_시_커밋_목록이_노출됨', async () => {
    setRole('WORKER');
    mock.onGet('/frames/42/versions').reply(200, versionsPayload);

    renderWithProviders(<HistoryPanel srcSn={42} />);

    await openVersionsTab();

    await waitFor(() => {
      expect(screen.getByText('aaa111a')).toBeInTheDocument();
    });
    expect(screen.getByText('bbb222b')).toBeInTheDocument();
    expect(screen.getByTestId('history-tab-versions')).toHaveAttribute(
      'aria-selected',
      'true',
    );
  });

  it('REVIEWER가_버전_2건_렌더링하고_롤백_트리거가_노출됨', async () => {
    setRole('REVIEWER');
    mock.onGet('/frames/42/versions').reply(200, versionsPayload);

    renderWithProviders(<HistoryPanel srcSn={42} />);

    await openVersionsTab();

    await waitFor(() => {
      expect(screen.getByText('aaa111a')).toBeInTheDocument();
    });
    expect(screen.getByText('bbb222b')).toBeInTheDocument();

    // 최신 아닌 커밋(bbb222b) 단일 선택 → 롤백 트리거 노출
    const bbbButton = commitRowButton('bbb222b');
    fireEvent.click(bbbButton);

    await waitFor(() => {
      expect(screen.getByTestId('rollback-trigger-bbb222b')).toBeInTheDocument();
    });
  });

  it('WORKER도_롤백_트리거_노출됨', async () => {
    setRole('WORKER');
    mock.onGet('/frames/42/versions').reply(200, versionsPayload);

    renderWithProviders(<HistoryPanel srcSn={42} />);

    await openVersionsTab();

    await waitFor(() => {
      expect(screen.getByText('aaa111a')).toBeInTheDocument();
    });

    const bbbButton = commitRowButton('bbb222b');
    fireEvent.click(bbbButton);

    // WORKER에게도 롤백 트리거가 노출되어야 함
    await waitFor(() => {
      expect(screen.getByTestId('rollback-trigger-bbb222b')).toBeInTheDocument();
    });
  });

  it('busy_중에는_롤백_트리거가_비활성이고_승인도_차단된다', async () => {
    // 롤백은 **서버측 라벨 재작성**이라 저장 PUT in-flight 와 교차 실행되면 최종본이 결정되지 않는다.
    setRole('REVIEWER');
    mock.onGet('/frames/42/versions').reply(200, versionsPayload);
    mock.onPost(/\/frames\/42\/rollback/).reply(200, { success: true, data: null, message: null, errorCode: null });

    renderWithProviders(<HistoryPanel srcSn={42} />);
    await openVersionsTab();
    await waitFor(() => expect(screen.getByText('bbb222b')).toBeInTheDocument());

    fireEvent.click(commitRowButton('bbb222b'));
    const trigger = await screen.findByTestId('rollback-trigger-bbb222b');
    expect(trigger).not.toBeDisabled();

    // when: 저장이 진행 중
    act(() => {
      useLabelStore.getState().beginBusy('SAVE', { srcSn: 42 });
    });

    // then: 트리거 비활성 + 클릭해도 확인 모달이 열리지 않는다.
    await waitFor(() =>
      expect(screen.getByTestId('rollback-trigger-bbb222b')).toBeDisabled(),
    );
    fireEvent.click(screen.getByTestId('rollback-trigger-bbb222b'));
    expect(screen.queryByText('이 버전으로 롤백하시겠습니까?')).not.toBeInTheDocument();

    // 해제되면 즉시 복구된다.
    act(() => {
      useLabelStore.getState().cancelBusy();
    });
    await waitFor(() =>
      expect(screen.getByTestId('rollback-trigger-bbb222b')).not.toBeDisabled(),
    );
  });

  it('확인모달을_연_뒤_busy가_시작되면_롤백_요청이_나가지_않는다', async () => {
    setRole('REVIEWER');
    mock.onGet('/frames/42/versions').reply(200, versionsPayload);
    mock.onPost(/rollback/).reply(200, { success: true, data: null, message: null, errorCode: null });

    renderWithProviders(<HistoryPanel srcSn={42} />);
    await openVersionsTab();
    await waitFor(() => expect(screen.getByText('bbb222b')).toBeInTheDocument());
    fireEvent.click(commitRowButton('bbb222b'));
    fireEvent.click(await screen.findByTestId('rollback-trigger-bbb222b'));
    const dialog = await screen.findByRole('dialog');

    act(() => {
      useLabelStore.getState().beginBusy('SAVE', { srcSn: 42 });
    });
    fireEvent.click(within(dialog).getByRole('button', { name: '롤백' }));
    await act(async () => {
      await Promise.resolve();
    });

    expect(mock.history.post).toHaveLength(0);
    const toasts = useUiStore.getState().toasts;
    expect(toasts.some((t) => t.variant === 'warning' && t.message.includes('진행 중'))).toBe(true);
    expect(toasts.every((t) => !/SAM|YOLO/i.test(t.message))).toBe(true);
  });

  it('빈_응답일_때_EmptyState_로_버전_이력_없음을_알리고_500_없이_렌더', async () => {
    setRole('WORKER');
    mock.onGet('/frames/99/versions').reply(200, {
      success: true,
      data: [],
      message: null,
      errorCode: null,
    });

    renderWithProviders(<HistoryPanel srcSn={99} />);

    await openVersionsTab();

    // UI-066: 빈 목록은 평문이 아니라 EmptyState('버전 이력이 없습니다')다.
    await waitFor(() => {
      expect(screen.getByText('버전 이력이 없습니다')).toBeInTheDocument();
    });
    // role=status 컨테이너까지 확인 — 문구만 맞고 EmptyState 가 아닌 회귀를 막는다.
    expect(
      within(screen.getByRole('status')).getByText('버전 이력이 없습니다'),
    ).toBeInTheDocument();
    // 패널 자체는 정상 렌더 (회귀 가드)
    expect(screen.getByTestId('history-panel')).toBeInTheDocument();
  });

  it('onClose_콜백이_있으면_닫기_버튼_노출_및_호출', async () => {
    setRole('WORKER');
    mock.onGet('/frames/42/versions').reply(200, versionsPayload);

    let closed = false;
    renderWithProviders(<HistoryPanel srcSn={42} onClose={() => { closed = true; }} />);

    await waitFor(() => {
      expect(screen.getByTestId('history-panel-close')).toBeInTheDocument();
    });
    fireEvent.click(screen.getByTestId('history-panel-close'));
    expect(closed).toBe(true);
  });

  // ---- 단일 선택 = 현재 작업본 비교 (R1/R2/R4) ----
  // 주의: "DiffViewer 가 렌더됐다"만 보면 두 비교 경로를 구분하지 못한다. 반드시 요청 URL 로 단언한다.

  it('커밋_1건_클릭시_현재_작업본_diff_API_가_호출된다', async () => {
    // [req: R1]
    setRole('WORKER');
    mock.onGet('/frames/42/versions').reply(200, versionsPayload);
    mock
      .onGet(`/versions/${HASH_B}/diff-with-working`)
      .reply(200, diffPayload(oneDiff));

    renderWithProviders(<HistoryPanel srcSn={42} />);
    await openVersionsTab();
    await waitFor(() => expect(screen.getByText('bbb222b')).toBeInTheDocument());

    clickCommitRow('bbb222b');

    await waitFor(() => expect(workingDiffCalls(mock)).toHaveLength(1));
    expect(workingDiffCalls(mock)[0].url).toBe(`/versions/${HASH_B}/diff-with-working`);
    // diff 결과가 실제로 화면에 그려진다.
    expect(await screen.findByTestId('diff-row-MODIFIED-obj-1')).toBeInTheDocument();
    // 비교 대상이 "현재 작업본"임을 화면에 명시한다.
    expect(screen.getByTestId('diff-compare-target')).toHaveTextContent('현재 작업본');
    expect(screen.getByTestId('diff-compare-target')).toHaveTextContent('bbb222b');
  });

  it('버전이_1건뿐이어도_클릭하면_diff_가_표시된다', async () => {
    // [req: R1] 회귀 지점 — 구 동작(list[idx+1] 비교)에서는 비교 대상이 없어 안내 문구만 떴다.
    setRole('WORKER');
    mock.onGet('/frames/55/versions').reply(200, {
      success: true,
      data: [versionsPayload.data[0]],
      message: null,
      errorCode: null,
    });
    mock
      .onGet(`/versions/${HASH_A}/diff-with-working`)
      .reply(200, diffPayload(oneDiff));

    renderWithProviders(<HistoryPanel srcSn={55} />);
    await openVersionsTab();
    await waitFor(() => expect(screen.getByText('aaa111a')).toBeInTheDocument());

    clickCommitRow('aaa111a');

    expect(await screen.findByTestId('diff-row-MODIFIED-obj-1')).toBeInTheDocument();
    await waitFor(() => expect(workingDiffCalls(mock)).toHaveLength(1));
    expect(
      screen.queryByText(/커밋을 선택하면 현재 작업본과 비교하고/),
    ).not.toBeInTheDocument();
  });

  it('작업본과_동일하면_변경_없음_문구가_표시된다', async () => {
    // [req: R4] 빈 목록이 아니라 "변경 없음" 안내 — 로딩/조회 실패와 구분된다.
    setRole('WORKER');
    mock.onGet('/frames/42/versions').reply(200, versionsPayload);
    mock.onGet(`/versions/${HASH_B}/diff-with-working`).reply(200, diffPayload([]));

    renderWithProviders(<HistoryPanel srcSn={42} />);
    await openVersionsTab();
    await waitFor(() => expect(screen.getByText('bbb222b')).toBeInTheDocument());

    clickCommitRow('bbb222b');

    expect(await screen.findByText('변경 없음')).toBeInTheDocument();
    expect(
      screen.getByText('이 버전 이후 변경된 라벨이 없습니다.'),
    ).toBeInTheDocument();
    // 기존(두 버전 비교) 문구가 대신 뜨면 안 된다.
    expect(screen.queryByText('두 버전이 동일합니다.')).not.toBeInTheDocument();
  });

  it('작업본_diff_조회_실패시_에러_문구가_표시된다', async () => {
    setRole('WORKER');
    mock.onGet('/frames/42/versions').reply(200, versionsPayload);
    mock.onGet(`/versions/${HASH_B}/diff-with-working`).reply(412, {
      success: false,
      data: null,
      message: '비식별 재처리 대기 중인 영상입니다.',
      errorCode: 'DEIDENT_REPORT_OPEN',
    });

    renderWithProviders(<HistoryPanel srcSn={42} />);
    await openVersionsTab();
    await waitFor(() => expect(screen.getByText('bbb222b')).toBeInTheDocument());

    clickCommitRow('bbb222b');

    expect(await screen.findByText('diff 조회 실패')).toBeInTheDocument();
    // 변경 없음(R4) 과 혼동되지 않는다.
    expect(screen.queryByText('변경 없음')).not.toBeInTheDocument();
  });

  it('두_커밋_체크시_기존_두_버전_diff_API_가_호출된다', async () => {
    // 회귀 가드 — 체크박스 2건 = 버전 간 비교(기존 계약) 유지.
    setRole('WORKER');
    mock.onGet('/frames/42/versions').reply(200, versionsPayload);
    mock.onGet(`/versions/${HASH_A}/diff`).reply(200, diffPayload(oneDiff));

    renderWithProviders(<HistoryPanel srcSn={42} />);
    await openVersionsTab();
    await waitFor(() => expect(screen.getByText('bbb222b')).toBeInTheDocument());

    checkCommitRow('aaa111a');
    checkCommitRow('bbb222b');

    await waitFor(() => expect(pairDiffCalls(mock)).toHaveLength(1));
    const call = pairDiffCalls(mock)[0];
    expect(call.url).toBe(`/versions/${HASH_A}/diff`);
    expect(call.params).toMatchObject({ compareWith: HASH_B });
    // 작업본 비교 경로는 호출되지 않는다.
    expect(workingDiffCalls(mock)).toHaveLength(0);
    expect(await screen.findByTestId('diff-row-MODIFIED-obj-1')).toBeInTheDocument();
  });

  it('아무것도_선택하지_않으면_선택_안내_문구가_표시된다', async () => {
    setRole('WORKER');
    mock.onGet('/frames/42/versions').reply(200, versionsPayload);

    renderWithProviders(<HistoryPanel srcSn={42} />);
    await openVersionsTab();
    await waitFor(() => expect(screen.getByText('bbb222b')).toBeInTheDocument());

    expect(
      screen.getByText(
        '커밋을 선택하면 현재 작업본과 비교하고, 두 커밋을 체크하면 버전 간 비교합니다.',
      ),
    ).toBeInTheDocument();
    expect(workingDiffCalls(mock)).toHaveLength(0);
    expect(pairDiffCalls(mock)).toHaveLength(0);
  });

  it('프레임_전환시_선택이_초기화되어_작업본_diff_가_호출되지_않는다', async () => {
    setRole('WORKER');
    mock.onGet('/frames/42/versions').reply(200, versionsPayload);
    mock.onGet('/frames/43/versions').reply(200, versionsPayload);
    mock
      .onGet(`/versions/${HASH_B}/diff-with-working`)
      .reply(200, diffPayload(oneDiff));

    const { rerender } = renderWithProviders(<HistoryPanel srcSn={42} />);
    await openVersionsTab();
    await waitFor(() => expect(screen.getByText('bbb222b')).toBeInTheDocument());

    clickCommitRow('bbb222b');
    await waitFor(() => expect(workingDiffCalls(mock)).toHaveLength(1));

    // when: 다른 프레임으로 전환
    rerender(<HistoryPanel srcSn={43} />);
    await openVersionsTab();
    await waitFor(() => expect(screen.getByText('bbb222b')).toBeInTheDocument());

    // then: 선택이 리셋되어 추가 호출이 없고 안내 문구로 돌아간다.
    expect(
      screen.getByText(
        '커밋을 선택하면 현재 작업본과 비교하고, 두 커밋을 체크하면 버전 간 비교합니다.',
      ),
    ).toBeInTheDocument();
    expect(workingDiffCalls(mock)).toHaveLength(1);
  });

  it('프레임_전환시_두_커밋_체크가_초기화되어_버전간_diff_가_호출되지_않는다', async () => {
    // working 축과 대칭 가드 — 프레임 전환 직후 한 렌더 동안 남는 이전 프레임의 두 hash 로
    // /diff?compareWith= 가 나가면 이전 프레임 버전들의 diff 가 새 화면에 잠깐 표시된다.
    setRole('WORKER');
    mock.onGet('/frames/42/versions').reply(200, versionsPayload);
    mock.onGet('/frames/43/versions').reply(200, versionsPayload);
    mock.onGet(`/versions/${HASH_A}/diff`).reply(200, diffPayload(oneDiff));

    const { rerender } = renderWithProviders(<HistoryPanel srcSn={42} />);
    await openVersionsTab();
    await waitFor(() => expect(screen.getByText('bbb222b')).toBeInTheDocument());

    checkCommitRow('aaa111a');
    checkCommitRow('bbb222b');
    await waitFor(() => expect(pairDiffCalls(mock)).toHaveLength(1));

    // when: 다른 프레임으로 전환
    rerender(<HistoryPanel srcSn={43} />);
    await openVersionsTab();
    await waitFor(() => expect(screen.getByText('bbb222b')).toBeInTheDocument());

    // then: 이전 프레임의 두 hash 로 추가 요청이 나가지 않는다(요청 URL 로 단언).
    expect(pairDiffCalls(mock)).toHaveLength(1);
    expect(pairDiffCalls(mock)[0].url).toBe(`/versions/${HASH_A}/diff`);
    expect(workingDiffCalls(mock)).toHaveLength(0);
    // 선택이 리셋되어 안내 문구로 돌아간다.
    expect(
      screen.getByText(
        '커밋을 선택하면 현재 작업본과 비교하고, 두 커밋을 체크하면 버전 간 비교합니다.',
      ),
    ).toBeInTheDocument();
  });

  it('단일_선택은_더_이상_직전_버전과_비교하지_않는다', async () => {
    // [req: R2] 폐기 동작 고정 — 구 구현은 list[idx+1](= bbb222b)을 from 으로 /diff 를 호출했다.
    setRole('WORKER');
    mock.onGet('/frames/42/versions').reply(200, versionsPayload);
    mock
      .onGet(`/versions/${HASH_A}/diff-with-working`)
      .reply(200, diffPayload(oneDiff));
    // 구 경로가 호출되면 즉시 드러나도록 성공 응답을 심어둔다(호출 자체를 URL 로 단언).
    mock.onGet(`/versions/${HASH_A}/diff`).reply(200, diffPayload(oneDiff));

    renderWithProviders(<HistoryPanel srcSn={42} />);
    await openVersionsTab();
    await waitFor(() => expect(screen.getByText('aaa111a')).toBeInTheDocument());

    clickCommitRow('aaa111a');

    await waitFor(() => expect(workingDiffCalls(mock)).toHaveLength(1));
    expect(workingDiffCalls(mock)[0].url).toBe(`/versions/${HASH_A}/diff-with-working`);
    expect(pairDiffCalls(mock)).toHaveLength(0);
  });

  it('BE_응답이_배열이_아니어도_빈_목록으로_방어', async () => {
    setRole('WORKER');
    // 레거시 { items: [...] } 응답 — FE 는 안전하게 빈 배열로 처리
    // 2026-05-18: 경로 정합화로 /frames/{srcSn}/versions 사용 (api.ts/api.test.ts 참조)
    mock.onGet('/frames/42/versions').reply(200, {
      success: true,
      data: { items: [{ commitSha: 'x' }] },
      message: null,
      errorCode: null,
    });

    renderWithProviders(<HistoryPanel srcSn={42} />);

    await openVersionsTab();

    await waitFor(() => {
      expect(screen.getByText('버전 이력이 없습니다')).toBeInTheDocument();
    });
  });

  // ----- UI-066: 체크 2건 상태의 기준(from)/비교(to) 역할 배지 -----

  it('두_커밋_체크시_각_행에_기준과_비교_역할_배지가_붙는다', async () => {
    setRole('WORKER');
    mock.onGet('/frames/42/versions').reply(200, versionsPayload);
    mock.onGet(`/versions/${HASH_A}/diff`).reply(200, diffPayload([]));

    renderWithProviders(<HistoryPanel srcSn={42} />);
    await openVersionsTab();
    await waitFor(() => expect(screen.getByText('bbb222b')).toBeInTheDocument());

    // 먼저 체크한 쪽이 비교(to), 나중에 체크한 쪽이 기준(from) — diff 요청 방향과 동일하다.
    checkCommitRow('aaa111a');
    checkCommitRow('bbb222b');

    expect(await screen.findByTestId('commit-role-to-aaa111a')).toHaveTextContent('비교');
    expect(screen.getByTestId('commit-role-from-bbb222b')).toHaveTextContent('기준');

    // 배지가 실제 diff 요청 방향과 일치하는지 — 배지만 맞고 요청이 반대인 회귀를 막는다.
    await waitFor(() => expect(pairDiffCalls(mock)).toHaveLength(1));
    const call = pairDiffCalls(mock)[0];
    expect(call.url).toBe(`/versions/${HASH_A}/diff`); // to
    expect(call.params).toMatchObject({ compareWith: HASH_B }); // from
  });

  it('체크가_1건뿐이면_역할_배지가_붙지_않는다', async () => {
    setRole('WORKER');
    mock.onGet('/frames/42/versions').reply(200, versionsPayload);

    renderWithProviders(<HistoryPanel srcSn={42} />);
    await openVersionsTab();
    await waitFor(() => expect(screen.getByText('bbb222b')).toBeInTheDocument());

    checkCommitRow('aaa111a');

    // 비교 축이 성립하지 않는데 배지를 붙이면 "무엇과 비교 중"이라는 거짓 신호가 된다.
    expect(screen.queryByTestId('commit-role-to-aaa111a')).not.toBeInTheDocument();
    expect(screen.queryByTestId('commit-role-from-aaa111a')).not.toBeInTheDocument();
    expect(screen.queryByTestId('commit-role-from-bbb222b')).not.toBeInTheDocument();
  });

  it('단일_선택_작업본_비교에서는_역할_배지가_붙지_않는다', async () => {
    setRole('WORKER');
    mock.onGet('/frames/42/versions').reply(200, versionsPayload);
    mock.onGet(`/versions/${HASH_A}/diff-with-working`).reply(200, diffPayload([]));

    renderWithProviders(<HistoryPanel srcSn={42} />);
    await openVersionsTab();
    await waitFor(() => expect(screen.getByText('bbb222b')).toBeInTheDocument());

    clickCommitRow('aaa111a');

    await waitFor(() => expect(workingDiffCalls(mock)).toHaveLength(1));
    expect(screen.queryByTestId('commit-role-to-aaa111a')).not.toBeInTheDocument();
    expect(screen.queryByTestId('commit-role-from-aaa111a')).not.toBeInTheDocument();
  });
});
