// 결함 회귀 — Konva 의 dblclick 합성은 **시간(Konva.dblClickWindow=400ms) + 같은 shape** 만 보고
// 포인터 이동 거리를 보지 않는다. 캔버스 전체가 단일 캡처 Rect 라, 서로 다른 위치를 400ms 안에
// 클릭하기만 해도 dblclick 이 합성돼 ①폴리곤 드래프트가 통째로 삭제되고(정점이 안 찍히는 것처럼
// 보임) ②점 3개 이상이면 조기 커밋되며 ③AI 분할 프롬프트가 조기 확정됐다.
//
// 이 테스트는 **실제 브라우저 이벤트 순서**(mousedown → mouseup → click, 그리고 두 번째 클릭 뒤
// dblclick)를 그대로 시뮬레이션한다 — 구성 클릭의 누름 지점이 판정 근거이기 때문이다.

import { describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render } from '@testing-library/react';
import type Konva from 'konva';
import { createRef } from 'react';

vi.mock('react-konva', async () => (await import('@/test/konvaMock')).createKonvaMock());

vi.mock('../../../hooks/useLabelMasters', () => ({
  useLabelMasters: () => ({
    data: [{ labelId: 7, name: '사람', sortNo: 1, useYn: 'Y' }],
    isLoading: false,
    isError: false,
  }),
}));

import { OverlayLayer, type OverlayLayerHandle } from '../OverlayLayer';
import { ToolType } from '../../../types';
import type { Geometry } from '../../utils/coordinateTransformer';

const geom: Geometry = {
  image: { width: 640, height: 480 },
  canvas: { width: 640, height: 480 },
  scale: 1,
  top: 0,
  left: 0,
  angle: 0,
};

interface MutableStageRef {
  current: { getPointerPosition: () => { x: number; y: number } };
}

function makeStageRef(): MutableStageRef & React.RefObject<Konva.Stage | null> {
  const ref = { current: { getPointerPosition: () => ({ x: 0, y: 0 }) } };
  return ref as unknown as MutableStageRef & React.RefObject<Konva.Stage | null>;
}

function captureRect(container: HTMLElement): HTMLElement {
  return container.querySelectorAll('[data-konva="Rect"]')[0] as HTMLElement;
}

function draftLine(container: HTMLElement): HTMLElement | null {
  return container.querySelector('[data-konva="Line"][data-dash]');
}

function countCircles(container: HTMLElement): number {
  return container.querySelectorAll('[data-konva="Circle"]').length;
}

/** 브라우저의 클릭 1회 = mousedown → mouseup → click. 누름 지점이 더블클릭 판정 근거다. */
function clickAt(stageRef: MutableStageRef, rect: HTMLElement, p: { x: number; y: number }) {
  stageRef.current.getPointerPosition = () => p;
  fireEvent.mouseDown(rect);
  fireEvent.mouseUp(rect);
  fireEvent.click(rect);
}

describe('OverlayLayer — 합성 dblclick 으로 폴리곤 드래프트가 소실되지 않는다', () => {
  it('빠른_연속_클릭_2회는_서로_다른_위치면_정점_2개로_유지된다', () => {
    // given: 폴리곤 도구
    const onLabelAdd = vi.fn();
    const stageRef = makeStageRef();
    const { container } = render(
      <OverlayLayer
        geometry={geom}
        activeTool={ToolType.POLYGON}
        stageRef={stageRef}
        onLabelAdd={onLabelAdd}
      />,
    );
    const rect = captureRect(container);

    // when: 서로 다른 위치를 400ms 안에 연속 클릭 → Konva 가 dblclick 을 합성해 발화
    clickAt(stageRef, rect, { x: 400, y: 260 });
    clickAt(stageRef, rect, { x: 540, y: 250 });
    fireEvent.doubleClick(rect);

    // then: 두 정점이 모두 살아 있고 커밋도 일어나지 않는다
    expect(countCircles(container)).toBe(2);
    expect(draftLine(container)?.getAttribute('data-points')).toBe('400,260,540,250');
    expect(onLabelAdd).not.toHaveBeenCalled();
  });

  it('점3개_이상에서_먼_위치_합성더블클릭은_조기커밋되지_않는다', () => {
    // given: 정점 3개를 서로 다른 위치에 빠르게 찍음
    const onLabelAdd = vi.fn();
    const stageRef = makeStageRef();
    const { container } = render(
      <OverlayLayer
        geometry={geom}
        activeTool={ToolType.POLYGON}
        stageRef={stageRef}
        onLabelAdd={onLabelAdd}
      />,
    );
    const rect = captureRect(container);
    clickAt(stageRef, rect, { x: 100, y: 100 });
    clickAt(stageRef, rect, { x: 300, y: 110 });
    clickAt(stageRef, rect, { x: 200, y: 300 });

    // when: 마지막 두 클릭이 멀리 떨어진 채 합성된 dblclick
    fireEvent.doubleClick(rect);

    // then: 아직 그리는 중이므로 커밋하지 않고 드래프트도 유지
    expect(onLabelAdd).not.toHaveBeenCalled();
    expect(countCircles(container)).toBe(3);
  });

  it('진짜_더블클릭(같은_위치_빠른_2회)은_폴리곤을_정상_커밋한다', () => {
    // given: 정점 3개를 찍은 뒤 마지막 지점 근처에서 더블클릭(= 같은 자리 클릭 2회)
    const onLabelAdd = vi.fn();
    const stageRef = makeStageRef();
    const { container } = render(
      <OverlayLayer
        geometry={geom}
        activeTool={ToolType.POLYGON}
        stageRef={stageRef}
        onLabelAdd={onLabelAdd}
      />,
    );
    const rect = captureRect(container);
    clickAt(stageRef, rect, { x: 100, y: 100 });
    clickAt(stageRef, rect, { x: 300, y: 110 });
    clickAt(stageRef, rect, { x: 200, y: 300 });

    // when: 같은 자리 두 번째 누름 + dblclick
    clickAt(stageRef, rect, { x: 202, y: 301 });
    fireEvent.doubleClick(rect);

    // then: 폴리곤 커밋 + 드래프트 정리
    expect(onLabelAdd).toHaveBeenCalledTimes(1);
    expect(onLabelAdd.mock.calls[0][0].shape.type).toBe('POLYGON');
    expect(draftLine(container)).toBeNull();
    expect(countCircles(container)).toBe(0);
  });

  it('폴리곤_도구에서_느린_클릭_3~4회는_정점이_각각_추가된다', () => {
    // given / when: dblclick 합성 없이(느린 클릭) 4점
    const onLabelAdd = vi.fn();
    const stageRef = makeStageRef();
    const { container } = render(
      <OverlayLayer
        geometry={geom}
        activeTool={ToolType.POLYGON}
        stageRef={stageRef}
        onLabelAdd={onLabelAdd}
      />,
    );
    const rect = captureRect(container);
    clickAt(stageRef, rect, { x: 100, y: 100 });
    clickAt(stageRef, rect, { x: 300, y: 110 });
    clickAt(stageRef, rect, { x: 320, y: 300 });
    clickAt(stageRef, rect, { x: 120, y: 320 });

    // then: 정점 4개 누적 + 미커밋
    expect(countCircles(container)).toBe(4);
    expect(draftLine(container)?.getAttribute('data-points')).toBe(
      '100,100,300,110,320,300,120,320',
    );
    expect(onLabelAdd).not.toHaveBeenCalled();
  });

  it('F_키_점추가_경로는_기존과_동일하게_동작한다', () => {
    // given: 키보드 F(addPointAtPointer)로만 정점 배치
    const onLabelAdd = vi.fn();
    const stageRef = makeStageRef();
    const ref = createRef<OverlayLayerHandle>();
    const { container } = render(
      <OverlayLayer
        ref={ref}
        geometry={geom}
        activeTool={ToolType.POLYGON}
        stageRef={stageRef}
        onLabelAdd={onLabelAdd}
      />,
    );
    for (const p of [
      { x: 10, y: 10 },
      { x: 200, y: 20 },
      { x: 120, y: 200 },
    ]) {
      stageRef.current.getPointerPosition = () => p;
      act(() => ref.current?.addPointAtPointer());
    }
    expect(countCircles(container)).toBe(3);

    // when: Q(completePolygon)
    act(() => ref.current?.completePolygon());

    // then: 커밋 + 드래프트 정리
    expect(onLabelAdd).toHaveBeenCalledTimes(1);
    expect(onLabelAdd.mock.calls[0][0].shape.type).toBe('POLYGON');
    expect(countCircles(container)).toBe(0);
  });

  it('BBOX_드래그_동작은_기존과_동일하게_유지된다', () => {
    // given: BBOX 도구
    const onLabelAdd = vi.fn();
    const stageRef = makeStageRef();
    const { container } = render(
      <OverlayLayer
        geometry={geom}
        activeTool={ToolType.BBOX}
        stageRef={stageRef}
        onLabelAdd={onLabelAdd}
      />,
    );
    const rect = captureRect(container);

    // when: 드래그
    stageRef.current.getPointerPosition = () => ({ x: 20, y: 30 });
    fireEvent.mouseDown(rect);
    stageRef.current.getPointerPosition = () => ({ x: 120, y: 150 });
    fireEvent.mouseMove(rect);
    fireEvent.mouseUp(rect);

    // then: BBOX 커밋
    expect(onLabelAdd).toHaveBeenCalledTimes(1);
    expect(onLabelAdd.mock.calls[0][0].shape).toEqual({
      type: 'BBOX',
      left: 20,
      top: 30,
      right: 120,
      bottom: 150,
    });
  });
});

describe('OverlayLayer — 합성 dblclick 으로 AI 분할이 조기 확정되지 않는다', () => {
  it('AI분할_프롬프트_점만_빠르게_클릭해도_조기커밋되지_않는다', () => {
    // given: SAM 분할 도구
    const segment = vi.fn().mockResolvedValue({ polygon: [[1, 1]], score: 0.9 });
    const stageRef = makeStageRef();
    const { container } = render(
      <OverlayLayer
        geometry={geom}
        activeTool={ToolType.SAM_SEGMENT}
        stageRef={stageRef}
        segment={segment}
        onLabelAdd={vi.fn()}
      />,
    );
    const rect = captureRect(container);

    // when: 서로 다른 위치의 프롬프트 점을 빠르게 클릭(합성 dblclick 발화)
    clickAt(stageRef, rect, { x: 150, y: 250 });
    clickAt(stageRef, rect, { x: 300, y: 400 });
    fireEvent.doubleClick(rect);

    // then: 확정 요청이 나가지 않고 누적 점도 유지된다
    expect(segment).not.toHaveBeenCalled();
    expect(countCircles(container)).toBe(2);
  });

  it('AI분할_같은_위치_더블클릭은_확정된다', () => {
    // given: SAM 분할 도구 + 프롬프트 점 1개
    const segment = vi.fn().mockResolvedValue({ polygon: [[1, 1]], score: 0.9 });
    const stageRef = makeStageRef();
    const { container } = render(
      <OverlayLayer
        geometry={geom}
        activeTool={ToolType.SAM_SEGMENT}
        stageRef={stageRef}
        segment={segment}
        onLabelAdd={vi.fn()}
      />,
    );
    const rect = captureRect(container);
    clickAt(stageRef, rect, { x: 150, y: 250 });

    // when: 같은 자리 두 번째 누름 + dblclick
    clickAt(stageRef, rect, { x: 151, y: 250 });
    fireEvent.doubleClick(rect);

    // then: 확정 요청 1회
    expect(segment).toHaveBeenCalledTimes(1);
    expect(segment).toHaveBeenCalledWith({
      points: [
        [150, 250],
        [151, 250],
      ],
    });
  });
});

// 결함 회귀 — 확정 직후 도착하는 **트레일링 클릭**(더블클릭을 구성한 나머지 한 클릭).
//
// 사용자가 점 A 를 찍고 그 자리에서 더블클릭으로 확정하면 물리적 클릭은 3회다(A + 더블클릭 B,C).
// Konva 는 시간창 안의 앞선 두 클릭(A+B)으로 dblclick 을 먼저 합성하므로 확정이 그 시점에 끝나고,
// **남은 클릭 C 가 확정 뒤에 도착해** 방금 비워진 draft 의 첫 점으로 남는다 — 사용자가 찍은 적 없는
// 정점/프롬프트 점이라 다음 도형을 망치고, 즉시 프리뷰 모드에서는 불필요한 AI 추론 요청까지 나간다.
// 아래 테스트는 그 실제 타이밍(클릭 A → 클릭 B → dblclick → 트레일링 클릭 C)을 그대로 재현한다.
describe('OverlayLayer — 더블클릭 확정의 트레일링 클릭이 새 draft 를 오염시키지 않는다', () => {
  it('폴리곤_정점클릭_후_같은자리_더블클릭_커밋시_유령정점이_남지_않는다', () => {
    // given: 폴리곤 정점 2개를 찍고 마지막 정점 A 를 찍은 상태
    const onLabelAdd = vi.fn();
    const stageRef = makeStageRef();
    const { container } = render(
      <OverlayLayer
        geometry={geom}
        activeTool={ToolType.POLYGON}
        stageRef={stageRef}
        onLabelAdd={onLabelAdd}
      />,
    );
    const rect = captureRect(container);
    clickAt(stageRef, rect, { x: 100, y: 100 });
    clickAt(stageRef, rect, { x: 300, y: 110 });
    clickAt(stageRef, rect, { x: 200, y: 300 }); // 정점 A

    // when: 같은 자리에서 더블클릭(B → dblclick 합성) 뒤 남은 클릭 C 가 뒤늦게 도착
    clickAt(stageRef, rect, { x: 201, y: 301 }); // B
    fireEvent.doubleClick(rect);
    expect(onLabelAdd).toHaveBeenCalledTimes(1); // 커밋은 이 시점에 끝난다
    clickAt(stageRef, rect, { x: 201, y: 301 }); // C — 트레일링 클릭

    // then: 새 draft 에 유령 정점이 남지 않는다
    expect(countCircles(container)).toBe(0);
    expect(draftLine(container)).toBeNull();
    expect(onLabelAdd).toHaveBeenCalledTimes(1);
  });

  it('SAM분할_프롬프트점클릭_후_같은자리_더블클릭_확정시_유령점이_남지_않는다', () => {
    // given: SAM 분할 도구 + 프롬프트 점 A
    const segment = vi.fn().mockResolvedValue({ polygon: [[1, 1]], score: 0.9 });
    const stageRef = makeStageRef();
    const { container } = render(
      <OverlayLayer
        geometry={geom}
        activeTool={ToolType.SAM_SEGMENT}
        stageRef={stageRef}
        segment={segment}
        onLabelAdd={vi.fn()}
      />,
    );
    const rect = captureRect(container);
    clickAt(stageRef, rect, { x: 150, y: 250 }); // A

    // when: 같은 자리 더블클릭(B → dblclick 합성)으로 확정 뒤 남은 클릭 C 도착
    clickAt(stageRef, rect, { x: 151, y: 250 }); // B
    fireEvent.doubleClick(rect);
    expect(segment).toHaveBeenCalledTimes(1); // 확정 요청은 이 시점에 나간다
    clickAt(stageRef, rect, { x: 151, y: 250 }); // C — 트레일링 클릭

    // then: 유령 프롬프트 점이 누적되지 않는다
    expect(countCircles(container)).toBe(0);
  });

  it('SAM분할_즉시프리뷰_확정직후_트레일링클릭은_추가_추론요청을_보내지_않는다', () => {
    // given: 즉시 프리뷰 모드 + 프롬프트 점 A(클릭마다 프리뷰 요청)
    const segment = vi.fn().mockResolvedValue({ polygon: [[1, 1]], score: 0.9 });
    const stageRef = makeStageRef();
    const { container } = render(
      <OverlayLayer
        geometry={geom}
        activeTool={ToolType.SAM_SEGMENT}
        stageRef={stageRef}
        segment={segment}
        immediateSegment
        onLabelAdd={vi.fn()}
      />,
    );
    const rect = captureRect(container);
    clickAt(stageRef, rect, { x: 150, y: 250 }); // A
    clickAt(stageRef, rect, { x: 151, y: 250 }); // B
    fireEvent.doubleClick(rect);
    const callsAfterConfirm = segment.mock.calls.length;

    // when: 확정 직후 트레일링 클릭 C 가 도착
    clickAt(stageRef, rect, { x: 151, y: 250 }); // C

    // then: 요청이 더 나가지 않고 유령 점도 없다
    expect(segment.mock.calls.length).toBe(callsAfterConfirm);
    expect(countCircles(container)).toBe(0);
  });

  it('트레일링클릭_소비_이후_정상적인_다음_클릭은_삼켜지지_않는다', () => {
    // given: 폴리곤 커밋 + 트레일링 클릭 1회 소비까지 진행
    const onLabelAdd = vi.fn();
    const stageRef = makeStageRef();
    const { container } = render(
      <OverlayLayer
        geometry={geom}
        activeTool={ToolType.POLYGON}
        stageRef={stageRef}
        onLabelAdd={onLabelAdd}
      />,
    );
    const rect = captureRect(container);
    clickAt(stageRef, rect, { x: 100, y: 100 });
    clickAt(stageRef, rect, { x: 300, y: 110 });
    clickAt(stageRef, rect, { x: 200, y: 300 });
    clickAt(stageRef, rect, { x: 201, y: 301 });
    fireEvent.doubleClick(rect);
    clickAt(stageRef, rect, { x: 201, y: 301 }); // 트레일링 클릭 — 여기서 소비
    expect(countCircles(container)).toBe(0);

    // when: 사용자가 같은 자리에서 새 폴리곤의 첫 정점을 찍는다
    clickAt(stageRef, rect, { x: 201, y: 301 });

    // then: 정상 정점으로 추가된다(1회 소비 후 리셋)
    expect(countCircles(container)).toBe(1);
  });

  it('SAM분할_트레일링클릭_소비_이후_정상적인_다음_클릭은_삼켜지지_않는다', () => {
    // given: SAM 확정 + 트레일링 클릭 1회 소비까지 진행
    const segment = vi.fn().mockResolvedValue({ polygon: [[1, 1]], score: 0.9 });
    const stageRef = makeStageRef();
    const { container } = render(
      <OverlayLayer
        geometry={geom}
        activeTool={ToolType.SAM_SEGMENT}
        stageRef={stageRef}
        segment={segment}
        onLabelAdd={vi.fn()}
      />,
    );
    const rect = captureRect(container);
    clickAt(stageRef, rect, { x: 150, y: 250 });
    clickAt(stageRef, rect, { x: 151, y: 250 });
    fireEvent.doubleClick(rect);
    clickAt(stageRef, rect, { x: 151, y: 250 }); // 트레일링 클릭 — 여기서 소비
    expect(countCircles(container)).toBe(0);

    // when: 사용자가 같은 자리에서 새 프롬프트 점을 찍는다
    clickAt(stageRef, rect, { x: 151, y: 250 });

    // then: 정상 프롬프트 점으로 누적된다(1회 소비 후 리셋)
    expect(countCircles(container)).toBe(1);
  });
});
