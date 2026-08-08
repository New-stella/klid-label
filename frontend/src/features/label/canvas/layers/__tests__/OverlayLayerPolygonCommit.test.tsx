// FE-2 회귀: 폴리곤 dblclick/단일클릭 close 시 commit 성공 여부에 따라 점 보존/소실 분기.
// - commit 성공(라벨 마스터 로딩됨) → 점 비움(Line 사라짐) + onLabelAdd 호출.
// - commit 실패(라벨 마스터 미로딩) → 점 유지(Line 남음) + onLabelAdd 미호출 + onCommitError 호출.

import { describe, expect, it, vi, beforeEach } from 'vitest';
import { fireEvent, render } from '@testing-library/react';
import { createElement, type ReactNode } from 'react';

// 라벨 마스터 로딩 상태를 테스트별로 토글한다.
let labelMastersData: Array<{ labelId: number; name: string; sortNo: number; useYn: string }> = [];

vi.mock('react-konva', () => {
  const passthrough = (name: string) => {
    const KonvaMock = ({
      children,
      dash,
      points,
      onDblClick,
      listening,
      ...rest
    }: {
      children?: ReactNode;
      dash?: unknown;
      points?: unknown;
      onDblClick?: (event: unknown) => void;
      listening?: unknown;
      [key: string]: unknown;
    }) => {
      const props: Record<string, unknown> = { 'data-konva': name, ...rest };
      if (dash !== undefined) props['data-dash'] = Array.isArray(dash) ? dash.join(',') : String(dash);
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
  useLabelMasters: () => ({ data: labelMastersData, isLoading: false, isError: false }),
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

// 클릭 좌표를 매 클릭마다 바꿔 polyPoints 를 누적하기 위한 가변 stageRef.
function makeStageRef() {
  const ref = { current: { getPointerPosition: () => ({ x: 0, y: 0 }) } };
  return ref as unknown as React.RefObject<Konva.Stage | null> & {
    current: { getPointerPosition: () => { x: number; y: number } };
  };
}

function captureRect(container: HTMLElement): HTMLElement {
  return container.querySelectorAll('[data-konva="Rect"]')[0] as HTMLElement;
}
function polyLine(container: HTMLElement): HTMLElement | null {
  // 그리던 폴리곤 Line 은 dash 가 설정된 임시 Line (data-dash 보유).
  return container.querySelector('[data-konva="Line"][data-dash]') as HTMLElement | null;
}

// 서로 다른 3점을 찍어 dblclick 으로 닫을 수 있는 polyPoints 를 만든다.
function drawThreePoints(stageRef: { current: { getPointerPosition: () => { x: number; y: number } } }, rect: HTMLElement) {
  const pts = [
    { x: 10, y: 10 },
    { x: 40, y: 10 },
    { x: 20, y: 40 },
  ];
  for (const p of pts) {
    stageRef.current.getPointerPosition = () => p;
    fireEvent.click(rect);
  }
}

describe('OverlayLayer — 폴리곤 commit 성공/실패 분기 (FE-2)', () => {
  beforeEach(() => {
    labelMastersData = [{ labelId: 7, name: '사람', sortNo: 1, useYn: 'Y' }];
  });

  it('dblclick_commit_성공시_점이_비워진다', () => {
    const onLabelAdd = vi.fn();
    const stageRef = makeStageRef();
    const { container } = render(
      <OverlayLayer geometry={geom} activeTool={ToolType.POLYGON} stageRef={stageRef} onLabelAdd={onLabelAdd} />,
    );
    const rect = captureRect(container);
    drawThreePoints(stageRef, rect);
    // 그리는 중에는 임시 Line 이 보인다.
    expect(polyLine(container)).not.toBeNull();

    // when: dblclick 으로 커밋
    fireEvent.doubleClick(rect);

    // then: 라벨 추가 + 점 비워짐(Line 사라짐)
    expect(onLabelAdd).toHaveBeenCalledTimes(1);
    expect(onLabelAdd.mock.calls[0][0].shape.type).toBe('POLYGON');
    expect(polyLine(container)).toBeNull();
  });

  it('dblclick_commit_실패(라벨마스터_미로딩)시_점이_유지된다', () => {
    labelMastersData = []; // 라벨 마스터 없음 → commit no-op
    const onLabelAdd = vi.fn();
    const onCommitError = vi.fn();
    const stageRef = makeStageRef();
    const { container } = render(
      <OverlayLayer
        geometry={geom}
        activeTool={ToolType.POLYGON}
        stageRef={stageRef}
        onLabelAdd={onLabelAdd}
        onCommitError={onCommitError}
      />,
    );
    const rect = captureRect(container);
    drawThreePoints(stageRef, rect);
    expect(polyLine(container)).not.toBeNull();

    // when: dblclick (커밋 실패)
    fireEvent.doubleClick(rect);

    // then: 라벨 미추가 + 점 유지(Line 그대로) + 사용자 안내 호출
    expect(onLabelAdd).not.toHaveBeenCalled();
    expect(polyLine(container)).not.toBeNull();
    expect(onCommitError).toHaveBeenCalledTimes(1);
  });

  it('단일클릭_close_commit_실패시_점이_유지된다', () => {
    labelMastersData = [];
    const onLabelAdd = vi.fn();
    const onCommitError = vi.fn();
    const stageRef = makeStageRef();
    const { container } = render(
      <OverlayLayer
        geometry={geom}
        activeTool={ToolType.POLYGON}
        stageRef={stageRef}
        onLabelAdd={onLabelAdd}
        onCommitError={onCommitError}
      />,
    );
    const rect = captureRect(container);
    drawThreePoints(stageRef, rect);
    // 시작점(10,10) 근처를 다시 클릭 → closePolygonIfNear 가 닫힘 판정 → commit 시도(실패)
    stageRef.current.getPointerPosition = () => ({ x: 12, y: 12 });
    fireEvent.click(rect);

    // then: 닫기 시도했으나 commit 실패 → 점 유지 + 라벨 미추가
    expect(onLabelAdd).not.toHaveBeenCalled();
    expect(polyLine(container)).not.toBeNull();
    expect(onCommitError).toHaveBeenCalledTimes(1);
  });
});
