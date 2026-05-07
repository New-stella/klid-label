import { screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import { useLabelStore } from '@/stores/useLabelStore';
import { renderWithProviders } from '@/test/renderWithProviders';

import { ObjectAttributePanel } from '../components/ObjectAttributePanel';
import type { Label } from '../types';

const labelLowConf: Label = {
  id: 'a',
  frameNo: 1,
  classId: 3,
  className: 'pedestrian',
  source: 'AUTO_YOLO',
  confidence: 0.4,
  shape: { type: 'BBOX', left: 0, top: 0, right: 100, bottom: 50 },
};

describe('ObjectAttributePanel', () => {
  it('선택된_라벨이_없으면_안내_문구', () => {
    useLabelStore.getState().reset();
    renderWithProviders(<ObjectAttributePanel labels={[]} />);
    expect(screen.getByText(/선택된 객체가 없습니다/)).toBeInTheDocument();
  });

  it('신뢰도_0_5_미만_라벨_경고_뱃지', () => {
    useLabelStore.getState().reset();
    useLabelStore.getState().setLabels([labelLowConf]);
    useLabelStore.getState().selectLabel('a');
    renderWithProviders(<ObjectAttributePanel labels={[labelLowConf]} />);
    expect(screen.getByRole('status')).toHaveTextContent('낮은 신뢰도');
  });

  it('BBOX_좌표_표시', () => {
    useLabelStore.getState().reset();
    useLabelStore.getState().setLabels([labelLowConf]);
    useLabelStore.getState().selectLabel('a');
    renderWithProviders(<ObjectAttributePanel labels={[labelLowConf]} />);
    // Phase 6: 좌표는 X/Y/W/H input 필드로 편집 가능 — 각 input의 value 확인
    const xInput = screen.getByLabelText(/X 좌표/i) as HTMLInputElement;
    const yInput = screen.getByLabelText(/Y 좌표/i) as HTMLInputElement;
    const wInput = screen.getByLabelText(/W 우측/i) as HTMLInputElement;
    const hInput = screen.getByLabelText(/H 하단/i) as HTMLInputElement;
    expect(xInput.value).toBe('0');
    expect(yInput.value).toBe('0');
    expect(wInput.value).toBe('100');
    expect(hInput.value).toBe('50');
  });
});
