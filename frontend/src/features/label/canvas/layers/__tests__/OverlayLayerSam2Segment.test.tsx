// OverlayLayer SAM2 클릭/박스 분할(SAM_SEGMENT) 케이스 — react-konva 모킹.

import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, waitFor } from '@testing-library/react';

vi.mock('react-konva', () => {
  const React = require('react');
  const passthrough = (name: string) => {
    return ({ children, dash, points, onDblClick, listening, ...rest }: any) => {
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
  };
});

vi.mock('../../../hooks/useLabelMasters', () => ({
  useLabelMasters: () => ({
    data: [{ labelId: 7, name: '사람', sortNo: 1, useYn: 'Y' }],
    isLoading: false,
    isError: false,
  }),
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

function makeStageRef(x: number, y: number) {
  return {
    current: { getPointerPosition: () => ({ x, y }) },
  } as unknown as React.RefObject<Konva.Stage | null>;
}

function findRect(container: HTMLElement): HTMLElement {
  return container.querySelectorAll('[data-konva="Rect"]')[0] as HTMLElement;
}

describe('OverlayLayer — SAM2 클릭/박스 분할(SAM_SEGMENT)', () => {
  it('클릭시_포인트_프롬프트로_segment_요청', async () => {
    const segment = vi.fn().mockResolvedValue({
      polygon: [[10, 10], [20, 20], [10, 20]],
      score: 0.9,
      mock: false,
    });
    const { container } = render(
      <OverlayLayer
        geometry={geom}
        activeTool={ToolType.SAM_SEGMENT}
        stageRef={makeStageRef(15, 25)}
        segment={segment}
        onLabelAdd={vi.fn()}
      />,
    );
    fireEvent.click(findRect(container));
    await waitFor(() => expect(segment).toHaveBeenCalledWith({ points: [[15, 25]] }));
  });

  it('드래그시_박스_프롬프트로_요청', async () => {
    const segment = vi.fn().mockResolvedValue({
      polygon: [[10, 10], [20, 20], [10, 20]],
      score: 0.9,
      mock: false,
    });
    // mousedown(10,10) → mousemove(40,40) → mouseup(40,40)
    const ref = makeStageRef(10, 10);
    const { container, rerender } = render(
      <OverlayLayer geometry={geom} activeTool={ToolType.SAM_SEGMENT} stageRef={ref} segment={segment} onLabelAdd={vi.fn()} />,
    );
    const rect = findRect(container);
    fireEvent.mouseDown(rect);
    rerender(
      <OverlayLayer geometry={geom} activeTool={ToolType.SAM_SEGMENT} stageRef={makeStageRef(40, 40)} segment={segment} onLabelAdd={vi.fn()} />,
    );
    const rect2 = findRect(container);
    fireEvent.mouseMove(rect2);
    fireEvent.mouseUp(rect2);
    await waitFor(() => expect(segment).toHaveBeenCalledWith({ box: [10, 10, 40, 40] }));
  });

  it('응답_폴리곤이_기존_적용_흐름으로_추가됨', async () => {
    const segment = vi.fn().mockResolvedValue({
      polygon: [[10, 10], [20, 20], [10, 20]],
      score: 0.9,
      mock: false,
    });
    const onLabelAdd = vi.fn();
    const { container } = render(
      <OverlayLayer
        geometry={geom}
        activeTool={ToolType.SAM_SEGMENT}
        stageRef={makeStageRef(15, 25)}
        segment={segment}
        onLabelAdd={onLabelAdd}
      />,
    );
    fireEvent.click(findRect(container));
    await waitFor(() => expect(onLabelAdd).toHaveBeenCalledTimes(1));
    const label = onLabelAdd.mock.calls[0][0];
    expect(label.shape.type).toBe('POLYGON');
    expect(label.classId).toBe(7);
  });

  it('mock_응답시_경고_표시_및_자동적용_차단', async () => {
    const segment = vi.fn().mockResolvedValue({
      polygon: [[10, 10], [20, 20], [10, 20]],
      score: 0.5,
      mock: true,
    });
    const onLabelAdd = vi.fn();
    const onMockWarning = vi.fn();
    const { container } = render(
      <OverlayLayer
        geometry={geom}
        activeTool={ToolType.SAM_SEGMENT}
        stageRef={makeStageRef(15, 25)}
        segment={segment}
        onLabelAdd={onLabelAdd}
        onMockWarning={onMockWarning}
      />,
    );
    fireEvent.click(findRect(container));
    await waitFor(() => expect(onMockWarning).toHaveBeenCalledTimes(1));
    // 자동 적용 차단 — onLabelAdd 호출되지 않음.
    expect(onLabelAdd).not.toHaveBeenCalled();
  });

  it('segment_미주입시_클릭해도_안전', () => {
    const onLabelAdd = vi.fn();
    const { container } = render(
      <OverlayLayer
        geometry={geom}
        activeTool={ToolType.SAM_SEGMENT}
        stageRef={makeStageRef(15, 25)}
        onLabelAdd={onLabelAdd}
      />,
    );
    expect(() => fireEvent.click(findRect(container))).not.toThrow();
    expect(onLabelAdd).not.toHaveBeenCalled();
  });
});
