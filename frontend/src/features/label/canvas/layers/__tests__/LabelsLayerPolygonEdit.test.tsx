// Phase C: 폴리곤 전체 이동 / 꼭짓점 편집 — 선택된 Line draggable + 앵커 Circle + 스토어 갱신.

import { describe, expect, it, beforeEach, vi } from 'vitest';
import { render } from '@testing-library/react';

// Line 의 dragEnd, 앵커 Circle 들의 dragEnd 를 캡처한다.
const captured: {
  lineDragEnd?: (e: unknown) => void;
  anchorDragEnds: Array<(e: unknown) => void>;
} = { anchorDragEnds: [] };

vi.mock('react-konva', async () =>
  (await import('@/test/konvaMock')).createKonvaMock({
    onNode: (name, props) => {
      if (!props.onDragEnd) return;
      const handler = props.onDragEnd as (e: unknown) => void;
      if (name === 'Line') captured.lineDragEnd = handler;
      if (name === 'Circle') captured.anchorDragEnds.push(handler);
    },
  }),
);

vi.mock('../../../hooks/useLabelMasters', () => ({
  useLabelMasters: () => ({ data: [], isLoading: false, isError: false }),
}));

import { LabelsLayer } from '../LabelsLayer';
import { useLabelStore } from '@/stores/useLabelStore';
import type { Geometry } from '../../utils/coordinateTransformer';
import type { Label } from '../../../types';

// scale=2, offset 0 → 이미지 px ×2 = 캔버스 px.
const geom: Geometry = {
  image: { width: 100, height: 100 },
  canvas: { width: 200, height: 200 },
  scale: 2,
  top: 0,
  left: 0,
  angle: 0,
};

function poly(points = [10, 10, 30, 10, 20, 30]): Label {
  return {
    id: 'p',
    frameNo: 1,
    classId: 1,
    className: 'car',
    source: 'MANUAL',
    shape: { type: 'POLYGON', points },
  };
}

// draggable Line 의 이동량(node.x()/y())을 흉내내는 노드.
function fakeNode(over: { x?: number; y?: number } = {}) {
  return {
    x: () => over.x ?? 0,
    y: () => over.y ?? 0,
    position: () => ({ x: over.x ?? 0, y: over.y ?? 0 }),
  };
}

function firstLine(c: HTMLElement) {
  return c.querySelector('[data-konva="Line"]') as HTMLElement;
}
function anchors(c: HTMLElement) {
  return Array.from(c.querySelectorAll('[data-konva="Circle"]'));
}

beforeEach(() => {
  useLabelStore.getState().reset();
  captured.lineDragEnd = undefined;
  captured.anchorDragEnds = [];
});

describe('LabelsLayer — 폴리곤 이동/꼭짓점 편집 (SELECT 도구)', () => {
  it('선택된 폴리곤 Line 은 draggable=true', () => {
    useLabelStore.setState({ labels: [poly()], selectedLabelId: 'p' });
    const { container } = render(<LabelsLayer labels={[poly()]} geometry={geom} />);
    expect(firstLine(container).getAttribute('data-draggable')).toBe('true');
  });

  it('선택 안 된 폴리곤은 draggable=false', () => {
    useLabelStore.setState({ labels: [poly()], selectedLabelId: null });
    const { container } = render(<LabelsLayer labels={[poly()]} geometry={geom} />);
    expect(firstLine(container).getAttribute('data-draggable')).toBe('false');
  });

  it('잠금(readOnly) 시 선택돼도 draggable=false', () => {
    useLabelStore.setState({ labels: [poly()], selectedLabelId: 'p' });
    const { container } = render(<LabelsLayer labels={[poly()]} geometry={geom} readOnly />);
    expect(firstLine(container).getAttribute('data-draggable')).toBe('false');
  });

  it('dragEnd 시 전체 점이 delta(이미지 단위)만큼 갱신된다 (scale 보정)', () => {
    useLabelStore.setState({ labels: [poly()], selectedLabelId: 'p' });
    render(<LabelsLayer labels={[poly()]} geometry={geom} />);
    // 캔버스 +20,+20 = 이미지 +10,+10
    captured.lineDragEnd!({ target: fakeNode({ x: 20, y: 20 }) });
    const updated = useLabelStore.getState().labels[0];
    expect(updated.shape.type).toBe('POLYGON');
    if (updated.shape.type === 'POLYGON') {
      expect(updated.shape.points).toEqual([20, 20, 40, 20, 30, 40]);
    }
  });

  it('경계 밖 이동 시 폴리곤 전체가 이미지 안에 머문다 (delta 클램프)', () => {
    useLabelStore.setState({ labels: [poly()], selectedLabelId: 'p' });
    render(<LabelsLayer labels={[poly()]} geometry={geom} />);
    // 캔버스 -200,-200 = 이미지 -100,-100 → min x/y=10 이므로 delta -10 으로 클램프
    captured.lineDragEnd!({ target: fakeNode({ x: -200, y: -200 }) });
    const updated = useLabelStore.getState().labels[0];
    if (updated.shape.type === 'POLYGON') {
      const xs = updated.shape.points.filter((_, i) => i % 2 === 0);
      const ys = updated.shape.points.filter((_, i) => i % 2 === 1);
      expect(Math.min(...xs)).toBeGreaterThanOrEqual(0);
      expect(Math.min(...ys)).toBeGreaterThanOrEqual(0);
    }
  });

  it('점 수 임계 이하면 꼭짓점 앵커(Circle)가 점 수만큼 렌더된다', () => {
    useLabelStore.setState({ labels: [poly()], selectedLabelId: 'p' });
    const { container } = render(<LabelsLayer labels={[poly()]} geometry={geom} />);
    expect(anchors(container).length).toBe(3); // 점 3개
  });

  it('점 수 임계 초과 시 앵커가 렌더되지 않는다 (대량 SAM2 폴리곤)', () => {
    const many: number[] = [];
    for (let i = 0; i < 200; i += 1) many.push(i % 100, (i * 2) % 100);
    useLabelStore.setState({ labels: [poly(many)], selectedLabelId: 'p' });
    const { container } = render(<LabelsLayer labels={[poly(many)]} geometry={geom} />);
    expect(anchors(container).length).toBe(0);
  });

  it('선택 안 된 폴리곤은 앵커를 렌더하지 않는다', () => {
    useLabelStore.setState({ labels: [poly()], selectedLabelId: null });
    const { container } = render(<LabelsLayer labels={[poly()]} geometry={geom} />);
    expect(anchors(container).length).toBe(0);
  });

  it('꼭짓점 앵커 드래그 시 해당 점만 갱신된다', () => {
    useLabelStore.setState({ labels: [poly()], selectedLabelId: 'p' });
    render(<LabelsLayer labels={[poly()]} geometry={geom} />);
    // 두번째 앵커(index 1)를 캔버스 (80,40)=이미지(40,20)로
    captured.anchorDragEnds[1]({ target: fakeNode({ x: 80, y: 40 }) });
    const updated = useLabelStore.getState().labels[0];
    if (updated.shape.type === 'POLYGON') {
      expect(updated.shape.points).toEqual([10, 10, 40, 20, 20, 30]);
    }
  });

  it('이동 후 undo 하면 원좌표로 복원된다', () => {
    useLabelStore.setState({ labels: [poly()], selectedLabelId: 'p' });
    render(<LabelsLayer labels={[poly()]} geometry={geom} />);
    captured.lineDragEnd!({ target: fakeNode({ x: 20, y: 20 }) });
    useLabelStore.getState().undo();
    const restored = useLabelStore.getState().labels[0];
    if (restored.shape.type === 'POLYGON') {
      expect(restored.shape.points).toEqual([10, 10, 30, 10, 20, 30]);
    }
  });
});
