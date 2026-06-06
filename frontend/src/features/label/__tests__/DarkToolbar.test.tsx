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
