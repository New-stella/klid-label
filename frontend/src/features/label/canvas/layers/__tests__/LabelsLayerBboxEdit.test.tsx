// R17 이슈4: 기존 BBOX 이동/리사이즈 — 선택된 박스 draggable + Transformer + 스토어 좌표 갱신.

import { describe, expect, it, beforeEach, vi } from 'vitest';
import { render } from '@testing-library/react';
import { createElement, type ReactNode } from 'react';

// 마지막으로 렌더된 Rect 의 dragEnd/transformEnd 핸들러를 캡처 — 테스트에서 직접 konva 이벤트로 호출.
const captured: { onDragEnd?: (e: unknown) => void; onTransformEnd?: (e: unknown) => void } = {};

vi.mock('react-konva', () => {
  const passthrough = (name: string) => {
    const KonvaMock = ({
      children,
      dash,
      draggable,
      onDragEnd,
      onTransformEnd,
      ...rest
    }: {
      children?: ReactNode;
      dash?: unknown;
      draggable?: unknown;
      onDragEnd?: (event: unknown) => void;
      onTransformEnd?: (event: unknown) => void;
      [key: string]: unknown;
    }) => {
      const props: Record<string, unknown> = { 'data-konva': name, ...rest };
      if (dash !== undefined) props['data-dash'] = Array.isArray(dash) ? dash.join(',') : String(dash);
      if (draggable !== undefined) props['data-draggable'] = String(draggable);
      if (name === 'Rect') {
        if (onDragEnd) captured.onDragEnd = onDragEnd;
        if (onTransformEnd) captured.onTransformEnd = onTransformEnd;
      }
      return createElement('div', props, children);
    };
    KonvaMock.displayName = `KonvaMock(${name})`;
    return KonvaMock;
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

import { LabelsLayer } from '../LabelsLayer';
import { useLabelStore } from '@/stores/useLabelStore';
import type { Geometry } from '../../utils/coordinateTransformer';
import type { Label } from '../../../types';

// zoom=1 → fitScale=min(200/100)=2, scale=2, left/top=0
const geom: Geometry = {
  image: { width: 100, height: 100 },
  canvas: { width: 200, height: 200 },
  scale: 2,
  top: 0,
  left: 0,
  angle: 0,
};

function bbox(): Label {
  return {
    id: 'a',
    frameNo: 1,
    classId: 1,
    className: 'person',
    source: 'MANUAL',
    shape: { type: 'BBOX', left: 10, top: 10, right: 30, bottom: 30 },
  };
}

// konva 노드 흉내 — getClientRect/x/y/width/height/scaleX 등.
function fakeNode(rect: { x: number; y: number; width: number; height: number }) {
  return {
    x: () => rect.x,
    y: () => rect.y,
    width: () => rect.width,
    height: () => rect.height,
    scaleX: () => 1,
    scaleY: () => 1,
    rotation: () => 0,
    getClientRect: () => rect,
  };
}

function firstRect(c: HTMLElement) {
  return c.querySelector('[data-konva="Rect"]') as HTMLElement;
}

beforeEach(() => {
  useLabelStore.getState().reset();
  captured.onDragEnd = undefined;
  captured.onTransformEnd = undefined;
});

describe('LabelsLayer — 기존 BBOX 이동/리사이즈 (SELECT 도구)', () => {
  it('선택된 BBOX 는 draggable=true', () => {
    useLabelStore.setState({ labels: [bbox()], selectedLabelId: 'a' });
    const { container } = render(<LabelsLayer labels={[bbox()]} geometry={geom} />);
    expect(firstRect(container).getAttribute('data-draggable')).toBe('true');
  });

  it('선택되지 않은 BBOX 는 draggable=false', () => {
    useLabelStore.setState({ labels: [bbox()], selectedLabelId: null });
    const { container } = render(<LabelsLayer labels={[bbox()]} geometry={geom} />);
    expect(firstRect(container).getAttribute('data-draggable')).toBe('false');
  });

  it('잠금(readOnly) 상태에서는 선택돼도 draggable=false', () => {
    useLabelStore.setState({ labels: [bbox()], selectedLabelId: 'a' });
    const { container } = render(<LabelsLayer labels={[bbox()]} geometry={geom} readOnly />);
    expect(firstRect(container).getAttribute('data-draggable')).toBe('false');
  });

  it('dragend 시 스토어 좌표가 delta(이미지 단위)만큼 갱신된다', () => {
    useLabelStore.setState({ labels: [bbox()], selectedLabelId: 'a' });
    render(<LabelsLayer labels={[bbox()]} geometry={geom} />);
    // 원래 캔버스 위치 (20,20) 크기 40x40. 캔버스 (40,40)로 이동(=+20,+20px=+10,+10 이미지).
    captured.onDragEnd!({ target: fakeNode({ x: 40, y: 40, width: 40, height: 40 }) });
    const updated = useLabelStore.getState().labels[0];
    expect(updated.shape.type).toBe('BBOX');
    if (updated.shape.type === 'BBOX') {
      expect(updated.shape.left).toBeCloseTo(20, 3);
      expect(updated.shape.top).toBeCloseTo(20, 3);
      expect(updated.shape.right).toBeCloseTo(40, 3);
      expect(updated.shape.bottom).toBeCloseTo(40, 3);
    }
  });

  it('transformend 시 width/height 가 갱신된다 (리사이즈)', () => {
    useLabelStore.setState({ labels: [bbox()], selectedLabelId: 'a' });
    render(<LabelsLayer labels={[bbox()]} geometry={geom} />);
    // 캔버스 (20,20) 80x80 → 이미지 (10,10) 40x40
    captured.onTransformEnd!({ target: fakeNode({ x: 20, y: 20, width: 80, height: 80 }) });
    const updated = useLabelStore.getState().labels[0];
    if (updated.shape.type === 'BBOX') {
      expect(updated.shape.right - updated.shape.left).toBeCloseTo(40, 3);
      expect(updated.shape.bottom - updated.shape.top).toBeCloseTo(40, 3);
    }
  });

  it('0/음수 크기로 transformend 되면 좌표가 갱신되지 않는다 (무효 방어)', () => {
    useLabelStore.setState({ labels: [bbox()], selectedLabelId: 'a' });
    render(<LabelsLayer labels={[bbox()]} geometry={geom} />);
    captured.onTransformEnd!({ target: fakeNode({ x: 20, y: 20, width: 0, height: 0 }) });
    const updated = useLabelStore.getState().labels[0];
    if (updated.shape.type === 'BBOX') {
      // 원본 그대로
      expect(updated.shape.left).toBe(10);
      expect(updated.shape.right).toBe(30);
    }
  });

  it('이동 후 undo 하면 원좌표로 복원된다', () => {
    useLabelStore.setState({ labels: [bbox()], selectedLabelId: 'a' });
    render(<LabelsLayer labels={[bbox()]} geometry={geom} />);
    captured.onDragEnd!({ target: fakeNode({ x: 40, y: 40, width: 40, height: 40 }) });
    useLabelStore.getState().undo();
    const restored = useLabelStore.getState().labels[0];
    if (restored.shape.type === 'BBOX') {
      expect(restored.shape.left).toBe(10);
      expect(restored.shape.top).toBe(10);
      expect(restored.shape.right).toBe(30);
      expect(restored.shape.bottom).toBe(30);
    }
  });
});
