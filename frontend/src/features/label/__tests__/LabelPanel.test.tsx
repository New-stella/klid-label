import { fireEvent, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import { useLabelStore } from '@/stores/useLabelStore';
import { renderWithProviders } from '@/test/renderWithProviders';

import { LabelPanel } from '../components/LabelPanel';
import type { Label } from '../types';

const labels: Label[] = [
  {
    id: 'a-12345',
    frameNo: 1,
    classId: 1,
    className: 'car',
    source: 'AUTO_YOLO',
    confidence: 0.9,
    shape: { type: 'BBOX', left: 0, top: 0, right: 10, bottom: 10 },
  },
  {
    id: 'b-67890',
    frameNo: 1,
    classId: 1,
    className: 'car',
    source: 'MANUAL',
    shape: { type: 'POLYGON', points: [0, 0, 10, 0, 10, 10] },
  },
];

describe('LabelPanel', () => {
  it('클래스별_그룹_+_카운트_표시', () => {
    useLabelStore.getState().reset();
    renderWithProviders(<LabelPanel labels={labels} />);
    expect(screen.getByText('car')).toBeInTheDocument();
    expect(screen.getByText('(2)')).toBeInTheDocument();
  });

  it('항목_클릭_시_selectLabel_호출', () => {
    useLabelStore.getState().reset();
    renderWithProviders(<LabelPanel labels={labels} />);
    const btn = screen.getByText(/a-12345/);
    fireEvent.click(btn);
    expect(useLabelStore.getState().selectedLabelId).toBe('a-12345');
  });
});
