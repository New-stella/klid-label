// Phase 3: OverlayLayer KEYPOINT 툴 — 17점 순차 클릭 배치 → shape 커밋.

import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render } from '@testing-library/react';

let labelMastersData: Array<{ labelId: number; name: string; sortNo: number; useYn: string }> = [];

vi.mock('react-konva', async () => (await import('@/test/konvaMock')).createKonvaMock());

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

function makeStageRef() {
  const ref = { current: { getPointerPosition: () => ({ x: 0, y: 0 }) } };
  return ref as unknown as React.RefObject<Konva.Stage | null> & {
    current: { getPointerPosition: () => { x: number; y: number } };
  };
}

function captureRect(container: HTMLElement): HTMLElement {
  return container.querySelectorAll('[data-konva="Rect"]')[0] as HTMLElement;
}

describe('OverlayLayer — KEYPOINT 툴 17점 배치', () => {
  beforeEach(() => {
    labelMastersData = [{ labelId: 7, name: '사람', sortNo: 1, useYn: 'Y' }];
  });

  it('OverlayLayer_KEYPOINT툴_17번_클릭시_shape_커밋', () => {
    const onLabelAdd = vi.fn();
    const stageRef = makeStageRef();
    const { container } = render(
      <OverlayLayer geometry={geom} activeTool={ToolType.KEYPOINT} stageRef={stageRef} onLabelAdd={onLabelAdd} />,
    );
    const rect = captureRect(container);

    // 서로 다른 17점을 순차 클릭.
    for (let i = 0; i < 17; i += 1) {
      stageRef.current.getPointerPosition = () => ({ x: i + 1, y: (i + 1) * 2 });
      fireEvent.click(rect);
    }

    expect(onLabelAdd).toHaveBeenCalledTimes(1);
    const label = onLabelAdd.mock.calls[0][0];
    expect(label.shape.type).toBe('KEYPOINT');
    expect(label.shape.keypoints).toHaveLength(17);
    // 각 원소는 {x,y,v}
    for (const kp of label.shape.keypoints) {
      expect(kp).toHaveProperty('x');
      expect(kp).toHaveProperty('y');
      expect(kp).toHaveProperty('v');
    }
    // 첫 점 좌표 (identity geometry)
    expect(label.shape.keypoints[0].x).toBe(1);
    expect(label.shape.keypoints[0].y).toBe(2);
  });

  it('OverlayLayer_16점만_찍으면_커밋_안됨', () => {
    const onLabelAdd = vi.fn();
    const stageRef = makeStageRef();
    const { container } = render(
      <OverlayLayer geometry={geom} activeTool={ToolType.KEYPOINT} stageRef={stageRef} onLabelAdd={onLabelAdd} />,
    );
    const rect = captureRect(container);
    for (let i = 0; i < 16; i += 1) {
      stageRef.current.getPointerPosition = () => ({ x: i + 1, y: i + 1 });
      fireEvent.click(rect);
    }
    expect(onLabelAdd).not.toHaveBeenCalled();
  });

  it('OverlayLayer_기존_POLYGON툴_회귀없음', () => {
    // KEYPOINT 추가가 POLYGON 커밋 경로를 깨지 않음.
    const onLabelAdd = vi.fn();
    const stageRef = makeStageRef();
    const { container } = render(
      <OverlayLayer geometry={geom} activeTool={ToolType.POLYGON} stageRef={stageRef} onLabelAdd={onLabelAdd} />,
    );
    const rect = captureRect(container);
    const pts = [
      { x: 10, y: 10 },
      { x: 40, y: 10 },
      { x: 20, y: 40 },
    ];
    for (const p of pts) {
      stageRef.current.getPointerPosition = () => p;
      fireEvent.click(rect);
    }
    fireEvent.doubleClick(rect);
    expect(onLabelAdd).toHaveBeenCalledTimes(1);
    expect(onLabelAdd.mock.calls[0][0].shape.type).toBe('POLYGON');
  });
});

// 회귀: onKeypointPlacingChange 파생 배선 (클릭 시퀀스 → placingIndex 실제 전달, 도구 전환 시 null화).
// OverlayLayer 내부 kptDraft·보고 effect 가 상위(인체 다이어그램 가이드)로 "지금 찍을 관절" 인덱스를
// 올바르게 통지하는지 exercise. 값이 실제 바뀔 때만 보고되므로 호출 인자 시퀀스를 검증한다.
describe('OverlayLayer — onKeypointPlacingChange 배선 회귀', () => {
  beforeEach(() => {
    labelMastersData = [{ labelId: 7, name: '사람', sortNo: 1, useYn: 'Y' }];
  });

  // onKeypointPlacingChange mock 이 지금까지 보고한 값 시퀀스 (호출 인자 목록).
  function reported(mock: ReturnType<typeof vi.fn>): Array<number | null> {
    return mock.mock.calls.map((c) => c[0] as number | null);
  }

  it('KEYPOINT툴_활성시_onKeypointPlacingChange가_0으로_보고', () => {
    const onKeypointPlacingChange = vi.fn();
    const stageRef = makeStageRef();
    render(
      <OverlayLayer
        geometry={geom}
        activeTool={ToolType.KEYPOINT}
        stageRef={stageRef}
        onKeypointPlacingChange={onKeypointPlacingChange}
      />,
    );

    // 배치 시작 전(첫 렌더 직후): 지금 찍을 관절 = 0.
    expect(onKeypointPlacingChange).toHaveBeenCalledWith(0);
    expect(onKeypointPlacingChange.mock.lastCall?.[0]).toBe(0);
  });

  it('클릭마다_onKeypointPlacingChange가_1씩_증가_보고', () => {
    const onKeypointPlacingChange = vi.fn();
    const stageRef = makeStageRef();
    const { container } = render(
      <OverlayLayer
        geometry={geom}
        activeTool={ToolType.KEYPOINT}
        stageRef={stageRef}
        onKeypointPlacingChange={onKeypointPlacingChange}
      />,
    );
    const rect = captureRect(container);

    // 3점 순차 클릭 → 각 클릭 후 placingIndex 가 1,2,3 으로 증가 보고.
    for (let i = 0; i < 3; i += 1) {
      stageRef.current.getPointerPosition = () => ({ x: i + 1, y: i + 1 });
      fireEvent.click(rect);
    }

    // 초기 0 + 클릭 1·2·3 순으로 보고 (값이 바뀔 때만 보고되므로 정확히 이 시퀀스).
    expect(reported(onKeypointPlacingChange)).toEqual([0, 1, 2, 3]);
  });

  it('도구가_KEYPOINT가_아니면_onKeypointPlacingChange_null_보고', () => {
    const onKeypointPlacingChange = vi.fn();
    const stageRef = makeStageRef();
    const { rerender } = render(
      <OverlayLayer
        geometry={geom}
        activeTool={ToolType.KEYPOINT}
        stageRef={stageRef}
        onKeypointPlacingChange={onKeypointPlacingChange}
      />,
    );
    // KEYPOINT 진입 시 0 보고 확인.
    expect(onKeypointPlacingChange.mock.lastCall?.[0]).toBe(0);

    // 도구를 KEYPOINT 가 아닌 것으로 전환 → placingIndex null 로 보고.
    rerender(
      <OverlayLayer
        geometry={geom}
        activeTool={ToolType.BBOX}
        stageRef={stageRef}
        onKeypointPlacingChange={onKeypointPlacingChange}
      />,
    );
    expect(onKeypointPlacingChange).toHaveBeenCalledWith(null);
    expect(onKeypointPlacingChange.mock.lastCall?.[0]).toBeNull();
  });

  it('17점_커밋후_placingIndex_재계산_동작', () => {
    // 17점 완료 시 kptDraft 가 리셋되어 다시 0(=1/17)부터 보고된다.
    // 이는 의도된 다중 인스턴스 "연속 배치" 동작 — 한 사람 배치 후 곧바로 다음 사람 배치 시작.
    const onLabelAdd = vi.fn();
    const onKeypointPlacingChange = vi.fn();
    const stageRef = makeStageRef();
    const { container } = render(
      <OverlayLayer
        geometry={geom}
        activeTool={ToolType.KEYPOINT}
        stageRef={stageRef}
        onLabelAdd={onLabelAdd}
        onKeypointPlacingChange={onKeypointPlacingChange}
      />,
    );
    const rect = captureRect(container);

    for (let i = 0; i < 17; i += 1) {
      stageRef.current.getPointerPosition = () => ({ x: i + 1, y: (i + 1) * 2 });
      fireEvent.click(rect);
    }

    // 17점 커밋 발생.
    expect(onLabelAdd).toHaveBeenCalledTimes(1);
    // 보고 시퀀스: 초기 0 → 클릭 1..16 → 17번째 클릭에서 커밋+리셋으로 다시 0.
    expect(reported(onKeypointPlacingChange)).toEqual([
      0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 0,
    ]);
    // 마지막 보고는 0 — 연속 배치 재시작.
    expect(onKeypointPlacingChange.mock.lastCall?.[0]).toBe(0);
  });
});
