// Phase 2 (⑤ T 표시/숨김) — hiddenLabelIds 에 든 라벨은 캔버스 렌더 skip.
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render } from '@testing-library/react';

// 렌더된 Rect 개수를 캡처한다.
const captured: { rects: string[] } = { rects: [] };

vi.mock('react-konva', async () =>
  (await import('@/test/konvaMock')).createKonvaMock({
    onNode: (name) => {
      if (name === 'Rect') captured.rects.push('rect');
    },
  }),
);

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
});

describe('LabelsLayer — 가시성 숨김 라벨 렌더 skip', () => {
  it('숨김_라벨은_Rect_렌더_안됨', () => {
    const labels = [bbox('a'), bbox('b')];
    useLabelStore.getState().setLabels(labels);
    useLabelStore.getState().toggleLabelVisibility('a');
    render(<LabelsLayer labels={labels} geometry={geom} />);
    // b 만 렌더 (a 는 숨김).
    expect(captured.rects.length).toBe(1);
  });

  it('숨김_없으면_모든_라벨_렌더', () => {
    const labels = [bbox('a'), bbox('b')];
    useLabelStore.getState().setLabels(labels);
    render(<LabelsLayer labels={labels} geometry={geom} />);
    expect(captured.rects.length).toBe(2);
  });
});
