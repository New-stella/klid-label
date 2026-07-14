// Phase 3: LabelsLayer KEYPOINT 렌더 — 스켈레톤 19선 + 관절 Circle + 점별 드래그.

import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render } from '@testing-library/react';

// 관절 Circle 들의 onDragEnd 를 순서대로 캡처.
const captured: { circleDragEnds: Array<(e: any) => void> } = { circleDragEnds: [] };

vi.mock('react-konva', () => {
  const React = require('react');
  const passthrough = (name: string) => {
    return ({ children, dash, points, listening, draggable, onDragEnd, ...rest }: any) => {
      const props: Record<string, unknown> = { 'data-konva': name, ...rest };
      if (dash !== undefined) props['data-dash'] = Array.isArray(dash) ? dash.join(',') : String(dash);
      if (points !== undefined) props['data-points'] = Array.isArray(points) ? points.join(',') : String(points);
      if (draggable !== undefined) props['data-draggable'] = String(draggable);
      if (name === 'Circle' && onDragEnd) captured.circleDragEnds.push(onDragEnd);
      return React.createElement('div', props, children);
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
import type { KeypointShape, Label } from '../../../types';

const geom: Geometry = {
  image: { width: 100, height: 100 },
  canvas: { width: 100, height: 100 },
  scale: 1,
  top: 0,
  left: 0,
  angle: 0,
};

function makeKps(): { x: number; y: number; v: number }[] {
  return Array.from({ length: 17 }, (_, i) => ({ x: i + 1, y: i + 1, v: 2 }));
}

function keypointLabel(over: Partial<Label> & Pick<Label, 'id'>): Label {
  return {
    frameNo: 1,
    classId: 1,
    className: 'person',
    source: 'MANUAL',
    shape: { type: 'KEYPOINT', keypoints: makeKps() },
    ...over,
  };
}

function bboxLabel(over: Partial<Label> & Pick<Label, 'id'>): Label {
  return {
    frameNo: 1,
    classId: 1,
    className: 'car',
    source: 'AUTO_YOLO',
    shape: { type: 'BBOX', left: 0, top: 0, right: 10, bottom: 10 },
    ...over,
  };
}

function fakeNode(x: number, y: number) {
  return { x: () => x, y: () => y, position: () => ({ x, y }) };
}

beforeEach(() => {
  useLabelStore.getState().reset();
  captured.circleDragEnds = [];
});

describe('LabelsLayer — KEYPOINT 렌더 + 드래그', () => {
  it('LabelsLayer_KEYPOINT_스켈레톤_19선_렌더', () => {
    const labels: Label[] = [keypointLabel({ id: 'kp1' })];
    const { container } = render(<LabelsLayer labels={labels} geometry={geom} />);
    // 모든 관절 v=2 가시 → 19 스켈레톤 Line 전부 렌더
    const lines = container.querySelectorAll('[data-konva="Line"]');
    expect(lines).toHaveLength(19);
    // 관절 Circle 17개
    const circles = container.querySelectorAll('[data-konva="Circle"]');
    expect(circles).toHaveLength(17);
  });

  it('LabelsLayer_v0_관절_연결선_숨김', () => {
    const kps = makeKps();
    kps[0] = { x: 1, y: 1, v: 0 }; // nose 미표기 → nose 를 끝점으로 갖는 엣지 숨김
    const labels: Label[] = [keypointLabel({ id: 'kp2', shape: { type: 'KEYPOINT', keypoints: kps } })];
    const { container } = render(<LabelsLayer labels={labels} geometry={geom} />);
    const lines = container.querySelectorAll('[data-konva="Line"]');
    // nose(관절1)를 끝점으로 갖는 엣지: [1,2],[1,3] 2개 숨김 → 17선
    expect(lines).toHaveLength(17);
  });

  it('LabelsLayer_점_드래그시_해당_keypoint만_갱신', () => {
    const labels: Label[] = [keypointLabel({ id: 'kp3' })];
    useLabelStore.getState().setLabels(labels);
    useLabelStore.getState().selectLabel('kp3');
    render(<LabelsLayer labels={labels} geometry={geom} />);

    expect(captured.circleDragEnds.length).toBe(17);
    // 첫 관절(index 0) 드래그 종료 — 새 위치 (50,60) 로 이동 (identity geom).
    captured.circleDragEnds[0]({ target: fakeNode(50, 60) });

    const updated = useLabelStore.getState().labels[0].shape as KeypointShape;
    expect(updated.keypoints[0].x).toBe(50);
    expect(updated.keypoints[0].y).toBe(60);
    // 다른 관절은 불변
    expect(updated.keypoints[1].x).toBe(2);
    expect(updated.keypoints[1].y).toBe(2);
    // 가시성(v)도 보존
    expect(updated.keypoints[0].v).toBe(2);
  });

  it('LabelsLayer_기존_BBOX_렌더_회귀없음', () => {
    const labels: Label[] = [bboxLabel({ id: 'b1' }), keypointLabel({ id: 'kp4' })];
    const { container } = render(<LabelsLayer labels={labels} geometry={geom} />);
    // BBOX Rect 여전히 렌더
    const rects = container.querySelectorAll('[data-konva="Rect"]');
    expect(rects.length).toBeGreaterThanOrEqual(1);
  });
});
