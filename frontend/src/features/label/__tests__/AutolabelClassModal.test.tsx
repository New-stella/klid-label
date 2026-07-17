// Phase 4 — YOLO 오토라벨 클래스 선택 팝업 테스트 (R3 AC3).

import { fireEvent, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

import { renderWithProviders } from '@/test/renderWithProviders';

import { AutolabelClassModal } from '../components/AutolabelClassModal';

describe('AutolabelClassModal', () => {
  it('열리면_YOLO_클래스_목록_렌더', () => {
    renderWithProviders(
      <AutolabelClassModal open onClose={vi.fn()} onConfirm={vi.fn()} />,
    );
    expect(screen.getByRole('checkbox', { name: '사람' })).toBeInTheDocument();
    expect(screen.getByRole('checkbox', { name: '자동차' })).toBeInTheDocument();
  });

  it('클래스_선택후_실행시_onConfirm에_선택_classIds_전달', () => {
    const onConfirm = vi.fn();
    renderWithProviders(
      <AutolabelClassModal open onClose={vi.fn()} onConfirm={onConfirm} />,
    );
    fireEvent.click(screen.getByRole('checkbox', { name: '사람' }));
    fireEvent.click(screen.getByRole('checkbox', { name: '트럭' }));
    fireEvent.click(screen.getByRole('button', { name: '실행' }));

    // 정의 순서(person → truck) 안정화된 COCO 영문 id.
    expect(onConfirm).toHaveBeenCalledWith(['person', 'truck']);
  });

  it('미선택(전체)시_실행하면_빈배열_전달', () => {
    const onConfirm = vi.fn();
    renderWithProviders(
      <AutolabelClassModal open onClose={vi.fn()} onConfirm={onConfirm} />,
    );
    fireEvent.click(screen.getByRole('button', { name: '실행' }));
    expect(onConfirm).toHaveBeenCalledWith([]);
  });

  it('취소시_onClose_호출되고_onConfirm_미호출', () => {
    const onConfirm = vi.fn();
    const onClose = vi.fn();
    renderWithProviders(
      <AutolabelClassModal open onClose={onClose} onConfirm={onConfirm} />,
    );
    fireEvent.click(screen.getByRole('button', { name: '취소' }));
    expect(onClose).toHaveBeenCalledTimes(1);
    expect(onConfirm).not.toHaveBeenCalled();
  });
});
