// DEV_FIX HIGH-1 — 분할 확정 시 누적 클릭 무음 소실(가드 술어 ↔ 드롭 조건 불일치).
//
// 기존 단위 테스트는 `isSegmenting`(prop)과 `segment`(항상 성공 mock)를 **독립 주입**해서
// 프로덕션의 어긋난 조합(= isSegmenting=false 인데 segment 가 드롭되는 상태)을 표현할 수 없었다.
// 여기서는 **실제 useSam2Segment + 실제 store busy** 로 렌더해 그 조합을 그대로 재현한다.

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, waitFor } from '@testing-library/react';
import type Konva from 'konva';
import { createElement, useRef, type ReactNode } from 'react';

vi.mock('react-konva', () => {
  const passthrough = (name: string) => {
    const KonvaMock = ({
      children,
      dash: _dash,
      points,
      onDblClick,
      listening: _listening,
      closed: _closed,
      ...rest
    }: {
      children?: ReactNode;
      dash?: unknown;
      points?: unknown;
      onDblClick?: (event: unknown) => void;
      closed?: unknown;
      [key: string]: unknown;
    }) => {
      const props: Record<string, unknown> = { 'data-konva': name, ...rest };
      if (points !== undefined) props['data-points'] = Array.isArray(points) ? points.join(',') : String(points);
      if (onDblClick) props.onDoubleClick = onDblClick;
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
  };
});

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
import { BUSY_OVERLAY_DELAY_MS } from '../../../busyPolicy';
import { useSam2Segment } from '../../../hooks/useSam2Segment';
import { ToolType, type Label } from '../../../types';
import type { Geometry } from '../../utils/coordinateTransformer';

const SRC_SN = 4242;

const geom: Geometry = {
  image: { width: 100, height: 100 },
  canvas: { width: 100, height: 100 },
  scale: 1,
  top: 0,
  left: 0,
  angle: 0,
};

/**
 * 프로덕션과 동일한 배선 — `segment`/`isSegmenting` 을 **같은 훅**에서 받는다.
 * (둘을 독립 주입하면 이 결함 자체가 표현되지 않는다.)
 */
function Harness({
  onLabelAdd,
  pointer,
  tool = ToolType.SAM_SEGMENT,
}: {
  onLabelAdd: (label: Label) => void;
  pointer: { x: number; y: number };
  tool?: ToolType;
}) {
  const { segment, isSegmenting } = useSam2Segment(SRC_SN);
  const pointerRef = useRef(pointer);
  pointerRef.current = pointer;
  const stageRef = {
    current: { getPointerPosition: () => pointerRef.current },
  } as unknown as React.RefObject<Konva.Stage | null>;
  return (
    <OverlayLayer
      geometry={geom}
      activeTool={tool}
      stageRef={stageRef}
      segment={segment}
      isSegmenting={isSegmenting}
      onLabelAdd={onLabelAdd}
    />
  );
}

function findRect(container: HTMLElement): HTMLElement {
  return container.querySelectorAll('[data-konva="Rect"]')[0] as HTMLElement;
}

function countCircles(container: HTMLElement): number {
  return container.querySelectorAll('[data-konva="Circle"]').length;
}

/** 진행 오버레이가 이미 떠 있는 상태(작업 시작이 지연 창보다 오래됨)로 만든다. */
function ageBusyPastOverlayDelay() {
  const busy = useLabelStore.getState().busy;
  if (busy === null) throw new Error('busy 가 없습니다');
  useLabelStore.setState({
    busy: { ...busy, startedAt: busy.startedAt - (BUSY_OVERLAY_DELAY_MS + 1000) },
  });
}

describe('OverlayLayer — 다른 종류 작업 진행 중 AI 분할', () => {
  beforeEach(() => {
    useLabelStore.getState().reset();
    useLabelStore.setState({ busy: null, busyGeneration: 0 });
    useUiStore.setState({ toasts: [] });
    requestSam2Segment.mockReset();
    requestSam2Segment.mockResolvedValue({
      polygon: [
        [10, 10],
        [20, 20],
        [10, 20],
      ],
      score: 0.9,
      message: null,
    });
  });

  afterEach(() => {
    useLabelStore.getState().reset();
    useUiStore.setState({ toasts: [] });
  });

  it('다른_종류_작업_진행중_분할확정시_누적점이_보존되고_무음_소실되지_않는다', async () => {
    // given: AI 추적이 진행 중 → isSegmenting 은 false 지만 분할 요청은 배타 실행에 막힌다.
    const onLabelAdd = vi.fn();
    const { container, rerender } = render(<Harness onLabelAdd={onLabelAdd} pointer={{ x: 15, y: 25 }} />);
    fireEvent.click(findRect(container));
    rerender(<Harness onLabelAdd={onLabelAdd} pointer={{ x: 30, y: 40 }} />);
    fireEvent.click(findRect(container));
    rerender(<Harness onLabelAdd={onLabelAdd} pointer={{ x: 45, y: 55 }} />);
    fireEvent.click(findRect(container));
    expect(countCircles(container)).toBe(3);
    act(() => {
      useLabelStore.getState().beginBusy('AI_TRACK', { srcSn: SRC_SN });
    });

    // when: 확정(Enter)
    fireEvent.keyDown(window, { key: 'Enter' });
    await act(async () => {
      await Promise.resolve();
    });

    // then: 점이 지워지지도, 요청이 조용히 사라지지도 않는다(확정은 큐로 보존).
    expect(countCircles(container)).toBe(3);
    expect(requestSam2Segment).not.toHaveBeenCalled();
    expect(onLabelAdd).not.toHaveBeenCalled();

    // and: 다른 작업이 끝나면 큐잉된 확정이 누적점 전체로 실행된다(작업 소실 없음).
    await act(async () => {
      useLabelStore.getState().cancelBusy();
      await Promise.resolve();
    });
    await waitFor(() => expect(onLabelAdd).toHaveBeenCalledTimes(1));
    expect(requestSam2Segment).toHaveBeenCalledTimes(1);
    expect(requestSam2Segment.mock.calls[0][1]).toEqual({
      points: [
        [15, 25],
        [30, 40],
        [45, 55],
      ],
    });
    expect(countCircles(container)).toBe(0);
  });

  it('확정_큐_대기중_도구를_바꾸면_busy가_풀려도_유령_커밋이_없다', async () => {
    // given: 큐 대기 상태(다른 작업 진행 중 확정)
    //   ⚠ 이 시나리오의 안전성은 "초기화 effect 가 큐 처리 effect 보다 먼저 실행된다"는
    //     컴포넌트 내부 effect 선언 순서에 의존한다 — 순서가 바뀌면 조용히 깨지므로 테스트로 고정한다.
    const onLabelAdd = vi.fn();
    const { container, rerender } = render(<Harness onLabelAdd={onLabelAdd} pointer={{ x: 15, y: 25 }} />);
    fireEvent.click(findRect(container));
    act(() => {
      useLabelStore.getState().beginBusy('SAVE', { srcSn: SRC_SN });
    });
    fireEvent.keyDown(window, { key: 'Enter' });
    expect(countCircles(container)).toBe(1);

    // when: 같은 시점에 도구 전환 + busy 해제
    await act(async () => {
      useLabelStore.getState().cancelBusy();
      rerender(<Harness onLabelAdd={onLabelAdd} pointer={{ x: 15, y: 25 }} tool={ToolType.SELECT} />);
      await Promise.resolve();
    });

    // then: 사용자가 도구를 떠났으므로 확정이 자동 실행되지 않는다.
    expect(requestSam2Segment).not.toHaveBeenCalled();
    expect(onLabelAdd).not.toHaveBeenCalled();
  });

  it('오버레이가_보이는_동안에는_분할_클릭이_누적되지_않는다', () => {
    // D3 — Phase 2 의 "차단 중에도 클릭 누적 허용" 예외는 **오버레이가 없어서 사용자가 진행
    // 중임을 모른다**는 전제 위에 있었다. 오버레이가 떠 있으면 그 전제가 사라진다.
    const onLabelAdd = vi.fn();
    const { container } = render(<Harness onLabelAdd={onLabelAdd} pointer={{ x: 15, y: 25 }} />);
    act(() => {
      useLabelStore.getState().beginBusy('SAVE', { srcSn: SRC_SN });
      ageBusyPastOverlayDelay();
    });

    fireEvent.click(findRect(container));

    // 누적점이 생기지 않는다(오버레이가 상태를 보여주고 있으므로 무음이 정상 — 소실이 아니다).
    expect(countCircles(container)).toBe(0);
    expect(requestSam2Segment).not.toHaveBeenCalled();
  });

  it('오버레이_미표시_구간에서는_분할_클릭이_누적된다', () => {
    // D3 경계 반대편 — 지연 창(<300ms) 안에는 오버레이가 없다. 이때 클릭을 삼키면 사용자는
    // 자기 조작이 사라진 이유를 알 수 없다(확정 큐로 보존하는 기존 계약 유지).
    const onLabelAdd = vi.fn();
    const { container } = render(<Harness onLabelAdd={onLabelAdd} pointer={{ x: 15, y: 25 }} />);
    act(() => {
      useLabelStore.getState().beginBusy('SAVE', { srcSn: SRC_SN });
    });

    fireEvent.click(findRect(container));

    expect(countCircles(container)).toBe(1);
    expect(requestSam2Segment).not.toHaveBeenCalled();
  });

  it('ESC로_취소하면_확정_큐도_비워져_자동_발사되지_않는다', async () => {
    // N-2 — 지연 창(<300ms)에 Enter 로 큐잉된 확정이, 사용자가 ESC 로 작업을 **취소한 뒤에**
    // busy 해제를 신호로 자동 발사되던 결함. 취소는 "이 작업을 그만둔다"는 의사표시이므로
    // 그 시점의 대기 확정도 함께 사라져야 한다(누적점은 보존 — 사용자가 지운 적 없다).
    const onLabelAdd = vi.fn();
    const { container } = render(<Harness onLabelAdd={onLabelAdd} pointer={{ x: 15, y: 25 }} />);
    fireEvent.click(findRect(container));
    expect(countCircles(container)).toBe(1);
    act(() => {
      useLabelStore.getState().beginBusy('SAVE', { srcSn: SRC_SN });
    });
    // 지연 창 안이라 오버레이가 없다 → Enter 가 캔버스 확정으로 들어와 큐잉된다.
    fireEvent.keyDown(window, { key: 'Enter' });

    // when: ESC 로 진행 중 작업을 취소한다(busy 해제 = 확정 큐 effect 의 트리거).
    await act(async () => {
      fireEvent.keyDown(window, { key: 'Escape' });
      await Promise.resolve();
    });

    // then: 취소했으므로 확정이 자동 발사되지 않는다. 2회째 ESC 가 필요하지도 않다.
    await waitFor(() => expect(useLabelStore.getState().busy).toBeNull());
    expect(requestSam2Segment).not.toHaveBeenCalled();
    expect(onLabelAdd).not.toHaveBeenCalled();
    // 누적점은 그대로 — ESC 는 작업만 취소하고 사용자의 클릭은 잃지 않는다(R9/AC10).
    expect(countCircles(container)).toBe(1);
  });

  it('다른_종류_작업_진행중_박스드래그_분할도_무음_소실되지_않는다', async () => {
    // given: 저장이 진행 중
    const onLabelAdd = vi.fn();
    const { container, rerender } = render(<Harness onLabelAdd={onLabelAdd} pointer={{ x: 10, y: 10 }} />);
    act(() => {
      useLabelStore.getState().beginBusy('SAVE', { srcSn: SRC_SN });
    });

    // when: 박스 드래그로 분할 요청
    fireEvent.mouseDown(findRect(container));
    rerender(<Harness onLabelAdd={onLabelAdd} pointer={{ x: 40, y: 40 }} />);
    fireEvent.mouseMove(findRect(container));
    fireEvent.mouseUp(findRect(container));
    await act(async () => {
      await Promise.resolve();
    });

    // then: 요청도 안 나가고 아무 안내도 없는 무음 상태가 되면 안 된다.
    expect(requestSam2Segment).not.toHaveBeenCalled();
    expect(onLabelAdd).not.toHaveBeenCalled();
    const toasts = useUiStore.getState().toasts;
    expect(toasts).toHaveLength(1);
    expect(toasts[0].message).toContain('저장');
    expect(toasts[0].message).toContain('진행 중');
    expect(toasts[0].message).not.toMatch(/SAM|YOLO/i);
  });
});
