// DEV_FIX 2차 HIGH-A / MED-C / LOW-G — 분할 확정의 **프레임 스코프**.
//
// 확정 요청은 "발사 시점 프레임" 을 캡처해야 한다. 복원 조건이 `점이 비어 있는가` 하나뿐이면
// 프레임 전환으로 비워진 상태와 사용자가 지운 상태를 구분하지 못해, 옛 프레임의 누적 클릭이
// 새 프레임에 부활하고(보라색 점) 그 상태로 확정하면 **새 프레임에 옛 좌표로 요청**이 나간다.
//
// 반대로 프레임 동일성을 `segment` 함수 참조로 판정하면 경계 세밀함 슬라이더 조절만으로도
// 참조가 바뀌어(CanvasShell useCallback) 큐잉된 확정이 조용히 폐기된다(LOW-G).

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, waitFor } from '@testing-library/react';
import type Konva from 'konva';
import { useRef } from 'react';

vi.mock('react-konva', async () => (await import('@/test/konvaMock')).createKonvaMock());

vi.mock('../../../hooks/useLabelMasters', () => ({
  useLabelMasters: () => ({
    data: [{ labelId: 7, name: '사람', sortNo: 1, useYn: 'Y' }],
    isLoading: false,
    isError: false,
  }),
}));

const requestSam2Segment = vi.fn();
vi.mock('../../../api', async (importOriginal) => {
  const actual = await importOriginal<Record<string, unknown>>();
  return { ...actual, requestSam2Segment: (...args: unknown[]) => requestSam2Segment(...args) };
});

import { useLabelStore } from '@/stores/useLabelStore';
import { useUiStore } from '@/stores/useUiStore';

import { OverlayLayer } from '../OverlayLayer';
import { useSam2Segment } from '../../../hooks/useSam2Segment';
import { ToolType, type Label } from '../../../types';
import type { Sam2SegmentRequest, Sam2SegmentResponse } from '../../../api';
import type { Geometry } from '../../utils/coordinateTransformer';

const geom: Geometry = {
  image: { width: 100, height: 100 },
  canvas: { width: 100, height: 100 },
  scale: 1,
  top: 0,
  left: 0,
  angle: 0,
};

const OK_RESULT: Sam2SegmentResponse = {
  polygon: [
    [10, 10],
    [20, 20],
    [10, 20],
  ],
  score: 0.9,
  message: null,
};

/** 외부에서 resolve/reject 를 제어하는 지연 프라미스. */
function deferred<T>() {
  let resolve!: (v: T) => void;
  let reject!: (e: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

/** 프로덕션과 동일 배선 — segment/isSegmenting 을 같은 훅에서 받고 srcSn 을 프레임 키로 넘긴다. */
function Harness({
  srcSn,
  pointer,
  onLabelAdd,
  onCommitError,
}: {
  srcSn: number;
  pointer: { x: number; y: number };
  onLabelAdd: (label: Label) => void;
  onCommitError?: (message: string) => void;
}) {
  const { segment, isSegmenting } = useSam2Segment(srcSn);
  const pointerRef = useRef(pointer);
  pointerRef.current = pointer;
  const stageRef = {
    current: { getPointerPosition: () => pointerRef.current },
  } as unknown as React.RefObject<Konva.Stage | null>;
  return (
    <OverlayLayer
      geometry={geom}
      activeTool={ToolType.SAM_SEGMENT}
      stageRef={stageRef}
      segment={segment}
      srcSn={srcSn}
      isSegmenting={isSegmenting}
      onLabelAdd={onLabelAdd}
      onCommitError={onCommitError}
    />
  );
}

function findRect(container: HTMLElement): HTMLElement {
  return container.querySelectorAll('[data-konva="Rect"]')[0] as HTMLElement;
}

function countCircles(container: HTMLElement): number {
  return container.querySelectorAll('[data-konva="Circle"]').length;
}

describe('OverlayLayer — 분할 확정의 프레임 스코프', () => {
  beforeEach(() => {
    useLabelStore.getState().reset();
    useLabelStore.setState({ busy: null, busyGeneration: 0 });
    useUiStore.setState({ toasts: [] });
    requestSam2Segment.mockReset();
  });

  afterEach(() => {
    useLabelStore.getState().reset();
    useUiStore.setState({ toasts: [] });
  });

  it('확정_in_flight_중_프레임을_전환하면_이전_프레임의_누적점이_복원되지_않는다', async () => {
    // given: 프레임 100 에서 2점 누적 후 확정 → 요청 in-flight
    const net = deferred<Sam2SegmentResponse>();
    requestSam2Segment.mockReturnValue(net.promise);
    const onLabelAdd = vi.fn();
    const { container, rerender } = render(
      <Harness srcSn={100} pointer={{ x: 15, y: 25 }} onLabelAdd={onLabelAdd} />,
    );
    fireEvent.click(findRect(container));
    rerender(<Harness srcSn={100} pointer={{ x: 30, y: 40 }} onLabelAdd={onLabelAdd} />);
    fireEvent.click(findRect(container));
    expect(countCircles(container)).toBe(2);
    fireEvent.keyDown(window, { key: 'Enter' });
    expect(countCircles(container)).toBe(0); // 확정 즉시 비움(시각적 확정)

    // when: 응답 전에 프레임 200 으로 전환 → 그 뒤 프레임 100 응답 도착(폐기)
    rerender(<Harness srcSn={200} pointer={{ x: 30, y: 40 }} onLabelAdd={onLabelAdd} />);
    await act(async () => {
      net.resolve(OK_RESULT);
      await Promise.resolve();
    });

    // then: 옛 프레임의 클릭이 새 프레임에 부활하지 않는다(폐기 결과도 커밋되지 않는다).
    expect(countCircles(container)).toBe(0);
    expect(onLabelAdd).not.toHaveBeenCalled();
  });

  it('확정_in_flight_중_프레임_전환_후_확정하면_이전_프레임_좌표로_요청되지_않는다', async () => {
    // given: 위와 동일 — 프레임 100 확정이 in-flight 인 채로 프레임 200 진입
    const net = deferred<Sam2SegmentResponse>();
    requestSam2Segment.mockReturnValue(net.promise);
    const onLabelAdd = vi.fn();
    const { container, rerender } = render(
      <Harness srcSn={100} pointer={{ x: 15, y: 25 }} onLabelAdd={onLabelAdd} />,
    );
    fireEvent.click(findRect(container));
    fireEvent.keyDown(window, { key: 'Enter' });
    rerender(<Harness srcSn={200} pointer={{ x: 60, y: 70 }} onLabelAdd={onLabelAdd} />);
    await act(async () => {
      net.resolve(OK_RESULT);
      await Promise.resolve();
    });

    // when: 새 프레임에서 확정(Enter)
    fireEvent.keyDown(window, { key: 'Enter' });
    await act(async () => {
      await Promise.resolve();
    });

    // then: 새 프레임에 옛 프레임 좌표로 요청이 나가지 않는다(요청은 프레임 100 것 1회뿐).
    expect(requestSam2Segment).toHaveBeenCalledTimes(1);
    expect(requestSam2Segment.mock.calls[0][0]).toBe(100);
    const sentToFrame200 = requestSam2Segment.mock.calls.filter((c) => c[0] === 200);
    expect(sentToFrame200).toHaveLength(0);
  });

  it('확정_요청이_예외로_실패하면_누적점이_복원되고_실패가_사용자_언어로_안내된다', async () => {
    // given: 서버 오류로 요청이 throw 되는 상황(응답 null 경로가 아니다)
    requestSam2Segment.mockRejectedValue(new Error('500 /v1/frames/100/sam2-segment failed'));
    const onLabelAdd = vi.fn();
    const onCommitError = vi.fn();
    const { container, rerender } = render(
      <Harness
        srcSn={100}
        pointer={{ x: 15, y: 25 }}
        onLabelAdd={onLabelAdd}
        onCommitError={onCommitError}
      />,
    );
    fireEvent.click(findRect(container));
    rerender(
      <Harness
        srcSn={100}
        pointer={{ x: 30, y: 40 }}
        onLabelAdd={onLabelAdd}
        onCommitError={onCommitError}
      />,
    );
    fireEvent.click(findRect(container));

    // when
    fireEvent.keyDown(window, { key: 'Enter' });
    await act(async () => {
      await Promise.resolve();
      await Promise.resolve();
    });

    // then: 클릭이 조용히 사라지지 않고(복원), 내부 오류 원문은 노출하지 않는다.
    await waitFor(() => expect(countCircles(container)).toBe(2));
    expect(onCommitError).toHaveBeenCalledTimes(1);
    const message = onCommitError.mock.calls[0][0] as string;
    expect(message).toContain('AI 분할');
    expect(message).not.toContain('500');
    expect(message).not.toContain('sam2-segment');
  });
});

describe('OverlayLayer — 같은 프레임에서 요청 함수만 교체(경계 세밀함 조절)', () => {
  beforeEach(() => {
    useLabelStore.getState().reset();
    useLabelStore.setState({ busy: null, busyGeneration: 0 });
    useUiStore.setState({ toasts: [] });
  });

  afterEach(() => {
    useLabelStore.getState().reset();
  });

  it('큐잉된_확정이_요청함수_교체로_폐기되지_않는다', async () => {
    // given: 다른 작업(저장) 진행 중 확정 → 큐잉
    const makeSegment = () =>
      vi.fn((_payload: Omit<Sam2SegmentRequest, 'srcSn'>) =>
        Promise.resolve<Sam2SegmentResponse | null>(OK_RESULT),
      );
    const segment1 = makeSegment();
    const segment2 = makeSegment();
    const stageRef = {
      current: { getPointerPosition: () => ({ x: 15, y: 25 }) },
    } as unknown as React.RefObject<Konva.Stage | null>;
    const onLabelAdd = vi.fn();
    const view = (seg: typeof segment1) => (
      <OverlayLayer
        geometry={geom}
        activeTool={ToolType.SAM_SEGMENT}
        stageRef={stageRef}
        segment={seg}
        srcSn={100}
        onLabelAdd={onLabelAdd}
      />
    );
    const { container, rerender } = render(view(segment1));
    fireEvent.click(findRect(container));
    act(() => {
      useLabelStore.getState().beginBusy('SAVE', { srcSn: 100 });
    });
    fireEvent.keyDown(window, { key: 'Enter' });
    expect(countCircles(container)).toBe(1); // 큐잉 — 점 보존

    // when: 같은 프레임에서 경계 세밀함만 조절해 요청 함수 참조가 교체된 뒤 저장이 끝난다
    rerender(view(segment2));
    await act(async () => {
      useLabelStore.getState().cancelBusy();
      await Promise.resolve();
    });

    // then: 큐잉된 확정이 새 요청 함수로 실행된다(조용한 폐기 없음).
    await waitFor(() => expect(segment2).toHaveBeenCalledTimes(1));
    expect(segment2.mock.calls[0][0]).toEqual({ points: [[15, 25]] });
    expect(segment1).not.toHaveBeenCalled();
  });
});
