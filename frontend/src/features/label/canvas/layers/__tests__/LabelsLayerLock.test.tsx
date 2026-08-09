// Phase 3 R6 — 잠금 라벨은 선택 불가·드래그 불가·Transformer 리사이즈 불가.
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render } from '@testing-library/react';

// 렌더된 Rect props(draggable/listening) + Transformer 렌더 여부 캡처.
// `listening` 은 konva 히트테스트 전용 축이라 DOM 에 대응이 없다 — onNode 로만 관측된다.
const captured: {
  rects: Array<{ draggable: unknown; listening: unknown }>;
  transformers: number;
} = { rects: [], transformers: 0 };

vi.mock('react-konva', async () =>
  (await import('@/test/konvaMock')).createKonvaMock({
    onNode: (name, props) => {
      if (name === 'Rect') {
        captured.rects.push({ draggable: props.draggable, listening: props.listening });
      }
      if (name === 'Transformer') captured.transformers += 1;
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
