import { fireEvent, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it } from 'vitest';

import { useLabelStore } from '@/stores/useLabelStore';
import { renderWithProviders } from '@/test/renderWithProviders';

import { ToolBar } from '../components/ToolBar';
import { ToolType } from '../types';

describe('ToolBar', () => {
  beforeEach(() => {
    useLabelStore.getState().reset();
  });

  it('도구_버튼_클릭_시_activeTool_변경', () => {
    renderWithProviders(<ToolBar />);
    const bbox = screen.getByRole('button', { name: /BBox \(B\)/i });
    fireEvent.click(bbox);
    expect(useLabelStore.getState().activeTool).toBe(ToolType.BBOX);
  });

  it('SAM분할_버튼_클릭_시_SAM_SEGMENT_활성', () => {
    renderWithProviders(<ToolBar />);
    const seg = screen.getByRole('button', { name: /SAM분할 \(G\)/i });
    fireEvent.click(seg);
    expect(useLabelStore.getState().activeTool).toBe(ToolType.SAM_SEGMENT);
  });

  it('aria-pressed_활성_도구_표시', () => {
    useLabelStore.getState().setActiveTool(ToolType.POLYGON);
    renderWithProviders(<ToolBar />);
    const poly = screen.getByRole('button', { name: /Polygon \(P\)/i });
    expect(poly.getAttribute('aria-pressed')).toBe('true');
  });
});
