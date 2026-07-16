// Phase 3 R6 — 잠금 라벨은 선택 불가·드래그 불가·Transformer 리사이즈 불가.
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render } from '@testing-library/react';

// 렌더된 Rect props(draggable/listening) + Transformer 렌더 여부 캡처.
const captured: {
  rects: Array<{ draggable: unknown; listening: unknown }>;
  transformers: number;
} = { rects: [], transformers: 0 };

vi.mock('react-konva', () => {
  const React = require('react');
  const passthrough = (name: string) => {
    return ({ children, dash, points, onDragEnd, onClick, onTap, ...rest }: any) => {
      if (name === 'Rect') {
        captured.rects.push({ draggable: rest.draggable, listening: rest.listening });
      }
      if (name === 'Transformer') captured.transformers += 1;
      const { listening, draggable, ...domRest } = rest;
      return React.createElement('div', { 'data-konva': name, ...domRest }, children);
    };
  };
  return {
    Stage: passthrough('Stage'),
    Layer: passthrough('Layer'),
    Image: passthrough('Image'),
    Rect: passthrough('Rect'),
    Line: passthrough('Line'),
    Circle: passthrough('Circle'),
    Transformer: passthrough('Transformer'),
  };
});

vi.mock('../../../hooks/useLabelMasters', () => ({
  useLabelMasters: () => ({ data: [], isLoading: false, isError: false }),
}));

import { useLabelStore } from '@/stores/useLabelStore';

import { LabelsLayer } from '../LabelsLayer';
import type { Geometry } from '../../utils/coordinateTransformer';
import type { Label } from '../../../types';

const geom: Geometry = {
  image: { width: 100, height: 100 },
  canvas: { width: 100, height: 100 },
  scale: 1,
  top: 0,
  left: 0,
  angle: 0,
};

function bbox(id: string): Label {
  return {
    id,
    frameNo: 1,
    classId: 1,
    className: 'car',
    source: 'MANUAL',
    shape: { type: 'BBOX', left: 0, top: 0, right: 10, bottom: 10 },
  };
}

beforeEach(() => {
  useLabelStore.getState().reset();
  captured.rects = [];
  captured.transformers = 0;
});

describe('LabelsLayer — 잠금 라벨 편집 차단(R6)', () => {
  it('잠금_선택_라벨은_draggable_false_listening_false_Transformer_없음', () => {
    const labels = [bbox('a')];
    useLabelStore.getState().setLabels(labels);
    useLabelStore.getState().selectLabel('a');
    useLabelStore.getState().toggleLabelLock('a');
    render(<LabelsLayer labels={labels} geometry={geom} />);

    expect(captured.rects).toHaveLength(1);
    expect(captured.rects[0].draggable).toBe(false);
    expect(captured.rects[0].listening).toBe(false);
    // 잠금 → Transformer(리사이즈 핸들) 미렌더.
    expect(captured.transformers).toBe(0);
  });

  it('비잠금_선택_라벨은_draggable_true_Transformer_렌더_회귀없음', () => {
    const labels = [bbox('a')];
    useLabelStore.getState().setLabels(labels);
    useLabelStore.getState().selectLabel('a');
    render(<LabelsLayer labels={labels} geometry={geom} />);

    expect(captured.rects[0].draggable).toBe(true);
    expect(captured.rects[0].listening).toBe(true);
    expect(captured.transformers).toBe(1);
  });
});
