// Phase 3 DEV_FIX 2차 NF-1 / NF-3 — 진행 오버레이와 캔버스 키/클릭 정책의 경계.
//
// 고정하는 것:
//  - NF-1: 오버레이가 떠 있으면 캔버스의 Enter 확정이 **오버레이 '작업 취소' 버튼에 양보**한다.
//    가로채면(preventDefault) 버튼 활성화가 취소돼, 사용자가 "취소"하려던 그 작업이 오히려
//    확정 큐에 들어가 busy 해제 시 재발사된다(취소가 정반대로 동작).
//  - NF-3: "오버레이가 떠 있는가" 판정은 **프레임 스코프**여야 한다. 실제 렌더 조건(프레임 스코프)과
//    캔버스 판정(스코프 무시)이 갈리면, 다른 프레임의 작업 때문에 오버레이가 없는데도 클릭이
//    안내 없이 사라진다(무음 드롭).

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
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
      listening?: unknown;
      closed?: unknown;
      [key: string]: unknown;
    }) => {
      const props: Record<string, unknown> = { 'data-konva': name, ...rest };
      if (points !== undefined) {
        props['data-points'] = Array.isArray(points) ? points.join(',') : String(points);
      }
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

import { isEditBlockedState, useLabelStore } from '@/stores/useLabelStore';
import { useUiStore } from '@/stores/useUiStore';

import { OverlayLayer } from '../OverlayLayer';
import { BUSY_OVERLAY_DELAY_MS } from '../../../busyPolicy';
import { BusyOverlay } from '../../../components/BusyOverlay';
import { useSam2Segment } from '../../../hooks/useSam2Segment';
import { ToolType, type Label } from '../../../types';
import type { Geometry } from '../../utils/coordinateTransformer';

const SRC_SN = 100;
const OTHER_SRC_SN = 999;

const geom: Geometry = {
  image: { width: 100, height: 100 },
  canvas: { width: 100, height: 100 },
  scale: 1,
  top: 0,
  left: 0,
  angle: 0,
};

/**
 * 프로덕션(LabelingPage)과 **같은 배선** — 오버레이 표시값을 캔버스와 동일한 프레임 스코프
 * 판정원(isEditBlockedState)에서 파생시킨다. 여기서 조건을 다시 세우면 이 테스트가 검증하려는
 * "두 판정기가 갈린다" 상황 자체를 재현하지 못한다.
 */
function Harness({
  srcSn,
  pointer,
  onLabelAdd,
}: {
  srcSn: number;
  pointer: { x: number; y: number };
  onLabelAdd: (label: Label) => void;
}) {
  const { segment, isSegmenting } = useSam2Segment(srcSn);
  const pointerRef = useRef(pointer);
  pointerRef.current = pointer;
  const stageRef = {
    current: { getPointerPosition: () => pointerRef.current },
  } as unknown as React.RefObject<Konva.Stage | null>;
  const busyKind = useLabelStore((s) => (isEditBlockedState(s, srcSn) ? (s.busy?.kind ?? null) : null));
  const busyStartedAt = useLabelStore((s) =>
    isEditBlockedState(s, srcSn) ? (s.busy?.startedAt ?? undefined) : undefined,
  );
  const cancelBusy = useLabelStore((s) => s.cancelBusy);
  return (
    <>
      <OverlayLayer
        geometry={geom}
        activeTool={ToolType.SAM_SEGMENT}
        stageRef={stageRef}
        segment={segment}
        srcSn={srcSn}
        isSegmenting={isSegmenting}
        onLabelAdd={onLabelAdd}
      />
      <BusyOverlay kind={busyKind} startedAt={busyStartedAt} onCancel={cancelBusy} />
    </>
  );
}

function findRect(container: HTMLElement): HTMLElement {
  return container.querySelectorAll('[data-konva="Rect"]')[0] as HTMLElement;
}

function countCircles(container: HTMLElement): number {
  return container.querySelectorAll('[data-konva="Circle"]').length;
}

/** 지정 프레임의 작업을 "오버레이가 이미 떠 있는" 시점(지연 창 경과)으로 시작한다. */
function startAgedBusy(srcSn: number) {
  act(() => {
    useLabelStore.getState().beginBusy('AI_SEGMENT', { srcSn });
    const busy = useLabelStore.getState().busy;
    if (busy === null) throw new Error('busy 가 없습니다');
    useLabelStore.setState({
      busy: { ...busy, startedAt: busy.startedAt - (BUSY_OVERLAY_DELAY_MS + 1000) },
    });
  });
}

async function flush() {
  await act(async () => {
    await Promise.resolve();
    await Promise.resolve();
  });
}

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

describe('NF-1 — 오버레이 취소 버튼의 Enter', () => {
  it('오버레이_취소_버튼에서_Enter로_작업이_취소된다', async () => {
    // given: 누적점 1개를 찍은 뒤 AI 분할이 시작돼 오버레이가 떠 있다(포커스는 취소 버튼).
    const user = userEvent.setup();
    const onLabelAdd = vi.fn();
    const { container } = render(
      <Harness srcSn={SRC_SN} pointer={{ x: 15, y: 25 }} onLabelAdd={onLabelAdd} />,
    );
    fireEvent.click(findRect(container));
    expect(countCircles(container)).toBe(1);
    startAgedBusy(SRC_SN);
    await screen.findByTestId('busy-overlay');
    const cancelButton = screen.getByRole('button', { name: '작업 취소' });
    expect(document.activeElement).toBe(cancelButton);

    // when: 포커스된 취소 버튼에서 Enter
    await user.keyboard('{Enter}');

    // then: 작업이 취소된다(캔버스 확정이 버튼 활성화를 가로채지 않는다).
    expect(useLabelStore.getState().busy).toBeNull();

    // and: 사용자가 취소한 그 작업이 확정 큐로 되살아나지 않는다 — busy 가 풀렸는데도 요청이 없다.
    await flush();
    expect(requestSam2Segment).not.toHaveBeenCalled();
    expect(onLabelAdd).not.toHaveBeenCalled();
    // 누적점은 보존된다(취소는 사용자의 조작을 지우지 않는다).
    expect(countCircles(container)).toBe(1);
  });

  it('오버레이가_없는_지연창에서는_Enter가_기존대로_확정한다', async () => {
    // NF-1 수정이 Enter 확정 자체를 죽이지 않았음을 고정한다(경계 반대편).
    const onLabelAdd = vi.fn();
    const { container } = render(
      <Harness srcSn={SRC_SN} pointer={{ x: 15, y: 25 }} onLabelAdd={onLabelAdd} />,
    );
    fireEvent.click(findRect(container));

    fireEvent.keyDown(window, { key: 'Enter' });
    await flush();

    expect(requestSam2Segment).toHaveBeenCalledTimes(1);
  });
});

describe('NF-3 — 오버레이 표시 판정의 프레임 스코프', () => {
  it('오버레이가_없는_프레임에서는_분할_클릭이_무음_드롭되지_않는다', () => {
    // given: 다른 프레임(999)의 작업이 진행 중 → 이 화면(100)에는 오버레이가 뜨지 않는다.
    const onLabelAdd = vi.fn();
    const { container } = render(
      <Harness srcSn={SRC_SN} pointer={{ x: 15, y: 25 }} onLabelAdd={onLabelAdd} />,
    );
    startAgedBusy(OTHER_SRC_SN);
    expect(screen.queryByTestId('busy-overlay')).not.toBeInTheDocument();

    // when
    fireEvent.click(findRect(container));

    // then: 화면에 아무 단서도 없는데 클릭만 사라지는 상태가 되면 안 된다(누적 보존).
    expect(countCircles(container)).toBe(1);
  });

  it('오버레이가_뜬_프레임에서는_분할_클릭이_누적되지_않는다', () => {
    // 경계 반대편(D3 기존 계약 무회귀) — 같은 프레임의 작업이면 오버레이가 상태를 보여준다.
    const onLabelAdd = vi.fn();
    const { container } = render(
      <Harness srcSn={SRC_SN} pointer={{ x: 15, y: 25 }} onLabelAdd={onLabelAdd} />,
    );
    startAgedBusy(SRC_SN);

    fireEvent.click(findRect(container));

    expect(countCircles(container)).toBe(0);
  });
});
