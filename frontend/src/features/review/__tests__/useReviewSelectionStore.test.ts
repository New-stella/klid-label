// SCR-REVIEW-002 Phase 4 — 객체 선택/호버 store 테스트.

import { beforeEach, describe, expect, it } from 'vitest';

import { useReviewSelectionStore } from '../store/useReviewSelectionStore';

describe('useReviewSelectionStore', () => {
  beforeEach(() => {
    // 각 케이스 독립성 보장 — 테스트 간 store 초기화.
    useReviewSelectionStore.getState().clear();
    useReviewSelectionStore.getState().setCurrentFrameIdx(0);
  });

  it('useReviewSelectionStore_초기상태_null', () => {
    const state = useReviewSelectionStore.getState();
    expect(state.selectedLabelId).toBeNull();
    expect(state.hoverLabelId).toBeNull();
    expect(state.currentFrameIdx).toBe(0);
  });

  it('setSelected_시_hover_초기화', () => {
    const { setHover, setSelected } = useReviewSelectionStore.getState();
    setHover(7);
    expect(useReviewSelectionStore.getState().hoverLabelId).toBe(7);

    setSelected(3);
    const after = useReviewSelectionStore.getState();
    expect(after.selectedLabelId).toBe(3);
    expect(after.hoverLabelId).toBeNull();
  });

  it('setHover_시_selected_유지', () => {
    const { setHover, setSelected } = useReviewSelectionStore.getState();
    setSelected(10);
    setHover(20);
    const s = useReviewSelectionStore.getState();
    expect(s.selectedLabelId).toBe(10);
    expect(s.hoverLabelId).toBe(20);
  });

  it('clear_시_둘다_null', () => {
    const { setSelected, setHover, clear } = useReviewSelectionStore.getState();
    setSelected(1);
    setHover(2);
    clear();
    const s = useReviewSelectionStore.getState();
    expect(s.selectedLabelId).toBeNull();
    expect(s.hoverLabelId).toBeNull();
  });

  it('setSelected_null_명시적_해제', () => {
    const { setSelected } = useReviewSelectionStore.getState();
    setSelected(5);
    setSelected(null);
    expect(useReviewSelectionStore.getState().selectedLabelId).toBeNull();
  });

  it('setCurrentFrameIdx_시_selected_hover_초기화', () => {
    const { setSelected, setHover, setCurrentFrameIdx } =
      useReviewSelectionStore.getState();
    setSelected(11);
    setHover(22);
    expect(useReviewSelectionStore.getState().selectedLabelId).toBe(11);

    setCurrentFrameIdx(3);
    const after = useReviewSelectionStore.getState();
    expect(after.currentFrameIdx).toBe(3);
    expect(after.selectedLabelId).toBeNull();
    expect(after.hoverLabelId).toBeNull();
  });

  it('store_issueMode_토글', () => {
    const { toggleIssueMode } = useReviewSelectionStore.getState();
    expect(useReviewSelectionStore.getState().issueMode).toBe(false);
    toggleIssueMode();
    expect(useReviewSelectionStore.getState().issueMode).toBe(true);
    toggleIssueMode();
    expect(useReviewSelectionStore.getState().issueMode).toBe(false);
  });

  it('store_reviewComment_업데이트', () => {
    const { setReviewComment } = useReviewSelectionStore.getState();
    expect(useReviewSelectionStore.getState().reviewComment).toBe('');
    setReviewComment('전반적으로 양호합니다');
    expect(useReviewSelectionStore.getState().reviewComment).toBe(
      '전반적으로 양호합니다',
    );
  });

  it('store_pendingIssues_추가_삭제_업데이트', () => {
    const {
      addPendingIssue,
      updatePendingIssue,
      removePendingIssue,
      clearPendingIssues,
    } = useReviewSelectionStore.getState();

    // 추가
    addPendingIssue('첫 번째 이슈', 1);
    addPendingIssue('두 번째 이슈', null);
    let s = useReviewSelectionStore.getState();
    expect(s.pendingIssues).toHaveLength(2);
    expect(s.pendingIssues[0]).toMatchObject({ text: '첫 번째 이슈', labelId: 1 });
    expect(s.pendingIssues[1]).toMatchObject({
      text: '두 번째 이슈',
      labelId: null,
    });
    expect(typeof s.pendingIssues[0].ts).toBe('string');

    // 업데이트
    updatePendingIssue(0, '수정된 이슈');
    s = useReviewSelectionStore.getState();
    expect(s.pendingIssues[0].text).toBe('수정된 이슈');
    expect(s.pendingIssues[0].labelId).toBe(1); // labelId 보존

    // 삭제
    removePendingIssue(0);
    s = useReviewSelectionStore.getState();
    expect(s.pendingIssues).toHaveLength(1);
    expect(s.pendingIssues[0].text).toBe('두 번째 이슈');

    // 일괄 삭제
    clearPendingIssues();
    expect(useReviewSelectionStore.getState().pendingIssues).toHaveLength(0);
  });

  it('store_clear_시_pending_포함_초기화', () => {
    const {
      setSelected,
      setHover,
      toggleIssueMode,
      setReviewComment,
      addPendingIssue,
      clear,
    } = useReviewSelectionStore.getState();

    setSelected(1);
    setHover(2);
    toggleIssueMode();
    setReviewComment('의견');
    addPendingIssue('이슈', 3);

    clear();

    const s = useReviewSelectionStore.getState();
    expect(s.selectedLabelId).toBeNull();
    expect(s.hoverLabelId).toBeNull();
    expect(s.issueMode).toBe(false);
    expect(s.reviewComment).toBe('');
    expect(s.pendingIssues).toEqual([]);
  });

  it('setCurrentFrameIdx_pendingIssues_보존', () => {
    const { addPendingIssue, setReviewComment, setCurrentFrameIdx } =
      useReviewSelectionStore.getState();
    addPendingIssue('영상 전체 이슈', 1);
    setReviewComment('의견');

    setCurrentFrameIdx(5);

    const s = useReviewSelectionStore.getState();
    // 프레임 전환에도 pendingIssues / reviewComment 는 보존 (영상 전체 단위).
    expect(s.pendingIssues).toHaveLength(1);
    expect(s.pendingIssues[0].text).toBe('영상 전체 이슈');
    expect(s.reviewComment).toBe('의견');
    expect(s.currentFrameIdx).toBe(5);
    // selected/hover 만 초기화.
    expect(s.selectedLabelId).toBeNull();
    expect(s.hoverLabelId).toBeNull();
  });
});
