// SCR-REVIEW-002 Phase 6 — ReviewMemoPanel 테스트.

import { beforeEach, describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '@/test/renderWithProviders';

import { ReviewMemoPanel } from '../components/ReviewMemoPanel';
import { useReviewSelectionStore } from '../store/useReviewSelectionStore';
import type { ReviewIssue } from '../types';

const issue1: ReviewIssue = {
  id: 1,
  frameId: 3,
  description: 'BE 등록 이슈 1',
  createdAt: '2026-05-07T10:00:00Z',
};

const issue2: ReviewIssue = {
  id: 2,
  frameId: 5,
  description: 'BE 등록 이슈 2',
  createdAt: '2026-05-07T11:00:00Z',
};

describe('ReviewMemoPanel', () => {
  beforeEach(() => {
    // store 초기화 — 케이스 독립성 보장.
    useReviewSelectionStore.getState().clear();
    useReviewSelectionStore.getState().setCurrentFrameIdx(0);
  });

  it('ReviewMemoPanel_이슈_추가_모드_버튼_active_표시', async () => {
    const user = userEvent.setup();
    renderWithProviders(<ReviewMemoPanel videoId={1} issues={[]} />);

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
    renderWithProviders(<ReviewMemoPanel videoId={1} issues={[]} />);

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

  it('ReviewMemoPanel_빈_이슈_안내문', () => {
    renderWithProviders(<ReviewMemoPanel videoId={1} issues={[]} />);

    expect(screen.getByTestId('memo-issues-empty')).toBeInTheDocument();
    expect(screen.getByText('등록된 이슈가 없습니다')).toBeInTheDocument();
  });

  it('ReviewMemoPanel_BE_이슈와_pending_이슈_모두_렌더', () => {
    // pending 이슈 1건 추가.
    useReviewSelectionStore.getState().addPendingIssue('로컬 이슈 텍스트', 99);

    renderWithProviders(
      <ReviewMemoPanel videoId={1} issues={[issue1, issue2]} />,
    );

    // BE 이슈 2건
    expect(screen.getByTestId('memo-issue-be-1')).toBeInTheDocument();
    expect(screen.getByTestId('memo-issue-be-2')).toBeInTheDocument();
    expect(screen.getByText('BE 등록 이슈 1')).toBeInTheDocument();
    expect(screen.getByText('BE 등록 이슈 2')).toBeInTheDocument();

    // pending 이슈 1건 (textarea 안에 value 로 렌더됨)
    const pendingTextarea = screen.getByTestId(
      'memo-issue-pending-text-0',
    ) as HTMLTextAreaElement;
    expect(pendingTextarea).toBeInTheDocument();
    expect(pendingTextarea.value).toBe('로컬 이슈 텍스트');

    // 합계 카운트 (3건)
    expect(screen.getByText(/이슈 목록 \(3건\)/)).toBeInTheDocument();
  });

  it('ReviewMemoPanel_첨부파일_placeholder_표시', () => {
    renderWithProviders(<ReviewMemoPanel videoId={1} issues={[]} />);

    expect(screen.getByTestId('memo-attach-placeholder')).toBeInTheDocument();
    expect(screen.getByText('첨부파일 기능 준비 중')).toBeInTheDocument();
    // 업로드 버튼 disabled
    const uploadBtn = screen.getByRole('button', { name: /파일 첨부/ });
    expect(uploadBtn).toBeDisabled();
  });

  it('ReviewMemoPanel_pendingIssue_텍스트_업데이트', async () => {
    useReviewSelectionStore.getState().addPendingIssue('초기', 1);

    const user = userEvent.setup();
    renderWithProviders(<ReviewMemoPanel videoId={1} issues={[]} />);

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
    renderWithProviders(<ReviewMemoPanel videoId={1} issues={[]} />);

    expect(useReviewSelectionStore.getState().pendingIssues).toHaveLength(2);

    const removeBtn = screen.getByTestId('memo-issue-pending-remove-0');
    await user.click(removeBtn);

    expect(useReviewSelectionStore.getState().pendingIssues).toHaveLength(1);
    expect(useReviewSelectionStore.getState().pendingIssues[0].text).toBe('남길 이슈');
  });
});
