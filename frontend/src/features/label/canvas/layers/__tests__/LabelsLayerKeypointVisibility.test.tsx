// Phase 4 (R7): 커밋된 KEYPOINT 관절의 가시성(v) 를 Alt+클릭으로 순환 변경.
// - 기본 클릭 = 라벨 선택(기존 동작 유지)
// - Alt+클릭 = 해당 관절 v 순환 (2→1→0→2)

import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render } from '@testing-library/react';
import { createElement, type ReactNode } from 'react';

// 관절 Circle 들의 onClick 을 순서대로 캡처.
const captured: { circleClicks: Array<(e: unknown) => void> } = { circleClicks: [] };

vi.mock('react-konva', () => {
  const passthrough = (name: string) => {
    const KonvaMock = ({
      children,
      dash,
      points,
      listening,
      draggable,
      onDragEnd,
      onClick,
      ...rest
    }: {
      children?: ReactNode;
      dash?: unknown;
      points?: unknown;
      listening?: unknown;
      draggable?: unknown;
      onDragEnd?: (event: unknown) => void;
      onClick?: (event: unknown) => void;
      [key: string]: unknown;
    }) => {
      const props: Record<string, unknown> = { 'data-konva': name, ...rest };
      if (name === 'Circle' && onClick) captured.circleClicks.push(onClick);
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

function keypointLabel(id: string): Label {
  const keypoints = Array.from({ length: 17 }, (_, i) => ({ x: i + 1, y: i + 1, v: 2 }));
  return {
    id,
    frameNo: 1,
    classId: 1,
    className: 'person',
    source: 'MANUAL',
    shape: { type: 'KEYPOINT', keypoints },
  };
}

beforeEach(() => {
  useLabelStore.getState().reset();
  captured.circleClicks = [];
});

describe('LabelsLayer — KEYPOINT 가시성 토글 (Alt+클릭)', () => {
  it('가시성_토글_Alt클릭_v_2_1_0_순환', () => {
    const labels: Label[] = [keypointLabel('kp1')];
    useLabelStore.getState().setLabels(labels);
    useLabelStore.getState().selectLabel('kp1');
    const { rerender } = render(<LabelsLayer labels={labels} geometry={geom} />);

    expect(captured.circleClicks.length).toBe(17);

    // 0번 관절 Alt+클릭 3회 → 2→1→0→2 순환. 각 클릭 후 갱신 라벨로 재렌더(실앱 흐름 모사).
    const clickJoint0 = () => {
      captured.circleClicks[0]({ evt: { altKey: true } });
      const updated = useLabelStore.getState().labels;
      captured.circleClicks = [];
      rerender(<LabelsLayer labels={updated} geometry={geom} />);
      return (updated[0].shape as KeypointShape).keypoints[0].v;
    };

    expect(clickJoint0()).toBe(1);
    expect(clickJoint0()).toBe(0);
    expect(clickJoint0()).toBe(2);
  });

  it('Alt없는_클릭은_v를_바꾸지_않고_라벨만_선택', () => {
    const labels: Label[] = [keypointLabel('kp2')];
    useLabelStore.getState().setLabels(labels);
    render(<LabelsLayer labels={labels} geometry={geom} />);

    captured.circleClicks[0]({ evt: { altKey: false } });
    // v 불변
    expect((useLabelStore.getState().labels[0].shape as KeypointShape).keypoints[0].v).toBe(2);
    // 선택됨
    expect(useLabelStore.getState().selectedLabelId).toBe('kp2');
  });

  it('Alt클릭은_해당_관절만_변경_다른_관절_불변', () => {
    const labels: Label[] = [keypointLabel('kp3')];
    useLabelStore.getState().setLabels(labels);
    useLabelStore.getState().selectLabel('kp3');
    render(<LabelsLayer labels={labels} geometry={geom} />);

    captured.circleClicks[5]({ evt: { altKey: true } });
    const kps = (useLabelStore.getState().labels[0].shape as KeypointShape).keypoints;
    expect(kps[5].v).toBe(1);
    expect(kps[0].v).toBe(2);
    expect(kps[6].v).toBe(2);
    // 좌표는 보존
    expect(kps[5].x).toBe(6);
  });
});
