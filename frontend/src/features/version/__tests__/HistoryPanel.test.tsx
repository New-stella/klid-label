import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { fireEvent, screen, waitFor } from '@testing-library/react';
import MockAdapter from 'axios-mock-adapter';

import { apiClient } from '@/lib/api/client';
import { HistoryPanel } from '@/features/version/components/HistoryPanel';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';

function setRole(role: 'REVIEWER' | 'WORKER', sub = 'u-7') {
  useAuthStore.setState({
    token: 'tok',
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
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
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
            lblSn: 50,
            changeKind: 'ADDED',
            actor: 'worker-1',
            regDt: '2026-07-20T10:00:00',
            label: 'person',
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
    await waitFor(() => {
      expect(screen.getByText('person')).toBeInTheDocument();
    });
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
