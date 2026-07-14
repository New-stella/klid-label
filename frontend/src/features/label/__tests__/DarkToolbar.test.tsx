// ISSUE-2 — DarkToolbar SAM2 분할/추적 도구 버튼 노출 + 선택 동작 검증.

import { fireEvent, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { useLabelStore } from '@/stores/useLabelStore';
import { renderWithProviders } from '@/test/renderWithProviders';

import { DarkToolbar } from '../components/DarkToolbar';
import { ToolType } from '../types';

describe('DarkToolbar — SAM2 도구', () => {
  beforeEach(() => {
    useLabelStore.getState().reset();
  });

  it('SAM_분할과_SAM_추적_버튼이_렌더된다', () => {
    renderWithProviders(<DarkToolbar onSave={vi.fn()} />);
    expect(screen.getByRole('button', { name: 'SAM 분할' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'SAM 추적' })).toBeInTheDocument();
  });

  it('SAM_분할_클릭_시_activeTool이_SAM_SEGMENT로_전환된다', () => {
    renderWithProviders(<DarkToolbar onSave={vi.fn()} />);
    fireEvent.click(screen.getByRole('button', { name: 'SAM 분할' }));
    expect(useLabelStore.getState().activeTool).toBe(ToolType.SAM_SEGMENT);
  });

  it('SAM_추적_클릭_시_activeTool이_TRACK으로_전환된다', () => {
    renderWithProviders(<DarkToolbar onSave={vi.fn()} />);
    fireEvent.click(screen.getByRole('button', { name: 'SAM 추적' }));
    expect(useLabelStore.getState().activeTool).toBe(ToolType.TRACK);
  });
});

describe('DarkToolbar — 키포인트 도구', () => {
  beforeEach(() => {
    useLabelStore.getState().reset();
  });

  it('키포인트_버튼이_렌더된다', () => {
    renderWithProviders(<DarkToolbar onSave={vi.fn()} />);
    expect(screen.getByRole('button', { name: '키포인트' })).toBeInTheDocument();
  });

  it('키포인트_클릭_시_activeTool이_KEYPOINT로_전환된다', () => {
    renderWithProviders(<DarkToolbar onSave={vi.fn()} />);
    fireEvent.click(screen.getByRole('button', { name: '키포인트' }));
    expect(useLabelStore.getState().activeTool).toBe(ToolType.KEYPOINT);
  });

  it('툴바_portalMode에서_키포인트_숨김', () => {
    renderWithProviders(<DarkToolbar onSave={vi.fn()} portalMode />);
    expect(screen.queryByRole('button', { name: '키포인트' })).not.toBeInTheDocument();
    // SAM 도구도 포털에서 숨김(회귀 가드)
    expect(screen.queryByRole('button', { name: 'SAM 분할' })).not.toBeInTheDocument();
  });
});

describe('DarkToolbar — YOLO 오토라벨', () => {
  beforeEach(() => {
    useLabelStore.getState().reset();
  });

  it('onAutolabel_핸들러_지정시_YOLO_버튼_렌더_및_클릭_호출', () => {
    const onAutolabel = vi.fn();
    renderWithProviders(<DarkToolbar onSave={vi.fn()} onAutolabel={onAutolabel} />);
    const btn = screen.getByRole('button', { name: 'YOLO 오토라벨' });
    expect(btn).toBeInTheDocument();
    fireEvent.click(btn);
    expect(onAutolabel).toHaveBeenCalledTimes(1);
  });

  it('onAutolabel_미지정시_YOLO_버튼_미노출', () => {
    renderWithProviders(<DarkToolbar onSave={vi.fn()} />);
    expect(screen.queryByRole('button', { name: 'YOLO 오토라벨' })).not.toBeInTheDocument();
  });

  it('진행중이면_YOLO_버튼_비활성화되어_중복클릭_방지', () => {
    const onAutolabel = vi.fn();
    renderWithProviders(
      <DarkToolbar onSave={vi.fn()} onAutolabel={onAutolabel} isAutolabeling />,
    );
    const btn = screen.getByRole('button', { name: 'YOLO 오토라벨' });
    expect(btn).toBeDisabled();
    expect(btn).toHaveAttribute('aria-busy', 'true');
    fireEvent.click(btn);
    expect(onAutolabel).not.toHaveBeenCalled();
  });

  it('portalMode에서_YOLO_오토라벨_버튼_숨김_ADR_013', () => {
    renderWithProviders(
      <DarkToolbar onSave={vi.fn()} onAutolabel={vi.fn()} portalMode />,
    );
    expect(screen.queryByRole('button', { name: 'YOLO 오토라벨' })).not.toBeInTheDocument();
  });
});
