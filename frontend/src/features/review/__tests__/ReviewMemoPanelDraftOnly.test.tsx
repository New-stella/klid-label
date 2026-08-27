// 회귀 가드 — 검수 메모 목록은 «반려 사유 초안»만 담는다. [@design SCREEN-019]
//
// 결함(사용자 신고): 반려하며 사유를 쓰면 그 사유가 「이슈」 탭뿐 아니라 「객체」 탭의 목록에도
//   나타났다(같은 내용의 이중 표시).
//
// 전말: 객체 탭에서 이슈 추가 모드로 캔버스 라벨을 찍고 검수 의견을 쓰면, 반려 시
//   `composeRejectReason` 이 그 둘을 사유 문자열 하나로 합성해 보낸다 → 서버가 반려 스레드를
//   만든다 → 「이슈」 탭에 뜬다. 그런데 같은 패널이 `useReviewIssues`(서버 등록 이슈)도 함께
//   그리고 있어서, 방금 보낸 사유가 스레드가 되어 이 목록으로 되돌아왔다.
//
// ★구현 결함이 아니라 사양이 시켰던 동작이다 — 사양에 「검수 요청 이전에 이미 서버에 등록되어
//   있던 이슈를 같은 목록에 참고용으로 함께 표시한다」가 있었고, 그 문장이 제거됐다. 지금 사양은
//   "서버에 등록된 이슈의 열람·댓글·해소는 「이슈」 탭이 단독으로 담당하므로 이 목록에는 서버에
//   등록된 이슈를 섞지 않는다"이다.
//
// ★제거 범위는 «서버 등록분»뿐이다 — 이 패널의 나머지 셋(이슈 추가 모드 토글 · 초안 목록 ·
//   검수 의견 입력칸)은 전부 반려 사유를 만드는 도구라 그대로 남는다. 목록을 통째로 지우면
//   반려 사유에 지적을 첨부할 수단이 사라진다.

import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { cleanup, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { apiClient } from '@/lib/api/client';
import { renderWithProviders } from '@/test/renderWithProviders';

import { IssueThreadPanel } from '../components/IssueThreadPanel';
import { ReviewMemoPanel } from '../components/ReviewMemoPanel';
import type { ReviewMemoPanelProps } from '../components/ReviewMemoPanel';
import { useReviewSelectionStore } from '../store/useReviewSelectionStore';
import type { IssueThread } from '../types';

/**
 * 「서버 등록 이슈를 넘기던」 구 배선을 재현한다.
 *
 * 지금은 `ReviewMemoPanel` 이 그 prop 을 아예 받지 않으므로 타입상 넘길 수 없다 — 그래서 캐스팅해
 * 넘긴다. 배선이 되살아나면(= prop 이 다시 생기고 렌더되면) 아래 단언이 RED 가 된다.
 * ⚠ prop 만 되살리고 렌더를 안 하면 이 케이스는 통과하지만, 그건 화면에 안 보인다는 뜻이라 결함이
 *   아니다 — 이 가드가 지키는 것은 «보이는 것»이다.
 */
const SERVER_ISSUE_PROPS = {
  videoId: 1,
  issues: [
    {
      id: 1,
      frameId: 3,
      description: '서버에 등록된 반려 사유 본문',
      createdAt: '2026-08-27T10:00:00Z',
    },
    {
      id: 2,
      frameId: 5,
      description: '서버에 등록된 문의 본문',
      createdAt: '2026-08-27T11:00:00Z',
    },
  ],
} as unknown as ReviewMemoPanelProps;

describe('ReviewMemoPanel — 반려 사유 초안 전용', () => {
  beforeEach(() => {
    useReviewSelectionStore.getState().clear();
    useReviewSelectionStore.getState().setCurrentFrameIdx(0);
  });

  it('서버에_등록된_이슈는_이_목록에_그려지지_않는다', () => {
    renderWithProviders(<ReviewMemoPanel {...SERVER_ISSUE_PROPS} />);

    expect(screen.queryByTestId('memo-issue-be-1')).toBeNull();
    expect(screen.queryByTestId('memo-issue-be-2')).toBeNull();
    expect(screen.queryByText('서버에 등록된 반려 사유 본문')).toBeNull();
    expect(screen.queryByText('서버에 등록된 문의 본문')).toBeNull();
    // 서버 이슈만 있는 상태 = 이 목록 기준으로는 비어 있다.
    expect(screen.getByTestId('memo-issues-empty')).toBeInTheDocument();
  });

  it('건수는_초안만_센다_서버_등록분을_더하지_않는다', () => {
    useReviewSelectionStore.getState().addPendingIssue('초안 지적 1', 11);
    useReviewSelectionStore.getState().addPendingIssue('초안 지적 2', 12);

    renderWithProviders(<ReviewMemoPanel {...SERVER_ISSUE_PROPS} />);

    // 서버 등록 2건을 더해 4건으로 세던 것이 구 동작이다.
    expect(screen.getByText(/반려 사유에 첨부할 지적 \(2건\)/)).toBeInTheDocument();
    expect(screen.queryByText(/\(4건\)/)).toBeNull();
  });

  it('목록의_이름이_반려_사유_초안임을_드러낸다', () => {
    renderWithProviders(<ReviewMemoPanel videoId={1} />);

    // 구 문구는 「이슈 목록」·「등록된 이슈가 없습니다」라 서버에 등록된 이슈가 여기 있을 것처럼
    // 읽혔다. 이 목록은 아직 보내지 않은 초안이다.
    expect(screen.getByText(/반려 사유에 첨부할 지적/)).toBeInTheDocument();
    expect(screen.queryByText(/^이슈 목록/)).toBeNull();
    expect(screen.getByText('첨부할 지적이 없습니다')).toBeInTheDocument();
    expect(screen.queryByText('등록된 이슈가 없습니다')).toBeNull();
  });

  it('초안_목록은_남아_있고_수정_삭제가_그대로_동작한다', async () => {
    // ⚠ 서버 등록분을 걷어내면서 초안까지 함께 지우면 반려 사유에 지적을 첨부할 수단이 사라진다.
    //   두 목록이 같은 렌더 경로를 쓰고 있었으므로 분리 후 초안 쪽 편집·삭제를 다시 확인한다.
    useReviewSelectionStore.getState().addPendingIssue('고칠 지적', 21);
    useReviewSelectionStore.getState().addPendingIssue('지울 지적', 22);

    const user = userEvent.setup();
    renderWithProviders(<ReviewMemoPanel {...SERVER_ISSUE_PROPS} />);

    const first = screen.getByTestId('memo-issue-pending-text-0') as HTMLTextAreaElement;
    expect(first.value).toBe('고칠 지적');
    await user.clear(first);
    await user.type(first, '고친 지적');
    expect(useReviewSelectionStore.getState().pendingIssues[0]!.text).toBe('고친 지적');

    await user.click(screen.getByTestId('memo-issue-pending-remove-1'));
    expect(useReviewSelectionStore.getState().pendingIssues).toHaveLength(1);
    expect(useReviewSelectionStore.getState().pendingIssues[0]!.text).toBe('고친 지적');
  });

  it('이슈_추가_모드_토글과_검수_의견_입력칸은_그대로_남는다', async () => {
    // 둘 다 «반려 사유를 만드는 도구»라 제거 대상이 아니다.
    const user = userEvent.setup();
    renderWithProviders(<ReviewMemoPanel {...SERVER_ISSUE_PROPS} />);

    const toggle = screen.getByTestId('issue-mode-toggle');
    expect(toggle).toHaveAttribute('aria-pressed', 'false');
    await user.click(toggle);
    expect(useReviewSelectionStore.getState().issueMode).toBe(true);

    const comment = screen.getByTestId('memo-comment-textarea') as HTMLTextAreaElement;
    expect(comment.maxLength).toBe(200);
    await user.type(comment, '의견');
    expect(useReviewSelectionStore.getState().reviewComment).toBe('의견');
  });
});

/**
 * ★대칭 가드 — 위에서 걷어낸 것은 «중복 표시»이지 «표시» 자체가 아니다.
 *
 * 위 describe 는 「객체 탭 목록에 없다」만 단언한다. 그것만으로는 <b>서버 등록 이슈가 어디에서도
 * 보이지 않게 되는 회귀</b>를 잡지 못한다 — 「중복을 없앴다」와 「소실시켰다」가 그 단언에서
 * 구분되지 않기 때문이다. 사양이 그 열람 책임을 「이슈」 탭에 <b>단독으로</b> 맡겼으므로, 그
 * 탭에서 실제로 그려진다는 사실을 여기서 함께 고정한다.
 *
 * ⚠ 이슈 탭 컴포넌트는 <b>이번 변경 대상이 아니다</b> — 이 블록은 단언만 더한다.
 */
describe('IssueThreadPanel — 서버 등록 이슈의 단독 열람처', () => {
  const RAW_SN = 1;

  /** 위 SERVER_ISSUE_PROPS 와 <b>같은 본문</b>을 쓴다 — 두 가드가 같은 값을 두고 반대편을 말한다. */
  const SERVER_THREADS: IssueThread[] = [
    {
      issueSn: 1,
      issueTypeCd: 'REJECTION',
      issueSttsCd: 'OPEN',
      srcSn: 3,
      reason: '서버에 등록된 반려 사유 본문',
      reportedUserNo: 'rev01',
      regDt: '2026-08-27T10:00:00Z',
      comments: [],
    },
    {
      issueSn: 2,
      issueTypeCd: 'INQUIRY',
      issueSttsCd: 'OPEN',
      srcSn: 5,
      reason: '서버에 등록된 문의 본문',
      reportedUserNo: 'wkr01',
      regDt: '2026-08-27T11:00:00Z',
      comments: [],
    },
  ];

  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    mock.onGet(`/videos/${RAW_SN}/issues`).reply(200, {
      success: true,
      data: SERVER_THREADS,
      message: null,
      errorCode: null,
    });
  });

  afterEach(() => {
    // ⚠ 렌더된 트리를 먼저 걷어낸 뒤 목을 되돌린다 — 순서가 반대면 언마운트 중에 살아 있는
    //   쿼리가 목 없는 클라이언트로 재요청해 잡음 실패가 난다.
    cleanup();
    mock.restore();
  });

  it('★서버에_등록된_이슈는_이슈_탭에_그대로_그려진다_중복만_없앤_것이지_소실이_아니다', async () => {
    renderWithProviders(<IssueThreadPanel rawSn={RAW_SN} mode="reviewer" />);

    const list = await screen.findByTestId('issue-thread-list');
    expect(within(list).getByText('서버에 등록된 반려 사유 본문')).toBeInTheDocument();
    expect(within(list).getByText('서버에 등록된 문의 본문')).toBeInTheDocument();
    // 빈 상태가 아니다 — 목록이 통째로 사라지는 회귀를 함께 막는다.
    expect(screen.queryByTestId('issue-thread-empty')).toBeNull();
  });

  it('반려_이력과_문의가_모두_남는다_한_유형만_걸러내지_않는다', async () => {
    // 객체 탭에서 걷어낸 축은 «출처(서버 등록분)»이지 «유형»이 아니다. 어느 한 유형만 남기는
    // 회귀가 나면 반대편 유형의 이력이 화면 어디에도 없게 된다.
    renderWithProviders(<IssueThreadPanel rawSn={RAW_SN} mode="reviewer" />);

    await waitFor(() =>
      expect(screen.getByTestId('issue-thread-card-1')).toBeInTheDocument(),
    );
    expect(screen.getByTestId('issue-type-badge-1')).toHaveTextContent('반려');
    expect(screen.getByTestId('issue-thread-card-2')).toBeInTheDocument();
    expect(screen.getByTestId('issue-type-badge-2')).toHaveTextContent(
      '검수자 확인 요청',
    );
  });
});
