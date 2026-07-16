// OverlayLayer SAM2 다중 positive-click 정제(SAM_SEGMENT) — react-konva 모킹.
// R6: 클릭 누적 → 확정(Enter/더블클릭) 2단계. 박스 드래그/mock 차단/저신뢰 차단은 무회귀.

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

function countCircles(container: HTMLElement): number {
  return container.querySelectorAll('[data-konva="Circle"]').length;
}

describe('OverlayLayer — SAM2 다중클릭 정제(SAM_SEGMENT)', () => {
  it('SAM_다중클릭시_segPoints가_누적되어_마커로_표시', () => {
    const segment = vi.fn().mockResolvedValue({ polygon: [[1, 1]], score: 0.9 });
    const { container, rerender } = render(
      <OverlayLayer geometry={geom} activeTool={ToolType.SAM_SEGMENT} stageRef={makeStageRef(15, 25)} segment={segment} onLabelAdd={vi.fn()} />,
    );
    fireEvent.click(findRect(container));
    expect(countCircles(container)).toBe(1);
    // 누적: 두 번째 클릭은 다른 좌표.
    rerender(
      <OverlayLayer geometry={geom} activeTool={ToolType.SAM_SEGMENT} stageRef={makeStageRef(30, 40)} segment={segment} onLabelAdd={vi.fn()} />,
    );
    fireEvent.click(findRect(container));
    expect(countCircles(container)).toBe(2);
    // 누적 단계에서는 segment 미호출(확정 전).
    expect(segment).not.toHaveBeenCalled();
  });

  it('SAM_확정(Enter)시_누적포인트_전체로_segment_1회_호출', async () => {
    const segment = vi.fn().mockResolvedValue({ polygon: [[10, 10], [20, 20], [10, 20]], score: 0.9 });
    const { container, rerender } = render(
      <OverlayLayer geometry={geom} activeTool={ToolType.SAM_SEGMENT} stageRef={makeStageRef(15, 25)} segment={segment} onLabelAdd={vi.fn()} />,
    );
    fireEvent.click(findRect(container));
    rerender(
      <OverlayLayer geometry={geom} activeTool={ToolType.SAM_SEGMENT} stageRef={makeStageRef(30, 40)} segment={segment} onLabelAdd={vi.fn()} />,
    );
    fireEvent.click(findRect(container));
    // Enter 확정 → 누적 전체 1회 호출.
    fireEvent.keyDown(window, { key: 'Enter' });
    await waitFor(() => expect(segment).toHaveBeenCalledTimes(1));
    expect(segment).toHaveBeenCalledWith({ points: [[15, 25], [30, 40]] });
    // 확정 후 누적 마커 초기화.
    expect(countCircles(container)).toBe(0);
  });

  it('SAM_확정(더블클릭)시_segment_호출', async () => {
    const segment = vi.fn().mockResolvedValue({ polygon: [[10, 10], [20, 20], [10, 20]], score: 0.9 });
    const { container } = render(
      <OverlayLayer geometry={geom} activeTool={ToolType.SAM_SEGMENT} stageRef={makeStageRef(15, 25)} segment={segment} onLabelAdd={vi.fn()} />,
    );
    fireEvent.click(findRect(container));
    fireEvent.doubleClick(findRect(container));
    await waitFor(() => expect(segment).toHaveBeenCalledTimes(1));
    expect(segment).toHaveBeenCalledWith({ points: [[15, 25]] });
  });

  it('SAM_단일클릭후_확정도_동작', async () => {
    const segment = vi.fn().mockResolvedValue({ polygon: [[10, 10], [20, 20], [10, 20]], score: 0.9 });
    const { container } = render(
      <OverlayLayer geometry={geom} activeTool={ToolType.SAM_SEGMENT} stageRef={makeStageRef(15, 25)} segment={segment} onLabelAdd={vi.fn()} />,
    );
    fireEvent.click(findRect(container));
    fireEvent.keyDown(window, { key: 'Enter' });
    await waitFor(() => expect(segment).toHaveBeenCalledWith({ points: [[15, 25]] }));
  });

  it('SAM_누적포인트_없을때_Enter는_무간섭_a11y', () => {
    // 누적 0 상태의 Enter 는 confirm/preventDefault 안 함 → button/a/select Enter 기본동작 보존.
    const segment = vi.fn().mockResolvedValue({ polygon: [[1, 1]], score: 0.9 });
    render(
      <OverlayLayer geometry={geom} activeTool={ToolType.SAM_SEGMENT} stageRef={makeStageRef(15, 25)} segment={segment} onLabelAdd={vi.fn()} />,
    );
    // fireEvent 반환값 = !defaultPrevented. 누적 0 이면 preventDefault 미호출 → true.
    const notPrevented = fireEvent.keyDown(window, { key: 'Enter' });
    expect(notPrevented).toBe(true);
    expect(segment).not.toHaveBeenCalled();
  });

  it('SAM_도구이탈시_segPoints_초기화', () => {
    const segment = vi.fn().mockResolvedValue({ polygon: [[1, 1]], score: 0.9 });
    const { container, rerender } = render(
      <OverlayLayer geometry={geom} activeTool={ToolType.SAM_SEGMENT} stageRef={makeStageRef(15, 25)} segment={segment} onLabelAdd={vi.fn()} />,
    );
    fireEvent.click(findRect(container));
    expect(countCircles(container)).toBe(1);
    // SELECT 로 전환 → 누적 초기화(마커 제거).
    rerender(
      <OverlayLayer geometry={geom} activeTool={ToolType.SELECT} stageRef={makeStageRef(15, 25)} segment={segment} onLabelAdd={vi.fn()} />,
    );
    expect(countCircles(container)).toBe(0);
  });

  it('SAM_Esc로_진행취소시_segPoints_초기화', () => {
    const segment = vi.fn().mockResolvedValue({ polygon: [[1, 1]], score: 0.9 });
    const { container } = render(
      <OverlayLayer geometry={geom} activeTool={ToolType.SAM_SEGMENT} stageRef={makeStageRef(15, 25)} segment={segment} onLabelAdd={vi.fn()} />,
    );
    fireEvent.click(findRect(container));
    expect(countCircles(container)).toBe(1);
    fireEvent.keyDown(window, { key: 'Escape' });
    expect(countCircles(container)).toBe(0);
    // 취소 → segment 미호출.
    expect(segment).not.toHaveBeenCalled();
  });

  it('드래그시_박스_프롬프트로_요청_무회귀', async () => {
    const segment = vi.fn().mockResolvedValue({ polygon: [[10, 10], [20, 20], [10, 20]], score: 0.9 });
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
    // 박스는 즉시(누적 없이) 호출.
    await waitFor(() => expect(segment).toHaveBeenCalledWith({ box: [10, 10, 40, 40] }));
  });

  it('응답_폴리곤이_기존_적용_흐름으로_추가됨_무회귀', async () => {
    const segment = vi.fn().mockResolvedValue({ polygon: [[10, 10], [20, 20], [10, 20]], score: 0.9 });
    const onLabelAdd = vi.fn();
    const { container } = render(
      <OverlayLayer geometry={geom} activeTool={ToolType.SAM_SEGMENT} stageRef={makeStageRef(15, 25)} segment={segment} onLabelAdd={onLabelAdd} />,
    );
    fireEvent.click(findRect(container));
    fireEvent.keyDown(window, { key: 'Enter' });
    await waitFor(() => expect(onLabelAdd).toHaveBeenCalledTimes(1));
    const label = onLabelAdd.mock.calls[0][0];
    expect(label.shape.type).toBe('POLYGON');
    expect(label.classId).toBe(7);
  });

  it('빈폴리곤_응답이면_자동적용_차단되고_message경고_무회귀', async () => {
    const segment = vi.fn().mockResolvedValue({
      polygon: [],
      score: 0,
      message: 'AI 모델 미로드 — 결과 신뢰 불가',
    });
    const onLabelAdd = vi.fn();
    const onMockWarning = vi.fn();
    const onLowConfidence = vi.fn();
    const { container } = render(
      <OverlayLayer geometry={geom} activeTool={ToolType.SAM_SEGMENT} stageRef={makeStageRef(15, 25)} segment={segment} onLabelAdd={onLabelAdd} onMockWarning={onMockWarning} onLowConfidence={onLowConfidence} />,
    );
    fireEvent.click(findRect(container));
    fireEvent.keyDown(window, { key: 'Enter' });
    await waitFor(() => expect(onMockWarning).toHaveBeenCalledTimes(1));
    expect(onMockWarning.mock.calls[0][0].message).toBe('AI 모델 미로드 — 결과 신뢰 불가');
    expect(onLabelAdd).not.toHaveBeenCalled();
    expect(onLowConfidence).not.toHaveBeenCalled();
  });

  it('저신뢰_는_낮은신뢰도_안내로_빈폴리곤과_구분_무회귀', async () => {
    const segment = vi.fn().mockResolvedValue({
      polygon: [[10, 10], [20, 20], [10, 20]],
      score: 0.1,
      message: null,
    });
    const onLabelAdd = vi.fn();
    const onMockWarning = vi.fn();
    const onLowConfidence = vi.fn();
    const { container } = render(
      <OverlayLayer geometry={geom} activeTool={ToolType.SAM_SEGMENT} stageRef={makeStageRef(15, 25)} segment={segment} onLabelAdd={onLabelAdd} onMockWarning={onMockWarning} onLowConfidence={onLowConfidence} />,
    );
    fireEvent.click(findRect(container));
    fireEvent.keyDown(window, { key: 'Enter' });
    await waitFor(() => expect(onLowConfidence).toHaveBeenCalledTimes(1));
    expect(onMockWarning).not.toHaveBeenCalled();
    expect(onLabelAdd).not.toHaveBeenCalled();
  });

  it('segment_미주입시_클릭_확정해도_안전', () => {
    const onLabelAdd = vi.fn();
    const { container } = render(
      <OverlayLayer geometry={geom} activeTool={ToolType.SAM_SEGMENT} stageRef={makeStageRef(15, 25)} onLabelAdd={onLabelAdd} />,
    );
    expect(() => {
      fireEvent.click(findRect(container));
      fireEvent.keyDown(window, { key: 'Enter' });
    }).not.toThrow();
    expect(onLabelAdd).not.toHaveBeenCalled();
  });
});
