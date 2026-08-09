// Phase 8: OverlayLayer imperative handle — 키보드 F(점 추가)/Q(완성)가 마우스와 동일
// 폴리곤 로직을 공유하도록 useImperativeHandle 로 노출한 명령 핸들(addPointAtPointer/completePolygon)을
// 검증한다. 또한 도구 이탈 시 진행 중 polyPoints draft 가 초기화되는지(회귀) 확인.

import { createRef } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render } from '@testing-library/react';

let labelMastersData: Array<{ labelId: number; name: string; sortNo: number; useYn: string }> = [];

vi.mock('react-konva', async () => (await import('@/test/konvaMock')).createKonvaMock());

vi.mock('../../../hooks/useLabelMasters', () => ({
  useLabelMasters: () => ({ data: labelMastersData, isLoading: false, isError: false }),
}));

import type Konva from 'konva';

import { OverlayLayer, type OverlayLayerHandle } from '../OverlayLayer';
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

function polyLine(container: HTMLElement): HTMLElement | null {
  return container.querySelector('[data-konva="Line"][data-dash]') as HTMLElement | null;
}

// 지정한 좌표 목록을 imperative addPointAtPointer 로 순차 추가.
// handle 은 매 렌더 재생성되므로(useImperativeHandle) ref 에서 매번 최신 handle 을 읽는다.
function addPoints(
  stageRef: { current: { getPointerPosition: () => { x: number; y: number } } },
  ref: React.RefObject<OverlayLayerHandle>,
  pts: Array<{ x: number; y: number }>,
) {
  for (const p of pts) {
    act(() => {
      stageRef.current.getPointerPosition = () => p;
      ref.current!.addPointAtPointer();
    });
  }
}

describe('OverlayLayer — imperative 폴리곤 핸들 (Phase 8 F/Q 배선)', () => {
  beforeEach(() => {
    labelMastersData = [{ labelId: 7, name: '사람', sortNo: 1, useYn: 'Y' }];
  });

  it('addPointAtPointer_POLYGON도구_점추가시_draft_Line_노출', () => {
    const ref = createRef<OverlayLayerHandle>();
    const stageRef = makeStageRef();
    const { container } = render(
      <OverlayLayer ref={ref} geometry={geom} activeTool={ToolType.POLYGON} stageRef={stageRef} />,
    );
    expect(polyLine(container)).toBeNull();
    addPoints(stageRef, ref, [
      { x: 10, y: 10 },
      { x: 40, y: 10 },
    ]);
    // 2점 이상이면 임시 폴리곤 Line 이 그려진다(마우스 클릭과 동일 경로).
    expect(polyLine(container)).not.toBeNull();
  });

  it('completePolygon_충분한_점이면_커밋되고_draft_비워짐', () => {
    const ref = createRef<OverlayLayerHandle>();
    const onLabelAdd = vi.fn();
    const stageRef = makeStageRef();
    const { container } = render(
      <OverlayLayer
        ref={ref}
        geometry={geom}
        activeTool={ToolType.POLYGON}
        stageRef={stageRef}
        onLabelAdd={onLabelAdd}
      />,
    );
    addPoints(stageRef, ref, [
      { x: 10, y: 10 },
      { x: 40, y: 10 },
      { x: 20, y: 40 },
    ]);
    expect(polyLine(container)).not.toBeNull();

    act(() => ref.current!.completePolygon());

    expect(onLabelAdd).toHaveBeenCalledTimes(1);
    expect(onLabelAdd.mock.calls[0][0].shape.type).toBe('POLYGON');
    expect(polyLine(container)).toBeNull();
  });

  it('completePolygon_점_부족시_no_op_draft_유지', () => {
    const ref = createRef<OverlayLayerHandle>();
    const onLabelAdd = vi.fn();
    const stageRef = makeStageRef();
    const { container } = render(
      <OverlayLayer
        ref={ref}
        geometry={geom}
        activeTool={ToolType.POLYGON}
        stageRef={stageRef}
        onLabelAdd={onLabelAdd}
      />,
    );
    // 2점만 추가(폴리곤 성립 불가).
    addPoints(stageRef, ref, [
      { x: 10, y: 10 },
      { x: 40, y: 10 },
    ]);
    expect(polyLine(container)).not.toBeNull();

    act(() => ref.current!.completePolygon());

    // 커밋 안 됨 + 키보드 오조작 방지 위해 draft 유지.
    expect(onLabelAdd).not.toHaveBeenCalled();
    expect(polyLine(container)).not.toBeNull();
  });

  it('addPointAtPointer_POLYGON_도구가_아니면_no_op', () => {
    const ref = createRef<OverlayLayerHandle>();
    const onLabelAdd = vi.fn();
    const stageRef = makeStageRef();
    const { container } = render(
      <OverlayLayer
        ref={ref}
        geometry={geom}
        activeTool={ToolType.BBOX}
        stageRef={stageRef}
        onLabelAdd={onLabelAdd}
      />,
    );
    addPoints(stageRef, ref, [
      { x: 10, y: 10 },
      { x: 40, y: 10 },
    ]);
    // BBOX 도구에서는 폴리곤 점 미추가.
    expect(polyLine(container)).toBeNull();
    act(() => ref.current!.completePolygon());
    expect(onLabelAdd).not.toHaveBeenCalled();
  });

  it('POLYGON_도구_이탈시_진행중_polyPoints_draft_초기화', () => {
    const ref = createRef<OverlayLayerHandle>();
    const stageRef = makeStageRef();
    const { container, rerender } = render(
      <OverlayLayer ref={ref} geometry={geom} activeTool={ToolType.POLYGON} stageRef={stageRef} />,
    );
    addPoints(stageRef, ref, [
      { x: 10, y: 10 },
      { x: 40, y: 10 },
    ]);
    expect(polyLine(container)).not.toBeNull();

    // 도구를 폴리곤이 아닌 것으로 전환 → draft 자동 초기화.
    rerender(<OverlayLayer ref={ref} geometry={geom} activeTool={ToolType.BBOX} stageRef={stageRef} />);
    expect(polyLine(container)).toBeNull();
  });
});

// 마우스 경로 회귀: onClick/onDblClick 을 명명 함수로 추출한 뒤에도 기존 마우스 그리기가
// 동일하게 동작해야 한다(키보드와 함수 공유). 시작점 근접 close 도 유지.
describe('OverlayLayer — 마우스 폴리곤 그리기 회귀(함수 추출 후)', () => {
  beforeEach(() => {
    labelMastersData = [{ labelId: 7, name: '사람', sortNo: 1, useYn: 'Y' }];
  });

  it('마우스_클릭_3점_dblclick_커밋_유지', () => {
    const onLabelAdd = vi.fn();
    const stageRef = makeStageRef();
    const { container } = render(
      <OverlayLayer geometry={geom} activeTool={ToolType.POLYGON} stageRef={stageRef} onLabelAdd={onLabelAdd} />,
    );
    const rect = container.querySelectorAll('[data-konva="Rect"]')[0] as HTMLElement;
    for (const p of [
      { x: 10, y: 10 },
      { x: 40, y: 10 },
      { x: 20, y: 40 },
    ]) {
      stageRef.current.getPointerPosition = () => p;
      fireEvent.click(rect);
    }
    fireEvent.doubleClick(rect);
    expect(onLabelAdd).toHaveBeenCalledTimes(1);
    expect(onLabelAdd.mock.calls[0][0].shape.type).toBe('POLYGON');
  });
});
