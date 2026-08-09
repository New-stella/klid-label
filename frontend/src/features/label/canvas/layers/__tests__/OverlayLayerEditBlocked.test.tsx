// Phase 2 — 편집 차단은 "요청 거부"가 아니라 "입력 차단"이다.
//
// 요청 단계에서 거부하면 그때까지의 사용자 작업(드래그 궤적)을 어떻게 되돌릴지가 매번 문제가 되고,
// 큐가 있는 경로(클릭 확정)와 없는 경로(박스 드래그)의 동작이 갈려 무음 소실이 생긴다.
// 여기서는 입력 단계 차단이 실제로 성립하는지를 고정한다.

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
import type { Geometry } from '../../utils/coordinateTransformer';

const SRC_SN = 5150;

const geom: Geometry = {
  image: { width: 100, height: 100 },
  canvas: { width: 100, height: 100 },
  scale: 1,
  top: 0,
  left: 0,
  angle: 0,
};

/** 프로덕션과 동일 배선 — segment/isSegmenting 을 같은 훅에서 받는다. */
function Harness({
  onLabelAdd,
  pointer,
  tool,
  immediate = false,
  srcSn = SRC_SN,
}: {
  onLabelAdd: (label: Label) => void;
  pointer: { x: number; y: number };
  tool: ToolType;
  immediate?: boolean;
  /** 프레임 전환 재현용 — 바뀌면 OverlayLayer 의 frameKey 가 바뀐다. */
  srcSn?: number;
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
      activeTool={tool}
      stageRef={stageRef}
      segment={segment}
      srcSn={srcSn}
      isSegmenting={isSegmenting}
      immediateSegment={immediate}
      onLabelAdd={onLabelAdd}
    />
  );
}

function findRect(container: HTMLElement): HTMLElement {
  return container.querySelectorAll('[data-konva="Rect"]')[0] as HTMLElement;
}
function countRects(container: HTMLElement): number {
  return container.querySelectorAll('[data-konva="Rect"]').length;
}
function countCircles(container: HTMLElement): number {
  return container.querySelectorAll('[data-konva="Circle"]').length;
}
/**
 * 실제 브라우저와 동일한 포인터 순서(mousedown → mouseup → click)로 캔버스를 클릭한다.
 * `click` 만 쏘면 **선행 mousedown 게이트가 재현되지 않아**, 성공하는 조작에 실패 안내가 붙는
 * 결함(2-2)과 stray 누적점(2-5)을 테스트가 통과시킨다.
 */
function clickCanvas(container: HTMLElement) {
  const rect = findRect(container);
  fireEvent.mouseDown(rect);
  fireEvent.mouseUp(rect);
  fireEvent.click(rect);
}

describe('OverlayLayer — busy 중 편집 입력 차단', () => {
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

  it('busy_중에는_캔버스에_새_도형을_그릴_수_없다', async () => {
    const onLabelAdd = vi.fn();
    const { container, rerender } = render(
      <Harness onLabelAdd={onLabelAdd} pointer={{ x: 10, y: 10 }} tool={ToolType.BBOX} />,
    );
    act(() => {
      useLabelStore.getState().beginBusy('SAVE', { srcSn: SRC_SN });
    });

    // when: 박스 드래그 시도
    fireEvent.mouseDown(findRect(container));
    // then: 드래그 자체가 시작되지 않는다(임시 사각형 미생성).
    expect(countRects(container)).toBe(1);
    rerender(
      <Harness onLabelAdd={onLabelAdd} pointer={{ x: 60, y: 60 }} tool={ToolType.BBOX} />,
    );
    fireEvent.mouseMove(findRect(container));
    fireEvent.mouseUp(findRect(container));

    expect(onLabelAdd).not.toHaveBeenCalled();
    // 무음이 아니다 — 왜 안 되는지 안내한다.
    expect(useUiStore.getState().toasts).toHaveLength(1);
    expect(useUiStore.getState().toasts[0].message).toContain('진행 중');
  });

  it('busy_중에는_폴리곤_점도_찍히지_않는다', () => {
    const onLabelAdd = vi.fn();
    const { container } = render(
      <Harness onLabelAdd={onLabelAdd} pointer={{ x: 10, y: 10 }} tool={ToolType.POLYGON} />,
    );
    act(() => {
      useLabelStore.getState().beginBusy('AI_DETECT', { srcSn: SRC_SN });
    });

    fireEvent.click(findRect(container));

    expect(countCircles(container)).toBe(0);
    expect(onLabelAdd).not.toHaveBeenCalled();
  });

  it('AI분할_진행중_박스드래그는_같은_종류가_막았어도_안내된다', async () => {
    const onLabelAdd = vi.fn();
    const { container, rerender } = render(
      <Harness onLabelAdd={onLabelAdd} pointer={{ x: 10, y: 10 }} tool={ToolType.SAM_SEGMENT} />,
    );
    // given: 같은 종류(AI 분할)가 진행 중.
    act(() => {
      useLabelStore.getState().beginBusy('AI_SEGMENT', { srcSn: SRC_SN });
    });

    // when: 박스 드래그(브라우저는 드래그 뒤에도 click 을 발화한다)
    fireEvent.mouseDown(findRect(container));
    expect(countRects(container)).toBe(1); // 드래그 사각형 미생성 = 시작 안 됨
    rerender(
      <Harness onLabelAdd={onLabelAdd} pointer={{ x: 40, y: 40 }} tool={ToolType.SAM_SEGMENT} />,
    );
    fireEvent.mouseMove(findRect(container));
    fireEvent.mouseUp(findRect(container));
    fireEvent.click(findRect(container));
    await act(async () => {
      await Promise.resolve();
    });

    // then: 요청도 나가지 않고, mouseup 지점이 누적점으로 남지도 않는다(그 점은 busy 해제 후
    // 확정 큐를 타고 실제 요청에 섞인다).
    expect(requestSam2Segment).not.toHaveBeenCalled();
    expect(onLabelAdd).not.toHaveBeenCalled();
    expect(countCircles(container)).toBe(0);
    // ★ 자기 종류 무음 규칙은 "거부돼도 그 조작이 다른 경로로 성사된다"(클릭 누적 + 확정 큐)를
    //   전제로 한다. 박스 프롬프트는 큐도 복원도 없어 조작이 통째로 소멸하므로 **반드시 알린다** —
    //   무음이면 사용자는 분할이 되는 줄 알고 기다린다. AI 분할 도구 사용 중 blocker 는 대부분
    //   AI_SEGMENT 라, 이 무음은 예외가 아니라 그 도구의 지배적 경로였다.
    const toasts = useUiStore.getState().toasts;
    expect(toasts).toHaveLength(1);
    expect(toasts[0].message).toContain('진행 중');
    expect(toasts[0].message).not.toMatch(/SAM|YOLO/i);
  });

  it('드래그_시작후_작업이_끝나면_진행중이라는_사실과_다른_안내를_하지_않는다', async () => {
    // 차단 중 mousedown → 드래그 도중 busy 해제 → mouseup. 차단 여부를 재판정하지 않으면
    // "다른 작업이 진행 중입니다"(busy=null 문구)라는 사실과 다른 안내가 뜬다.
    const onLabelAdd = vi.fn();
    const { container, rerender } = render(
      <Harness onLabelAdd={onLabelAdd} pointer={{ x: 10, y: 10 }} tool={ToolType.SAM_SEGMENT} />,
    );
    act(() => {
      useLabelStore.getState().beginBusy('SAVE', { srcSn: SRC_SN });
    });
    fireEvent.mouseDown(findRect(container)); // 차단 구간에서 시작

    act(() => {
      useLabelStore.getState().cancelBusy(); // 드래그 도중 작업 종료
    });
    rerender(
      <Harness onLabelAdd={onLabelAdd} pointer={{ x: 40, y: 40 }} tool={ToolType.SAM_SEGMENT} />,
    );
    fireEvent.mouseMove(findRect(container));
    fireEvent.mouseUp(findRect(container));
    fireEvent.click(findRect(container));
    await act(async () => {
      await Promise.resolve();
    });

    // 드래그는 처리되지 않았으므로(드래프트가 없었다) 안내는 하되, 진행 중이라고 말하지 않는다.
    const toasts = useUiStore.getState().toasts;
    expect(toasts).toHaveLength(1);
    expect(toasts[0].message).not.toContain('진행 중입니다');
    expect(toasts[0].message).toContain('다시');
    expect(requestSam2Segment).not.toHaveBeenCalled();
    expect(countCircles(container)).toBe(0); // stray 누적점 없음
  });

  it('차단중_시작한_제스처가_캔버스_밖에서_끝나도_다음_프레임의_클릭을_삼키지_않는다', async () => {
    // 차단 중 mousedown 후 캔버스 밖에서 release 하면 Rect 의 mouseup 이 발화하지 않아
    // 제스처 상태(ref)가 잔존한다. 프레임 전환에서 정리하지 않으면 그 다음 mouseup 이
    // "차단 제스처"로 오판돼 뒤따르는 분할 클릭 1점이 조용히 삼켜진다.
    const onLabelAdd = vi.fn();
    const { container, rerender } = render(
      <Harness onLabelAdd={onLabelAdd} pointer={{ x: 10, y: 10 }} tool={ToolType.SAM_SEGMENT} />,
    );
    act(() => {
      useLabelStore.getState().beginBusy('SAVE', { srcSn: SRC_SN });
    });
    fireEvent.mouseDown(findRect(container)); // 차단 구간에서 시작 — mouseup 은 캔버스 밖에서 발생

    // 작업 종료 + 프레임 전환.
    act(() => {
      useLabelStore.getState().cancelBusy();
    });
    rerender(
      <Harness
        onLabelAdd={onLabelAdd}
        pointer={{ x: 40, y: 40 }}
        tool={ToolType.SAM_SEGMENT}
        srcSn={SRC_SN + 1}
      />,
    );

    // 새 프레임에서 캔버스 밖에서 시작된 드래그가 캔버스 위에서 끝난 뒤(=mousedown 없는 mouseup)
    // 정상 클릭을 한다.
    fireEvent.mouseUp(findRect(container));
    fireEvent.click(findRect(container));
    await act(async () => {
      await Promise.resolve();
    });

    // 클릭이 누적점으로 살아 있어야 한다(삼켜지지 않는다).
    expect(countCircles(container)).toBe(1);
    expect(useUiStore.getState().toasts).toHaveLength(0);
  });

  it('다른작업_진행중_박스드래그는_무음이_아니라_안내된다', async () => {
    // 박스 프롬프트는 확정 큐가 없다 — 무음 no-op 이면 사용자는 분할이 되는 줄 알고 기다린다.
    const onLabelAdd = vi.fn();
    const { container, rerender } = render(
      <Harness onLabelAdd={onLabelAdd} pointer={{ x: 10, y: 10 }} tool={ToolType.SAM_SEGMENT} />,
    );
    act(() => {
      useLabelStore.getState().beginBusy('SAVE', { srcSn: SRC_SN });
    });

    fireEvent.mouseDown(findRect(container));
    rerender(
      <Harness onLabelAdd={onLabelAdd} pointer={{ x: 40, y: 40 }} tool={ToolType.SAM_SEGMENT} />,
    );
    fireEvent.mouseMove(findRect(container));
    fireEvent.mouseUp(findRect(container));
    fireEvent.click(findRect(container));
    await act(async () => {
      await Promise.resolve();
    });

    expect(requestSam2Segment).not.toHaveBeenCalled();
    expect(countCircles(container)).toBe(0);
    const toasts = useUiStore.getState().toasts;
    expect(toasts).toHaveLength(1);
    expect(toasts[0].message).toContain('진행 중');
    // 사용자 노출 문구에 모델명 금지.
    expect(toasts[0].message).not.toMatch(/SAM|YOLO/i);
  });

  it('드래그_도중_다른작업이_시작되면_박스분할은_무음으로_사라지지_않는다', async () => {
    // mousedown 게이트는 "시작" 만 막는다 — 드래그 도중 busy 가 걸리면 mouseup 은 무조건 호출된다.
    const onLabelAdd = vi.fn();
    const { container, rerender } = render(
      <Harness onLabelAdd={onLabelAdd} pointer={{ x: 10, y: 10 }} tool={ToolType.SAM_SEGMENT} />,
    );
    fireEvent.mouseDown(findRect(container)); // 차단 전 — 드래그 시작
    expect(countRects(container)).toBe(2);
    act(() => {
      useLabelStore.getState().beginBusy('SAVE', { srcSn: SRC_SN });
    });
    rerender(
      <Harness onLabelAdd={onLabelAdd} pointer={{ x: 40, y: 40 }} tool={ToolType.SAM_SEGMENT} />,
    );
    fireEvent.mouseMove(findRect(container));
    fireEvent.mouseUp(findRect(container));
    fireEvent.click(findRect(container));
    await act(async () => {
      await Promise.resolve();
    });

    expect(requestSam2Segment).not.toHaveBeenCalled();
    expect(countCircles(container)).toBe(0); // stray 누적점 없음
    expect(useUiStore.getState().toasts).toHaveLength(1);
  });

  it('서로_다른_사유의_차단_안내는_삼켜지지_않는다', async () => {
    // P-1 — 안내 dedupe 를 **시각만** 보고 하면(구 1,000ms) 사유가 달라도 두 번째가 완전 무음이
    // 된다. 정책은 문구 키 기반 하나로 통일한다(같은 사유의 연타만 묶는다).
    const onLabelAdd = vi.fn();
    const { container, rerender } = render(
      <Harness onLabelAdd={onLabelAdd} pointer={{ x: 10, y: 10 }} tool={ToolType.SAM_SEGMENT} />,
    );

    // 1) 저장 진행 중 차단 드래그 → "저장 진행 중입니다."
    act(() => {
      useLabelStore.getState().beginBusy('SAVE', { srcSn: SRC_SN });
    });
    fireEvent.mouseDown(findRect(container));
    rerender(
      <Harness onLabelAdd={onLabelAdd} pointer={{ x: 40, y: 40 }} tool={ToolType.SAM_SEGMENT} />,
    );
    fireEvent.mouseUp(findRect(container));
    fireEvent.click(findRect(container));
    await act(async () => {
      await Promise.resolve();
    });
    expect(useUiStore.getState().toasts).toHaveLength(1);

    // 2) 곧바로(1초 안에) 다른 사유 — AI 탐지 진행 중 차단 드래그.
    act(() => {
      useLabelStore.getState().cancelBusy();
      useLabelStore.getState().beginBusy('AI_DETECT', { srcSn: SRC_SN });
    });
    fireEvent.mouseDown(findRect(container));
    rerender(
      <Harness onLabelAdd={onLabelAdd} pointer={{ x: 70, y: 70 }} tool={ToolType.SAM_SEGMENT} />,
    );
    fireEvent.mouseUp(findRect(container));
    fireEvent.click(findRect(container));
    await act(async () => {
      await Promise.resolve();
    });

    const toasts = useUiStore.getState().toasts;
    expect(toasts).toHaveLength(2);
    expect(toasts[0].message).toContain('저장');
    expect(toasts[1].message).toContain('AI 탐지');
  });

  it('busy_중_ESC로_취소해도_누적점_드래프트는_보존된다', () => {
    // Phase 3 — ESC 는 이제 **취소**다. 다만 ESC 가 tool.select 로 흘러 드래프트를 파기하던
    // 원래 동작으로 되돌아가면 안 된다: 작업만 취소되고 누적점은 그대로 남아야 한다.
    const onLabelAdd = vi.fn();
    const { container, rerender } = render(
      <Harness onLabelAdd={onLabelAdd} pointer={{ x: 15, y: 25 }} tool={ToolType.SAM_SEGMENT} />,
    );
    clickCanvas(container);
    rerender(
      <Harness onLabelAdd={onLabelAdd} pointer={{ x: 35, y: 45 }} tool={ToolType.SAM_SEGMENT} />,
    );
    clickCanvas(container);
    expect(countCircles(container)).toBe(2);

    act(() => {
      useLabelStore.getState().beginBusy('SAVE', { srcSn: SRC_SN });
    });
    fireEvent.keyDown(window, { key: 'Escape' });

    // 드래프트 보존 + 진행 중 작업은 취소된다.
    expect(countCircles(container)).toBe(2);
    expect(useLabelStore.getState().busy).toBeNull();
  });

  it('즉시그리기_중_다른작업_진행이면_프리뷰_요청이_나가지_않고_점은_누적된다', async () => {
    const onLabelAdd = vi.fn();
    const { container, rerender } = render(
      <Harness
        onLabelAdd={onLabelAdd}
        pointer={{ x: 15, y: 25 }}
        tool={ToolType.SAM_SEGMENT}
        immediate
      />,
    );
    act(() => {
      useLabelStore.getState().beginBusy('SAVE', { srcSn: SRC_SN });
    });

    clickCanvas(container);
    rerender(
      <Harness
        onLabelAdd={onLabelAdd}
        pointer={{ x: 30, y: 40 }}
        tool={ToolType.SAM_SEGMENT}
        immediate
      />,
    );
    clickCanvas(container);
    await act(async () => {
      await Promise.resolve();
    });

    // 점은 누적(작업 결과 보존)되고, 프리뷰 요청은 아예 나가지 않는다.
    expect(countCircles(container)).toBe(2);
    expect(requestSam2Segment).not.toHaveBeenCalled();
    // 정상 동선이므로 "완료 후 다시 시도" 같은 사실과 다른 안내가 붙지 않는다.
    // (선행 mousedown 이 안내를 발화하면 성공하는 조작에 실패 안내가 붙는다 — 2-2)
    expect(useUiStore.getState().toasts).toHaveLength(0);
  });

  it('즉시그리기_중_다른작업_진행에도_거부_토스트가_반복되지_않는다', async () => {
    const onLabelAdd = vi.fn();
    const { container, rerender } = render(
      <Harness
        onLabelAdd={onLabelAdd}
        pointer={{ x: 15, y: 25 }}
        tool={ToolType.SAM_SEGMENT}
        immediate
      />,
    );
    act(() => {
      useLabelStore.getState().beginBusy('SAVE', { srcSn: SRC_SN });
    });

    for (const p of [
      { x: 15, y: 25 },
      { x: 30, y: 40 },
      { x: 45, y: 55 },
    ]) {
      rerender(
        <Harness
          onLabelAdd={onLabelAdd}
          pointer={p}
          tool={ToolType.SAM_SEGMENT}
          immediate
        />,
      );
      clickCanvas(container);
    }
    await act(async () => {
      await Promise.resolve();
    });

    // 점 누적은 정상 동선이라 안내 대상이 아니다(사실과 다른 "다시 시도" 안내 금지).
    // 3회 클릭 = mousedown 3회 — 선행 mousedown 이 안내를 발화하면 여기서 걸린다.
    expect(useUiStore.getState().toasts).toHaveLength(0);
    expect(countCircles(container)).toBe(3);
  });

  it('Esc로_지운_점은_응답_도착시_복원되지_않는다', async () => {
    // given: 확정 요청이 in-flight 인 동안 사용자가 Esc 로 누적점을 지운다.
    let release: ((v: unknown) => void) | null = null;
    requestSam2Segment.mockImplementation(
      () =>
        new Promise((resolve) => {
          release = resolve;
        }),
    );
    const onLabelAdd = vi.fn();
    const { container } = render(
      <Harness onLabelAdd={onLabelAdd} pointer={{ x: 15, y: 25 }} tool={ToolType.SAM_SEGMENT} />,
    );
    clickCanvas(container);
    expect(countCircles(container)).toBe(1);

    fireEvent.keyDown(window, { key: 'Enter' });
    await waitFor(() => expect(requestSam2Segment).toHaveBeenCalledTimes(1));
    expect(countCircles(container)).toBe(0);

    // when: 작업이 해제된 뒤 사용자가 명시적으로 취소(Esc) → 그 뒤 응답이 폐기(null)되어 도착.
    // ⚠ 차단 구간의 ESC 는 드래프트 보존을 위해 막히므로(2-3), 취소는 해제 이후에만 성립한다.
    act(() => {
      useLabelStore.getState().cancelBusy();
    });
    fireEvent.keyDown(window, { key: 'Escape' });
    await act(async () => {
      release?.({ polygon: [], score: 0, message: null });
      await Promise.resolve();
      await Promise.resolve();
    });

    // then: 사용자가 지운 점이 되살아나지 않는다.
    expect(countCircles(container)).toBe(0);
  });

  it('busy_해제되면_캔버스_그리기가_즉시_복구된다', () => {
    const onLabelAdd = vi.fn();
    const { container, rerender } = render(
      <Harness onLabelAdd={onLabelAdd} pointer={{ x: 10, y: 10 }} tool={ToolType.BBOX} />,
    );
    act(() => {
      useLabelStore.getState().beginBusy('SAVE', { srcSn: SRC_SN });
    });
    fireEvent.mouseDown(findRect(container));
    expect(countRects(container)).toBe(1);

    act(() => {
      useLabelStore.getState().cancelBusy();
    });
    fireEvent.mouseDown(findRect(container));
    expect(countRects(container)).toBe(2); // 드래프트 생성 = 그리기 복구
    rerender(<Harness onLabelAdd={onLabelAdd} pointer={{ x: 60, y: 60 }} tool={ToolType.BBOX} />);
    fireEvent.mouseMove(findRect(container));
    fireEvent.mouseUp(findRect(container));
    expect(onLabelAdd).toHaveBeenCalledTimes(1);
  });
});
