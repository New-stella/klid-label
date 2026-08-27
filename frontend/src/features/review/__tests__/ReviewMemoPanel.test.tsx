// SCR-REVIEW-002 Phase 6 — ReviewMemoPanel 테스트.

import { beforeEach, describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '@/test/renderWithProviders';

import { ReviewMemoPanel } from '../components/ReviewMemoPanel';
import { useReviewSelectionStore } from '../store/useReviewSelectionStore';

describe('ReviewMemoPanel', () => {
  beforeEach(() => {
    // store 초기화 — 케이스 독립성 보장.
    useReviewSelectionStore.getState().clear();
    useReviewSelectionStore.getState().setCurrentFrameIdx(0);
  });

  it('ReviewMemoPanel_이슈_추가_모드_버튼_active_표시', async () => {
    const user = userEvent.setup();
    renderWithProviders(<ReviewMemoPanel videoId={1} />);

    const toggle = screen.getByTestId('issue-mode-toggle');
    // 초기 상태: aria-pressed false
    expect(toggle).toHaveAttribute('aria-pressed', 'false');

    await user.click(toggle);

    // 클릭 후 active
    expect(toggle).toHaveAttribute('aria-pressed', 'true');
    expect(useReviewSelectionStore.getState().issueMode).toBe(true);
  });

  it('ReviewMemoPanel_검수의견_200자_초과_막힘', async () => {
    const user = userEvent.setup();
    renderWithProviders(<ReviewMemoPanel videoId={1} />);

    const textarea = screen.getByTestId('memo-comment-textarea') as HTMLTextAreaElement;
    // maxLength 속성으로 브라우저 레벨에서 입력 차단.
    expect(textarea.maxLength).toBe(200);

    // store 에 직접 200자 초과 값을 set 한 후 컴포넌트는 textarea.maxLength 로 보호되는 것은
    // 사용자 입력 경로에서 검증. type() 으로 짧은 텍스트 입력 후 길이가 store 와 동기화되는지 확인.
    await user.type(textarea, '의견 테스트');
    expect(useReviewSelectionStore.getState().reviewComment).toBe('의견 테스트');

    // 카운터 표시.
    expect(screen.getByTestId('memo-comment-counter')).toHaveTextContent(
      `${'의견 테스트'.length}/200`,
    );
  });

  it('ReviewMemoPanel_빈_초안_안내문', () => {
    renderWithProviders(<ReviewMemoPanel videoId={1} />);

    expect(screen.getByTestId('memo-issues-empty')).toBeInTheDocument();
    expect(screen.getByText('첨부할 지적이 없습니다')).toBeInTheDocument();
  });

  // [폐기] ReviewMemoPanel_BE_이슈와_pending_이슈_모두_렌더
  //   서버 등록 이슈를 이 목록에 함께 그리던 동작이 폐기됐다 — 반려하면 그 사유가 스레드가 되어
  //   되돌아와 「이슈」 탭과 이 목록에 동시에 보였다. 서버 등록 이슈의 열람·댓글·해소는 「이슈」
  //   탭(IssueThreadPanel)이 단독으로 담당한다. 대체 가드: ReviewMemoPanelDraftOnly.test.tsx.
  it('ReviewMemoPanel_pending_이슈만_렌더되고_건수도_초안만_센다', () => {
    useReviewSelectionStore.getState().addPendingIssue('로컬 이슈 텍스트', 99);

    renderWithProviders(<ReviewMemoPanel videoId={1} />);

    const pendingTextarea = screen.getByTestId(
      'memo-issue-pending-text-0',
    ) as HTMLTextAreaElement;
    expect(pendingTextarea).toBeInTheDocument();
    expect(pendingTextarea.value).toBe('로컬 이슈 텍스트');

    expect(screen.getByText(/반려 사유에 첨부할 지적 \(1건\)/)).toBeInTheDocument();
  });

  it('ReviewMemoPanel_첨부파일_placeholder_표시', () => {
    renderWithProviders(<ReviewMemoPanel videoId={1} />);

    expect(screen.getByTestId('memo-attach-placeholder')).toBeInTheDocument();
    expect(screen.getByText('첨부파일 기능 준비 중')).toBeInTheDocument();
    // 업로드 버튼 disabled
    const uploadBtn = screen.getByRole('button', { name: /파일 첨부/ });
    expect(uploadBtn).toBeDisabled();
  });

  it('ReviewMemoPanel_pendingIssue_텍스트_업데이트', async () => {
    useReviewSelectionStore.getState().addPendingIssue('초기', 1);

    const user = userEvent.setup();
    renderWithProviders(<ReviewMemoPanel videoId={1} />);

    const ta = screen.getByTestId('memo-issue-pending-text-0') as HTMLTextAreaElement;
    expect(ta.value).toBe('초기');

    await user.clear(ta);
    await user.type(ta, '수정');

    expect(useReviewSelectionStore.getState().pendingIssues[0].text).toBe('수정');
  });

  it('ReviewMemoPanel_pendingIssue_삭제', async () => {
    useReviewSelectionStore.getState().addPendingIssue('삭제할 이슈', 1);
    useReviewSelectionStore.getState().addPendingIssue('남길 이슈', 2);

    const user = userEvent.setup();
    renderWithProviders(<ReviewMemoPanel videoId={1} />);

    expect(useReviewSelectionStore.getState().pendingIssues).toHaveLength(2);

    const removeBtn = screen.getByTestId('memo-issue-pending-remove-0');
    await user.click(removeBtn);

    expect(useReviewSelectionStore.getState().pendingIssues).toHaveLength(1);
    expect(useReviewSelectionStore.getState().pendingIssues[0].text).toBe('남길 이슈');
  });
});
