// Undo/Redo 도구바 테스트.

import { fireEvent, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it } from 'vitest';

import { useLabelStore } from '@/stores/useLabelStore';
import { renderWithProviders } from '@/test/renderWithProviders';

import { UndoRedoToolbar } from '../components/UndoRedoToolbar';
import type { Label } from '../types';

const sample: Label = {
  id: 'a',
  frameNo: 1,
  classId: 1,
  className: 'car',
  source: 'MANUAL',
  shape: { type: 'BBOX', left: 0, top: 0, right: 10, bottom: 10 },
};

describe('UndoRedoToolbar', () => {
  beforeEach(() => {
    useLabelStore.getState().reset();
  });

  it('Redo_빈_스택일_때_버튼_disabled', () => {
    renderWithProviders(<UndoRedoToolbar />);
    const undoBtn = screen.getByRole('button', { name: /실행 취소|Undo/i });
    const redoBtn = screen.getByRole('button', { name: /다시 실행|Redo/i });
    expect(undoBtn).toBeDisabled();
    expect(redoBtn).toBeDisabled();
  });

  it('addLabel_후_Undo_버튼_활성화', () => {
    useLabelStore.getState().addLabel(sample);
    renderWithProviders(<UndoRedoToolbar />);
    const undoBtn = screen.getByRole('button', { name: /실행 취소|Undo/i });
    expect(undoBtn).not.toBeDisabled();
  });

  it('Undo_버튼_클릭_시_라벨_롤백', () => {
    useLabelStore.getState().addLabel(sample);
    expect(useLabelStore.getState().labels).toHaveLength(1);
    renderWithProviders(<UndoRedoToolbar />);
    const undoBtn = screen.getByRole('button', { name: /실행 취소|Undo/i });
    fireEvent.click(undoBtn);
    expect(useLabelStore.getState().labels).toHaveLength(0);
  });

  it('Undo_스택_50개_제한', () => {
    // 60번 addLabel
    for (let i = 0; i < 60; i++) {
      useLabelStore.getState().addLabel({ ...sample, id: `id-${i}` });
    }
    expect(useLabelStore.getState().undoStack.length).toBeLessThanOrEqual(50);
  });
});
