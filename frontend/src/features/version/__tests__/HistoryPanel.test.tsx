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
      message: '라벨 수정',
      committedAt: '2026-05-07T10:00:00Z',
      isCurrent: true,
    },
    {
      commitSha: 'bbb222bbb222bbb222bbb222bbb222bbb222bbb2',
      shortHash: 'bbb222b',
      authorName: '김검수',
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
    const bbbRow = screen.getByTestId('commit-row-bbb222b');
    const bbbButton = bbbRow.querySelector('button[type="button"]') as HTMLButtonElement;
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

    const bbbRow = screen.getByTestId('commit-row-bbb222b');
    const bbbButton = bbbRow.querySelector('button[type="button"]') as HTMLButtonElement;
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

    const bbbRow = screen.getByTestId('commit-row-bbb222b');
    fireEvent.click(bbbRow.querySelector('button[type="button"]') as HTMLButtonElement);
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
    const bbbRow = screen.getByTestId('commit-row-bbb222b');
    fireEvent.click(bbbRow.querySelector('button[type="button"]') as HTMLButtonElement);
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

  it('빈_응답일_때_커밋_없음_메시지_노출_및_500_없이_렌더', async () => {
    setRole('WORKER');
    mock.onGet('/frames/99/versions').reply(200, {
      success: true,
      data: [],
      message: null,
      errorCode: null,
    });

    renderWithProviders(<HistoryPanel srcSn={99} />);

    await openVersionsTab();

    await waitFor(() => {
      expect(screen.getByText('아직 커밋된 버전이 없습니다.')).toBeInTheDocument();
    });
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
      expect(screen.getByText('아직 커밋된 버전이 없습니다.')).toBeInTheDocument();
    });
  });
});
