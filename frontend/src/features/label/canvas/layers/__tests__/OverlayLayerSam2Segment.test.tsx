// OverlayLayer SAM2 다중 positive-click 정제(SAM_SEGMENT) — react-konva 모킹.
// R6: 클릭 누적 → 확정(Enter/더블클릭) 2단계. 박스 드래그/mock 차단/저신뢰 차단은 무회귀.

import { describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, waitFor } from '@testing-library/react';
import type Konva from 'konva';

vi.mock('react-konva', async () => (await import('@/test/konvaMock')).createKonvaMock());

vi.mock('../../../hooks/useLabelMasters', () => ({
  useLabelMasters: () => ({
    data: [{ labelId: 7, name: '사람', sortNo: 1, useYn: 'Y' }],
    isLoading: false,
    isError: false,
  }),
}));

import { OverlayLayer } from '../OverlayLayer';
import type { Sam2SegmentResponse } from '../../../api';
import { ToolType } from '../../../types';
import type { Geometry } from '../../utils/coordinateTransformer';

type SegResult = Sam2SegmentResponse | null;

// 수동 제어 가능한 promise — in-flight 경합을 직렬화 없이 실제 재현하기 위해 사용.
function makeDeferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((r) => {
    resolve = r;
  });
  return { promise, resolve };
}

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

function findLines(container: HTMLElement): HTMLElement[] {
  return Array.from(container.querySelectorAll('[data-konva="Line"]')) as HTMLElement[];
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

describe('OverlayLayer — AI 분할 즉시 프리뷰(immediateSegment)', () => {
  it('즉시모드_OFF면_클릭은_누적만하고_segment를_호출하지_않는다', () => {
    // immediateSegment 미지정(기본 false) — 기존 "누적 후 확정 시 1회" 동작 보존.
    const segment = vi.fn().mockResolvedValue({ polygon: [[10, 10], [20, 20], [10, 20]], score: 0.9 });
    const { container } = render(
      <OverlayLayer geometry={geom} activeTool={ToolType.SAM_SEGMENT} stageRef={makeStageRef(15, 25)} segment={segment} onLabelAdd={vi.fn()} />,
    );
    fireEvent.click(findRect(container));
    // 누적 마커 1개, 프리뷰 Line 없음, segment 미호출.
    expect(countCircles(container)).toBe(1);
    expect(findLines(container)).toHaveLength(0);
    expect(segment).not.toHaveBeenCalled();
  });

  it('즉시모드_ON이면_클릭마다_누적점_전체로_segment가_호출된다', async () => {
    const segment = vi.fn().mockResolvedValue({ polygon: [[10, 10], [20, 20], [10, 20]], score: 0.9 });
    const { container, rerender } = render(
      <OverlayLayer geometry={geom} activeTool={ToolType.SAM_SEGMENT} stageRef={makeStageRef(15, 25)} segment={segment} onLabelAdd={vi.fn()} immediateSegment />,
    );
    fireEvent.click(findRect(container));
    await waitFor(() => expect(segment).toHaveBeenCalledTimes(1));
    expect(segment).toHaveBeenNthCalledWith(1, { points: [[15, 25]] });
    // 두 번째 클릭은 다른 좌표 → 누적 점 전체 재요청.
    rerender(
      <OverlayLayer geometry={geom} activeTool={ToolType.SAM_SEGMENT} stageRef={makeStageRef(30, 40)} segment={segment} onLabelAdd={vi.fn()} immediateSegment />,
    );
    fireEvent.click(findRect(container));
    await waitFor(() => expect(segment).toHaveBeenCalledTimes(2));
    expect(segment).toHaveBeenNthCalledWith(2, { points: [[15, 25], [30, 40]] });
  });

  it('즉시모드_ON에서_유효응답이면_프리뷰_폴리곤이_렌더되고_라벨은_커밋되지_않는다', async () => {
    const segment = vi.fn().mockResolvedValue({ polygon: [[10, 10], [20, 20], [10, 20]], score: 0.9 });
    const onLabelAdd = vi.fn();
    const { container } = render(
      <OverlayLayer geometry={geom} activeTool={ToolType.SAM_SEGMENT} stageRef={makeStageRef(15, 25)} segment={segment} onLabelAdd={onLabelAdd} immediateSegment />,
    );
    fireEvent.click(findRect(container));
    // 프리뷰 Line 이 렌더될 때까지 대기(이미지 flat → 캔버스 flat, geom identity).
    await waitFor(() => expect(findLines(container)).toHaveLength(1));
    expect(findLines(container)[0].getAttribute('data-points')).toBe('10,10,20,20,10,20');
    // 프리뷰 단계에서는 라벨 커밋 안 함.
    expect(onLabelAdd).not.toHaveBeenCalled();
  });

  it('즉시모드_ON에서_더블클릭_확정시_프리뷰가_실제_라벨로_커밋된다', async () => {
    const segment = vi.fn().mockResolvedValue({ polygon: [[10, 10], [20, 20], [10, 20]], score: 0.9 });
    const onLabelAdd = vi.fn();
    const { container } = render(
      <OverlayLayer geometry={geom} activeTool={ToolType.SAM_SEGMENT} stageRef={makeStageRef(15, 25)} segment={segment} onLabelAdd={onLabelAdd} immediateSegment />,
    );
    fireEvent.click(findRect(container));
    await waitFor(() => expect(findLines(container)).toHaveLength(1));
    segment.mockClear();
    // 더블클릭 확정 → 재요청 없이 프리뷰를 커밋.
    fireEvent.doubleClick(findRect(container));
    await waitFor(() => expect(onLabelAdd).toHaveBeenCalledTimes(1));
    const label = onLabelAdd.mock.calls[0][0];
    expect(label.shape.type).toBe('POLYGON');
    expect(label.classId).toBe(7);
    // 확정 시 재요청(segment) 없음.
    expect(segment).not.toHaveBeenCalled();
    // 프리뷰·누적 초기화.
    expect(findLines(container)).toHaveLength(0);
    expect(countCircles(container)).toBe(0);
  });

  it('즉시모드_ON에서_빈폴리곤(mock)_응답은_프리뷰로_표시되지_않고_onMockWarning이_호출된다', async () => {
    const segment = vi.fn().mockResolvedValue({ polygon: [], score: 0, message: 'AI 모델 미로드' });
    const onLabelAdd = vi.fn();
    const onMockWarning = vi.fn();
    const { container } = render(
      <OverlayLayer geometry={geom} activeTool={ToolType.SAM_SEGMENT} stageRef={makeStageRef(15, 25)} segment={segment} onLabelAdd={onLabelAdd} onMockWarning={onMockWarning} immediateSegment />,
    );
    fireEvent.click(findRect(container));
    await waitFor(() => expect(onMockWarning).toHaveBeenCalledTimes(1));
    // 프리뷰 미표시, 라벨 미커밋.
    expect(findLines(container)).toHaveLength(0);
    expect(onLabelAdd).not.toHaveBeenCalled();
  });

  it('도구전환시_프리뷰와_누적점이_초기화된다', async () => {
    const segment = vi.fn().mockResolvedValue({ polygon: [[10, 10], [20, 20], [10, 20]], score: 0.9 });
    const { container, rerender } = render(
      <OverlayLayer geometry={geom} activeTool={ToolType.SAM_SEGMENT} stageRef={makeStageRef(15, 25)} segment={segment} onLabelAdd={vi.fn()} immediateSegment />,
    );
    fireEvent.click(findRect(container));
    await waitFor(() => expect(findLines(container)).toHaveLength(1));
    // SELECT 로 전환 → SAM_SEGMENT 로 복귀 시 프리뷰 상태가 유지되지 않아야 한다(초기화 검증).
    rerender(
      <OverlayLayer geometry={geom} activeTool={ToolType.SELECT} stageRef={makeStageRef(15, 25)} segment={segment} onLabelAdd={vi.fn()} immediateSegment />,
    );
    rerender(
      <OverlayLayer geometry={geom} activeTool={ToolType.SAM_SEGMENT} stageRef={makeStageRef(15, 25)} segment={segment} onLabelAdd={vi.fn()} immediateSegment />,
    );
    expect(findLines(container)).toHaveLength(0);
    expect(countCircles(container)).toBe(0);
  });
});

describe('OverlayLayer — AI 분할 즉시 프리뷰 in-flight 경합(immediateSegment)', () => {
  const validPoly = { polygon: [[10, 10], [20, 20], [10, 20]] as number[][], score: 0.9 };

  it('즉시모드_ON에서_마지막클릭이_inflight로_드롭돼도_확정시_현재점전체로_재요청후_커밋된다', async () => {
    // 이슈1a — 점A(inflight) 중 점B 클릭이 드롭(null)돼 프리뷰가 점B를 반영 못해도,
    // 확정 시 프리뷰를 커밋하지 않고 현재 누적점 전체([A,B])로 재요청해 커밋한다.
    const dA = makeDeferred<SegResult>();
    const segment = vi
      .fn()
      .mockReturnValueOnce(dA.promise) // 클릭A — in-flight(미해소)
      .mockResolvedValueOnce(null) // 클릭B — inflight 가드로 드롭(null)
      .mockResolvedValueOnce({ polygon: [[10, 10], [20, 20], [10, 20], [15, 25]], score: 0.9 }); // 확정 재요청
    const onLabelAdd = vi.fn();
    const { container, rerender } = render(
      <OverlayLayer geometry={geom} activeTool={ToolType.SAM_SEGMENT} stageRef={makeStageRef(15, 25)} segment={segment} onLabelAdd={onLabelAdd} immediateSegment />,
    );
    fireEvent.click(findRect(container)); // 클릭A (gen1)
    rerender(
      <OverlayLayer geometry={geom} activeTool={ToolType.SAM_SEGMENT} stageRef={makeStageRef(30, 40)} segment={segment} onLabelAdd={onLabelAdd} immediateSegment />,
    );
    fireEvent.click(findRect(container)); // 클릭B (gen2) → 드롭(null)
    // 점A 응답을 뒤늦게 해소 → gen1 무효화 → stale 프리뷰(점A만) 미반영.
    await act(async () => {
      dA.resolve(validPoly);
    });
    expect(findLines(container)).toHaveLength(0); // stale 프리뷰 없음
    expect(countCircles(container)).toBe(2); // 누적점 A,B 유지
    segment.mockClear();
    // 확정(더블클릭) — 프리뷰 없음 → 현재 누적점 전체로 재요청 후 커밋.
    fireEvent.doubleClick(findRect(container));
    await waitFor(() => expect(onLabelAdd).toHaveBeenCalledTimes(1));
    expect(segment).toHaveBeenCalledWith({ points: [[15, 25], [30, 40]] });
    expect(onLabelAdd.mock.calls[0][0].shape.type).toBe('POLYGON');
  });

  it('즉시모드_ON에서_확정후_늦게도착한_응답은_프리뷰를_되살리지_않는다', async () => {
    // 이슈1b — 프리뷰(점A) 표시 후 점B in-flight 중 확정 → 초기화. 이후 늦게 온 점B 응답이
    // 세대 무효화로 프리뷰를 되살리지 못한다. 빈 누적점 더블클릭도 커밋 안 함(이슈2 가드).
    const dA = makeDeferred<SegResult>();
    const dB = makeDeferred<SegResult>();
    const dConfirm = makeDeferred<SegResult>();
    const segment = vi
      .fn()
      .mockReturnValueOnce(dA.promise) // 클릭A
      .mockReturnValueOnce(dB.promise) // 클릭B (in-flight)
      .mockReturnValueOnce(dConfirm.promise); // 확정 폴백 재요청(미해소)
    const onLabelAdd = vi.fn();
    const { container, rerender } = render(
      <OverlayLayer geometry={geom} activeTool={ToolType.SAM_SEGMENT} stageRef={makeStageRef(15, 25)} segment={segment} onLabelAdd={onLabelAdd} immediateSegment />,
    );
    fireEvent.click(findRect(container)); // 클릭A (gen1)
    await act(async () => {
      dA.resolve(validPoly); // 점A 응답 → 프리뷰 표시(fresh, count1)
    });
    await waitFor(() => expect(findLines(container)).toHaveLength(1));
    rerender(
      <OverlayLayer geometry={geom} activeTool={ToolType.SAM_SEGMENT} stageRef={makeStageRef(30, 40)} segment={segment} onLabelAdd={onLabelAdd} immediateSegment />,
    );
    fireEvent.click(findRect(container)); // 클릭B (gen2) — in-flight
    // 확정(더블클릭) — 프리뷰 count1 != 누적2(stale) → 폴백 재요청 + 초기화(gen3).
    fireEvent.doubleClick(findRect(container));
    await waitFor(() => expect(findLines(container)).toHaveLength(0));
    // 늦게 도착한 점B 응답(gen2, 무효화됨) → 유령 프리뷰 재생 금지.
    await act(async () => {
      dB.resolve({ polygon: [[50, 50], [60, 60], [50, 60], [55, 55]], score: 0.9 });
    });
    expect(findLines(container)).toHaveLength(0); // 유령 프리뷰 없음
    // 이슈2 — 빈 누적점 상태에서 더블클릭 확정은 이전 폴리곤을 잘못 커밋하지 않는다.
    onLabelAdd.mockClear();
    fireEvent.doubleClick(findRect(container));
    expect(onLabelAdd).not.toHaveBeenCalled();
  });

  it('즉시모드_ON에서_프리뷰없이_확정시_기존경로로_재요청후_커밋된다', async () => {
    // 폴백 — 프리뷰가 없으면(응답 드롭 등) 확정 시 누적점 전체로 재요청 후 커밋.
    const segment = vi
      .fn()
      .mockResolvedValueOnce(null) // 클릭 → inflight 드롭(프리뷰 없음)
      .mockResolvedValueOnce(validPoly); // 확정 재요청
    const onLabelAdd = vi.fn();
    const { container } = render(
      <OverlayLayer geometry={geom} activeTool={ToolType.SAM_SEGMENT} stageRef={makeStageRef(15, 25)} segment={segment} onLabelAdd={onLabelAdd} immediateSegment />,
    );
    fireEvent.click(findRect(container));
    await waitFor(() => expect(segment).toHaveBeenCalledTimes(1));
    expect(findLines(container)).toHaveLength(0); // 프리뷰 없음
    fireEvent.keyDown(window, { key: 'Enter' });
    await waitFor(() => expect(onLabelAdd).toHaveBeenCalledTimes(1));
    expect(segment).toHaveBeenNthCalledWith(2, { points: [[15, 25]] });
  });

  it('즉시모드_ON에서_저신뢰응답은_프리뷰로_표시되지_않고_onLowConfidence가_호출된다', async () => {
    const segment = vi.fn().mockResolvedValue({ polygon: [[10, 10], [20, 20], [10, 20]], score: 0.1 });
    const onLabelAdd = vi.fn();
    const onLowConfidence = vi.fn();
    const { container } = render(
      <OverlayLayer geometry={geom} activeTool={ToolType.SAM_SEGMENT} stageRef={makeStageRef(15, 25)} segment={segment} onLabelAdd={onLabelAdd} onLowConfidence={onLowConfidence} immediateSegment />,
    );
    fireEvent.click(findRect(container));
    await waitFor(() => expect(onLowConfidence).toHaveBeenCalledTimes(1));
    expect(findLines(container)).toHaveLength(0); // 프리뷰 미표시
    expect(onLabelAdd).not.toHaveBeenCalled();
  });

  it('즉시모드_ON에서_Esc시_프리뷰와_누적점이_모두_초기화된다', async () => {
    const segment = vi.fn().mockResolvedValue(validPoly);
    const { container } = render(
      <OverlayLayer geometry={geom} activeTool={ToolType.SAM_SEGMENT} stageRef={makeStageRef(15, 25)} segment={segment} onLabelAdd={vi.fn()} immediateSegment />,
    );
    fireEvent.click(findRect(container));
    await waitFor(() => expect(findLines(container)).toHaveLength(1));
    expect(countCircles(container)).toBe(1);
    fireEvent.keyDown(window, { key: 'Escape' });
    expect(findLines(container)).toHaveLength(0);
    expect(countCircles(container)).toBe(0);
  });

  it('즉시모드_ON에서_프레임전환(segment참조교체)시_프리뷰가_초기화된다', async () => {
    const segment1 = vi.fn().mockResolvedValue(validPoly);
    const segment2 = vi.fn().mockResolvedValue(validPoly);
    const { container, rerender } = render(
      <OverlayLayer geometry={geom} activeTool={ToolType.SAM_SEGMENT} stageRef={makeStageRef(15, 25)} segment={segment1} onLabelAdd={vi.fn()} immediateSegment />,
    );
    fireEvent.click(findRect(container));
    await waitFor(() => expect(findLines(container)).toHaveLength(1));
    // 새 프레임 = 새 segment 참조 → 프리뷰/누적 초기화.
    rerender(
      <OverlayLayer geometry={geom} activeTool={ToolType.SAM_SEGMENT} stageRef={makeStageRef(15, 25)} segment={segment2} onLabelAdd={vi.fn()} immediateSegment />,
    );
    expect(findLines(container)).toHaveLength(0);
    expect(countCircles(container)).toBe(0);
  });

  it('즉시모드_ON에서_inflight중_응답이_null이면_누적점이_유지된다', async () => {
    const segment = vi.fn().mockResolvedValue(null);
    const { container } = render(
      <OverlayLayer geometry={geom} activeTool={ToolType.SAM_SEGMENT} stageRef={makeStageRef(15, 25)} segment={segment} onLabelAdd={vi.fn()} immediateSegment />,
    );
    fireEvent.click(findRect(container));
    await waitFor(() => expect(segment).toHaveBeenCalledTimes(1));
    expect(countCircles(container)).toBe(1); // 누적점 유지(null 응답이 점을 지우지 않음)
    expect(findLines(container)).toHaveLength(0); // 프리뷰 없음
  });

  // === 이월 MED 결함 — 확정이 in-flight 중이면 조용히 소실되지 않고 확정 큐잉으로 처리 ===
  it('즉시모드_ON에서_확정이_inflight중이면_조용히_소실되지_않고_처리된다', async () => {
    // isSegmenting=true(재요청이 useSam2Segment inflight 가드로 드롭될 상황)에서 확정하면,
    // 조용히 커밋 실패(작업 소실)하지 않고 확정을 큐잉했다가 in-flight 해제 시 최신 누적점
    // 전체로 재요청→커밋한다.
    const segment = vi
      .fn()
      .mockResolvedValueOnce(null) // 클릭 즉시요청 — inflight 드롭(프리뷰 없음)
      .mockResolvedValueOnce(validPoly); // 큐잉된 확정 재요청
    const onLabelAdd = vi.fn();
    const { container, rerender } = render(
      <OverlayLayer geometry={geom} activeTool={ToolType.SAM_SEGMENT} stageRef={makeStageRef(15, 25)} segment={segment} onLabelAdd={onLabelAdd} immediateSegment isSegmenting />,
    );
    fireEvent.click(findRect(container));
    await waitFor(() => expect(segment).toHaveBeenCalledTimes(1));
    expect(findLines(container)).toHaveLength(0); // 프리뷰 없음(드롭)
    // 확정(더블클릭) — isSegmenting=true → 재요청이 드롭될 상황이므로 큐잉(누적점 유지·조용한 소실 아님).
    fireEvent.doubleClick(findRect(container));
    expect(countCircles(container)).toBe(1); // 누적점 유지
    expect(onLabelAdd).not.toHaveBeenCalled();
    expect(segment).toHaveBeenCalledTimes(1); // 아직 재요청 안 함(큐 대기)
    // in-flight 해제 → 큐 처리: 최신 누적점 전체로 재요청 후 커밋.
    rerender(
      <OverlayLayer geometry={geom} activeTool={ToolType.SAM_SEGMENT} stageRef={makeStageRef(15, 25)} segment={segment} onLabelAdd={onLabelAdd} immediateSegment isSegmenting={false} />,
    );
    await waitFor(() => expect(onLabelAdd).toHaveBeenCalledTimes(1));
    expect(segment).toHaveBeenNthCalledWith(2, { points: [[15, 25]] });
    expect(onLabelAdd.mock.calls[0][0].shape.type).toBe('POLYGON');
    // 처리 후 초기화(누적점·프리뷰 제거).
    expect(countCircles(container)).toBe(0);
    expect(findLines(container)).toHaveLength(0);
  });

  it('즉시모드_ON에서_확정_큐잉후_새클릭이_오면_큐가_취소된다', async () => {
    // 큐 대기 중 사용자가 계속 그리면(새 클릭) 확정 큐를 취소하고 정상 누적/프리뷰를 이어간다.
    const segment = vi
      .fn()
      .mockResolvedValueOnce(null) // 클릭A 즉시요청 — 드롭
      .mockResolvedValueOnce(validPoly); // 클릭B 즉시요청(큐 취소 후) — 프리뷰
    const onLabelAdd = vi.fn();
    const { container, rerender } = render(
      <OverlayLayer geometry={geom} activeTool={ToolType.SAM_SEGMENT} stageRef={makeStageRef(15, 25)} segment={segment} onLabelAdd={onLabelAdd} immediateSegment isSegmenting />,
    );
    fireEvent.click(findRect(container)); // 클릭A
    await waitFor(() => expect(segment).toHaveBeenCalledTimes(1));
    fireEvent.doubleClick(findRect(container)); // 확정 → 큐잉(isSegmenting=true)
    // 새 클릭(다른 좌표) → 큐 취소 + 계속 누적.
    rerender(
      <OverlayLayer geometry={geom} activeTool={ToolType.SAM_SEGMENT} stageRef={makeStageRef(30, 40)} segment={segment} onLabelAdd={onLabelAdd} immediateSegment isSegmenting />,
    );
    fireEvent.click(findRect(container)); // 클릭B
    await waitFor(() => expect(segment).toHaveBeenCalledTimes(2));
    // in-flight 해제 → 큐가 취소됐으므로 자동 커밋되지 않는다.
    rerender(
      <OverlayLayer geometry={geom} activeTool={ToolType.SAM_SEGMENT} stageRef={makeStageRef(30, 40)} segment={segment} onLabelAdd={onLabelAdd} immediateSegment isSegmenting={false} />,
    );
    // 잠깐 대기해도 커밋 없음(큐 취소됨).
    await Promise.resolve();
    expect(onLabelAdd).not.toHaveBeenCalled();
    expect(countCircles(container)).toBe(2); // 누적점 A,B 유지
  });
});
