// ObjectClassTree — 오토 라벨/수동 라벨 편집·삭제 동일화 확인 (Phase 5 / B-1, AC8).
//
// 오토라벨(source=AUTO_YOLO/AUTO_SAM2)도 수동(MANUAL)과 동일하게 선택/삭제가 가능해야 한다.
// (출처 아이콘·배지는 유지하되 편집 동작은 동일)

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, screen } from '@testing-library/react';

import { ObjectClassTree } from '@/features/label/components/ObjectClassTree';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useLabelStore } from '@/stores/useLabelStore';
import type { Label } from '@/features/label/types';

function autoLabel(): Label {
  return {
    id: 'auto-1',
    serverId: 100,
    frameNo: 1,
    classId: 1,
    className: 'person',
    source: 'AUTO_YOLO',
    confidence: 0.9,
    shape: { type: 'BBOX', left: 0, top: 0, right: 10, bottom: 10 },
  };
}

function manualLabel(): Label {
  return {
    id: 'man-1',
    serverId: 200,
    frameNo: 1,
    classId: 1,
    className: 'person',
    source: 'MANUAL',
    shape: { type: 'BBOX', left: 20, top: 20, right: 40, bottom: 40 },
  };
}

describe('ObjectClassTree — 오토/수동 라벨 편집 동일화', () => {
  beforeEach(() => {
    // 잠금/숨김 없는 초기 상태로 리셋.
    useLabelStore.setState({
      lockedLabelIds: new Set<string>(),
      hiddenLabelIds: new Set<string>(),
      selectedLabelId: null,
    });
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('오토_라벨도_수동과_동일하게_선택·삭제된다', () => {
    const selectSpy = vi.fn();
    const removeSpy = vi.fn();
    useLabelStore.setState({ selectLabel: selectSpy, removeLabel: removeSpy });

    renderWithProviders(
      <ObjectClassTree labels={[autoLabel(), manualLabel()]} />,
    );

    // 오토 라벨 선택 — 수동과 동일한 선택 버튼 경로 (displayName: person → '사람')
    fireEvent.click(screen.getByRole('button', { name: '사람 #1 선택' }));
    expect(selectSpy).toHaveBeenCalledWith('auto-1');

    // 삭제 버튼은 오토/수동 모두 활성 (동일 삭제 경로)
    const deleteButtons = screen.getAllByRole('button', { name: '객체 삭제' });
    expect(deleteButtons).toHaveLength(2);
    deleteButtons.forEach((b) => expect(b).toBeEnabled());

    // 오토 라벨 삭제 실행 → removeLabel 이 auto id 로 호출
    fireEvent.click(deleteButtons[0]);
    expect(removeSpy).toHaveBeenCalledWith('auto-1');
  });
});
