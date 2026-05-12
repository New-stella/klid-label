// SCR-REVIEW-002 Phase 4·5·6 — 검수 화면의 객체 선택/호버 + 현재 프레임 인덱스 + 메모/이슈 모드 store.
//
// 양방향 동기화 (캔버스 ↔ 트리) 를 위해 라벨 id 를 공유한다.
// - selectedLabelId: 클릭으로 강조된 라벨 id (캔버스 + 트리 양쪽에서 강조).
// - hoverLabelId: 마우스 hover (선택보다 가벼운 indicator).
// - currentFrameIdx (Phase 5): 타임라인에서 동기화되는 현재 프레임 인덱스 (0-base).
// - issueMode (Phase 6): 이슈 추가 모드 토글 — 캔버스 cursor + 라벨 클릭 시 pending 이슈 누적.
// - reviewComment (Phase 6): 검수 전체 의견 텍스트 (반려 시 reason 에 포함).
// - pendingIssues (Phase 6): 이슈 추가 모드에서 누적된 로컬 이슈 (BE 미연결 — V1.x 보조).
//
// 메모:
// - selected 가 설정되면 hover 는 reset (시각 충돌 회피).
// - frame 전환 시 selected/hover 는 자동 reset — 이전 프레임의 라벨 id 가
//   새 프레임에서 우연히 다른 라벨에 매칭되는 회귀를 차단.
// - frame 전환 시 pendingIssues 는 **보존** — 영상 전체 단위로 누적된다.
// - 페이지 언마운트 시 ReviewPage 가 `clear()` 로 메모/이슈도 함께 reset.
//
// Zustand selector 패턴: 컴포넌트는 필요한 값만 구독 (전체 store 구독 금지 — rules/state-management.md).

import { create } from 'zustand';

export interface PendingIssue {
  labelId: number | null;
  text: string;
  ts: string;
}

export interface ReviewSelectionState {
  selectedLabelId: number | null;
  hoverLabelId: number | null;
  currentFrameIdx: number;
  issueMode: boolean;
  reviewComment: string;
  pendingIssues: PendingIssue[];
  setSelected: (id: number | null) => void;
  setHover: (id: number | null) => void;
  setCurrentFrameIdx: (idx: number) => void;
  toggleIssueMode: () => void;
  setReviewComment: (s: string) => void;
  addPendingIssue: (text: string, labelId?: number | null) => void;
  removePendingIssue: (idx: number) => void;
  updatePendingIssue: (idx: number, text: string) => void;
  clearPendingIssues: () => void;
  clear: () => void;
}

export const useReviewSelectionStore = create<ReviewSelectionState>((set) => ({
  selectedLabelId: null,
  hoverLabelId: null,
  currentFrameIdx: 0,
  issueMode: false,
  reviewComment: '',
  pendingIssues: [],
  setSelected: (id) => set({ selectedLabelId: id, hoverLabelId: null }),
  setHover: (id) => set({ hoverLabelId: id }),
  // 프레임 전환 시 selected/hover 자동 reset (잔존 매칭 회피).
  // pendingIssues / reviewComment / issueMode 는 보존 (영상 전체 단위 누적).
  setCurrentFrameIdx: (idx) =>
    set({
      currentFrameIdx: idx,
      selectedLabelId: null,
      hoverLabelId: null,
    }),
  toggleIssueMode: () => set((s) => ({ issueMode: !s.issueMode })),
  setReviewComment: (s) => set({ reviewComment: s }),
  addPendingIssue: (text, labelId = null) =>
    set((s) => ({
      pendingIssues: [
        ...s.pendingIssues,
        { labelId, text, ts: new Date().toISOString() },
      ],
    })),
  removePendingIssue: (idx) =>
    set((s) => ({
      pendingIssues: s.pendingIssues.filter((_, i) => i !== idx),
    })),
  updatePendingIssue: (idx, text) =>
    set((s) => ({
      pendingIssues: s.pendingIssues.map((p, i) =>
        i === idx ? { ...p, text } : p,
      ),
    })),
  clearPendingIssues: () => set({ pendingIssues: [] }),
  clear: () =>
    set({
      selectedLabelId: null,
      hoverLabelId: null,
      issueMode: false,
      reviewComment: '',
      pendingIssues: [],
    }),
}));
