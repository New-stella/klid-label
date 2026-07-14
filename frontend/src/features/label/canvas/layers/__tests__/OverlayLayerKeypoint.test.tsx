// Phase 3: OverlayLayer KEYPOINT 툴 — 17점 순차 클릭 배치 → shape 커밋.

import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render } from '@testing-library/react';

let labelMastersData: Array<{ labelId: number; name: string; sortNo: number; useYn: string }> = [];

vi.mock('react-konva', () => {
  const React = require('react');
  const passthrough = (name: string) => {
    return ({ children, dash, points, listening, onDblClick, ...rest }: any) => {
      const props: Record<string, unknown> = { 'data-konva': name, ...rest };
      if (dash !== undefined) props['data-dash'] = Array.isArray(dash) ? dash.join(',') : String(dash);
      if (points !== undefined) props['data-points'] = Array.isArray(points) ? points.join(',') : String(points);
      if (onDblClick) props.onDoubleClick = onDblClick;
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
    Text: passthrough('Text'),
  };
});

vi.mock('../../../hooks/useLabelMasters', () => ({
  useLabelMasters: () => ({ data: labelMastersData, isLoading: false, isError: false }),
}));

import type Konva from 'konva';

import { OverlayLayer } from '../OverlayLayer';
import { ToolType } from '../../../types';
import type { Geometry } from '../../utils/coordinateTransformer';

const geom: Geometry = {
  image: { width: 100, height: 100 },
  canvas: { width: 100, height: 100 },
  scale: 1,
  top: 0,
  left: 0,
  angle: 0,
};

function makeStageRef() {
  const ref = { current: { getPointerPosition: () => ({ x: 0, y: 0 }) } };
  return ref as unknown as React.RefObject<Konva.Stage | null> & {
    current: { getPointerPosition: () => { x: number; y: number } };
  };
}

function captureRect(container: HTMLElement): HTMLElement {
  return container.querySelectorAll('[data-konva="Rect"]')[0] as HTMLElement;
}

describe('OverlayLayer — KEYPOINT 툴 17점 배치', () => {
  beforeEach(() => {
    labelMastersData = [{ labelId: 7, name: '사람', sortNo: 1, useYn: 'Y' }];
  });

  it('OverlayLayer_KEYPOINT툴_17번_클릭시_shape_커밋', () => {
    const onLabelAdd = vi.fn();
    const stageRef = makeStageRef();
    const { container } = render(
      <OverlayLayer geometry={geom} activeTool={ToolType.KEYPOINT} stageRef={stageRef} onLabelAdd={onLabelAdd} />,
    );
    const rect = captureRect(container);

    // 서로 다른 17점을 순차 클릭.
    for (let i = 0; i < 17; i += 1) {
      stageRef.current.getPointerPosition = () => ({ x: i + 1, y: (i + 1) * 2 });
      fireEvent.click(rect);
    }

    expect(onLabelAdd).toHaveBeenCalledTimes(1);
    const label = onLabelAdd.mock.calls[0][0];
    expect(label.shape.type).toBe('KEYPOINT');
    expect(label.shape.keypoints).toHaveLength(17);
    // 각 원소는 {x,y,v}
    for (const kp of label.shape.keypoints) {
      expect(kp).toHaveProperty('x');
      expect(kp).toHaveProperty('y');
      expect(kp).toHaveProperty('v');
    }
    // 첫 점 좌표 (identity geometry)
    expect(label.shape.keypoints[0].x).toBe(1);
    expect(label.shape.keypoints[0].y).toBe(2);
  });

  it('OverlayLayer_16점만_찍으면_커밋_안됨', () => {
    const onLabelAdd = vi.fn();
    const stageRef = makeStageRef();
    const { container } = render(
      <OverlayLayer geometry={geom} activeTool={ToolType.KEYPOINT} stageRef={stageRef} onLabelAdd={onLabelAdd} />,
    );
    const rect = captureRect(container);
    for (let i = 0; i < 16; i += 1) {
      stageRef.current.getPointerPosition = () => ({ x: i + 1, y: i + 1 });
      fireEvent.click(rect);
    }
    expect(onLabelAdd).not.toHaveBeenCalled();
  });

  it('OverlayLayer_기존_POLYGON툴_회귀없음', () => {
    // KEYPOINT 추가가 POLYGON 커밋 경로를 깨지 않음.
    const onLabelAdd = vi.fn();
    const stageRef = makeStageRef();
    const { container } = render(
      <OverlayLayer geometry={geom} activeTool={ToolType.POLYGON} stageRef={stageRef} onLabelAdd={onLabelAdd} />,
    );
    const rect = captureRect(container);
    const pts = [
      { x: 10, y: 10 },
      { x: 40, y: 10 },
      { x: 20, y: 40 },
    ];
    for (const p of pts) {
      stageRef.current.getPointerPosition = () => p;
      fireEvent.click(rect);
    }
    fireEvent.doubleClick(rect);
    expect(onLabelAdd).toHaveBeenCalledTimes(1);
    expect(onLabelAdd.mock.calls[0][0].shape.type).toBe('POLYGON');
  });
});
