// UI-046 — 화면 표시용 회전(0/90/180/270°)과 격자 오버레이.
//
// 이 두 기능의 **유일한 위험**은 보기 축이 저장 축으로 새는 것이다. 그래서 가드의 초점은
// "화면이 돌아갔는가"가 아니라 아래 셋이다:
//   ① 회전해도 라벨 좌표(저장 payload 가 되는 값)가 한 글자도 바뀌지 않는다
//   ② 회전 중에는 편집 입력 경로가 봉인된다 — 포인터 좌표와 geometry 좌표계가 어긋나므로
//      입력을 받으면 사용자가 찍은 곳과 **다른 좌표가 저장된다**
//   ③ 격자는 순수 오버레이다 — 히트테스트(라벨 선택·드로잉)에 어떤 경로로도 참여하지 않는다
// 여기에 ④ 새 props 미지정 시 기존 렌더가 그대로인지(무회귀)를 더한다.

import { describe, expect, it, beforeEach, vi } from 'vitest';
import { render } from '@testing-library/react';
import { createElement, type ReactNode } from 'react';

// 모킹된 konva 노드에 전달된 props 를 수집한다(vi.hoisted — 팩토리보다 먼저 초기화 보장).
const captured = vi.hoisted(() => ({
  layers: [] as Array<Record<string, unknown>>,
  lines: [] as Array<Record<string, unknown>>,
  labelsLayer: [] as Array<Record<string, unknown>>,
  overlay: [] as Array<Record<string, unknown>>,
}));

vi.mock('react-konva', () => {
  const passthrough = (name: string) => {
    const KonvaMock = ({
      children,
      ...rest
    }: {
      children?: ReactNode;
      [key: string]: unknown;
    }) => {
      if (name === 'Layer') captured.layers.push(rest);
      if (name === 'Line') captured.lines.push(rest);
      return createElement('div', { 'data-konva': name }, children);
    };
    KonvaMock.displayName = `KonvaMock(${name})`;
    return KonvaMock;
  };
  return {
    Stage: passthrough('Stage'),
    Layer: passthrough('Layer'),
    Line: passthrough('Line'),
    Image: passthrough('Image'),
    Rect: passthrough('Rect'),
    Circle: passthrough('Circle'),
    Group: passthrough('Group'),
    Transformer: passthrough('Transformer'),
  };
});

vi.mock('../layers/ImageLayer', () => ({ ImageLayer: () => null }));
vi.mock('../layers/LabelsLayer', () => ({
  LabelsLayer: (props: Record<string, unknown>) => {
    captured.labelsLayer.push(props);
    return null;
  },
}));
vi.mock('../layers/OverlayLayer', () => ({
  OverlayLayer: (props: Record<string, unknown>) => {
    captured.overlay.push(props);
    return null;
  },
}));
vi.mock('../../hooks/useSam2Segment', () => ({
  useSam2Segment: () => ({ segment: vi.fn(), isSegmenting: false }),
}));

import { useLabelStore } from '@/stores/useLabelStore';

import {
  CanvasShell,
  normalizeCanvasRotation,
  rotateVectorClockwise,
  toViewPoint,
} from '../CanvasShell';
import { LabelSource, ShapeType, type FrameSummary, type Label } from '../../types';

// jsdom 은 이미지를 실제로 로드하지 않는다 — 실측 크기를 갖춘 로드 완료를 동기 시뮬레이션해
// geometry 가 확정된 상태에서 검증한다(geometry 미확정이면 레이어가 아무것도 그리지 않는다).
class FakeImage {
  onload: (() => void) | null = null;
  onerror: (() => void) | null = null;
  crossOrigin = '';
  naturalWidth = 100;
  naturalHeight = 100;
  set src(_value: string) {
    this.onload?.();
  }
}
vi.stubGlobal('Image', FakeImage as unknown as typeof Image);

const frame: FrameSummary = {
  frameNo: 1,
  srcSn: 1,
  thumbnailUrl: '',
  imageUrl: 'blob:mock-frame',
};

/** 저장 payload 가 되는 좌표를 가진 라벨 — 회전 전후로 이 값이 바뀌면 데이터 오염이다. */
const label: Label = {
  id: 'lbl-1',
  frameNo: 1,
  classId: 1,
  className: '사람',
  labelId: 11,
  source: LabelSource.MANUAL,
  shape: { type: ShapeType.BBOX, left: 10, top: 20, right: 60, bottom: 80 },
};

// 세로로 긴 캔버스 — 90° 회전 시 뷰 가로·세로가 뒤바뀌어 geometry(left/top)가 달라지므로
// "회전이 실제로 표시에 반영됐다"를 좌표 값으로 확인할 수 있다.
const CANVAS_W = 200;
const CANVAS_H = 400;

function findLayer(name: string) {
  return captured.layers.find((l) => l.name === name);
}

beforeEach(() => {
  captured.layers.length = 0;
  captured.lines.length = 0;
  captured.labelsLayer.length = 0;
  captured.overlay.length = 0;
  useLabelStore.getState().reset();
});

describe('CanvasShell — 회전(보기 전용)', () => {
  it('회전해도_라벨_좌표는_한_글자도_바뀌지_않는다_저장축_무영향', () => {
    useLabelStore.getState().setLabels([label]);
    const before = structuredClone(useLabelStore.getState().labels);

    const { rerender } = render(
      <CanvasShell
        frame={frame}
        width={CANVAS_W}
        height={CANVAS_H}
        labels={[label]}
        rotation={0}
      />,
    );
    const labelsAtZero = captured.labelsLayer.at(-1)?.labels as Label[];
    const geometryAtZero = captured.labelsLayer.at(-1)?.geometry as { left: number; top: number };

    rerender(
      <CanvasShell
        frame={frame}
        width={CANVAS_W}
        height={CANVAS_H}
        labels={[label]}
        rotation={90}
      />,
    );
    const labelsAtNinety = captured.labelsLayer.at(-1)?.labels as Label[];
    const geometryAtNinety = captured.labelsLayer.at(-1)?.geometry as { left: number; top: number };

    // ① 라벨 좌표 불변 — 캔버스로 넘어가는 값도, 스토어에 남는 값도 그대로다.
    expect(labelsAtNinety).toEqual(labelsAtZero);
    expect(labelsAtNinety[0].shape).toEqual({
      type: ShapeType.BBOX,
      left: 10,
      top: 20,
      right: 60,
      bottom: 80,
    });
    expect(useLabelStore.getState().labels).toEqual(before);

    // ② 바뀐 것은 표시(뷰 변환)뿐이다 — 90° 에서는 뷰 가로·세로가 뒤바뀌어 배치가 달라진다.
    expect(geometryAtNinety).not.toEqual(geometryAtZero);
  });

  it('회전은_레이어_변환으로만_걸린다_geometry_각도는_0을_유지한다', () => {
    render(
      <CanvasShell frame={frame} width={CANVAS_W} height={CANVAS_H} labels={[]} rotation={90} />,
    );

    // geometry.angle 에 각도를 넣으면 라벨·오버레이가 각자 점을 회전시켜 축정렬 도형의
    // 렌더·드래그 계산이 어긋난다 — 회전은 반드시 레이어 변환 축에만 있어야 한다.
    expect((captured.labelsLayer.at(-1)?.geometry as { angle: number }).angle).toBe(0);
    for (const name of ['image-layer', 'labels-layer', 'overlay-layer']) {
      expect(findLayer(name)).toMatchObject({
        rotation: 90,
        x: CANVAS_W / 2,
        y: CANVAS_H / 2,
        // 90° 에서는 뷰(회전 전 캔버스)의 가로·세로가 뒤바뀐다.
        offsetX: CANVAS_H / 2,
        offsetY: CANVAS_W / 2,
      });
    }
  });

  it('회전_중에는_편집_입력이_봉인된다_레이어_listening_off_읽기전용_안내노출', () => {
    const { getByTestId, queryByTestId } = render(
      <CanvasShell frame={frame} width={CANVAS_W} height={CANVAS_H} labels={[label]} rotation={90} />,
    );

    expect(findLayer('labels-layer')?.listening).toBe(false);
    expect(findLayer('overlay-layer')?.listening).toBe(false);
    // 두 번째 방어선 — listening 이 복구돼도 이동/리사이즈 핸들이 붙지 않는다.
    expect(captured.labelsLayer.at(-1)?.readOnly).toBe(true);
    // 왜 편집이 안 되는지 화면에서 알 수 있어야 한다.
    expect(getByTestId('canvas-rotation-notice')).toHaveTextContent('회전 보기 90°');
    expect(getByTestId('canvas-shell')).toHaveAttribute('data-rotation', '90');
    expect(queryByTestId('canvas-rotation-notice')).not.toBeNull();
  });

  it('규격_밖_회전값은_0으로_떨어진다_보기전용이라_화면을_죽이지_않는다', () => {
    expect(normalizeCanvasRotation(undefined)).toBe(0);
    expect(normalizeCanvasRotation(45)).toBe(0);
    expect(normalizeCanvasRotation(Number.NaN)).toBe(0);
    expect(normalizeCanvasRotation(360)).toBe(0);
    // 순환 입력은 4단계 안으로 접힌다.
    expect(normalizeCanvasRotation(-90)).toBe(270);
    expect(normalizeCanvasRotation(450)).toBe(90);
    expect(normalizeCanvasRotation(180)).toBe(180);

    const { getByTestId } = render(
      <CanvasShell frame={frame} width={CANVAS_W} height={CANVAS_H} labels={[]} rotation={45} />,
    );
    expect(getByTestId('canvas-shell')).toHaveAttribute('data-rotation', '0');
    expect(findLayer('labels-layer')?.rotation).toBeUndefined();
  });

  it('포인터_역변환은_회전을_정확히_되돌린다_왕복_동일', () => {
    // 화면 좌표계는 y 가 아래로 증가한다 — +90° = 시계방향.
    expect(rotateVectorClockwise(1, 0, 90)).toEqual([0, 1]);
    expect(rotateVectorClockwise(1, 0, 180)).toEqual([-1, 0]);
    expect(rotateVectorClockwise(1, 0, 270)).toEqual([0, -1]);

    const stage = { width: CANVAS_W, height: CANVAS_H };
    const view = { width: CANVAS_H, height: CANVAS_W }; // 90/270° — 뷰는 가로·세로가 뒤바뀐다
    // 회전이 없으면 손대지 않는다(무회귀).
    expect(toViewPoint({ x: 13, y: 77 }, 0, stage, stage)).toEqual({ x: 13, y: 77 });
    // 중심은 회전의 불변점이다.
    expect(toViewPoint({ x: CANVAS_W / 2, y: CANVAS_H / 2 }, 90, stage, view)).toEqual({
      x: view.width / 2,
      y: view.height / 2,
    });
    // 90° 로 돌린 점을 역변환하면 원래 뷰 좌표로 정확히 돌아온다(부동소수 찌꺼기 없음).
    const viewPoint = { x: 300, y: 40 };
    const [dx, dy] = rotateVectorClockwise(
      viewPoint.x - view.width / 2,
      viewPoint.y - view.height / 2,
      90,
    );
    const stagePoint = { x: dx + stage.width / 2, y: dy + stage.height / 2 };
    expect(toViewPoint(stagePoint, 90, stage, view)).toEqual(viewPoint);
  });
});

describe('CanvasShell — 격자 오버레이(순수 표시)', () => {
  it('격자는_히트테스트에_관여하지_않는다_레이어와_선분_모두_listening_off', () => {
    render(
      <CanvasShell frame={frame} width={CANVAS_W} height={CANVAS_H} labels={[label]} showGrid />,
    );

    const grid = findLayer('grid-layer');
    expect(grid).toBeDefined();
    expect(grid?.listening).toBe(false);
    expect(captured.lines.length).toBeGreaterThan(0);
    for (const line of captured.lines) {
      expect(line.listening).toBe(false);
    }
  });

  it('격자_토글은_라벨_레이어로_가는_값을_바꾸지_않는다_선택_드로잉_무영향', () => {
    const { rerender } = render(
      <CanvasShell
        frame={frame}
        width={CANVAS_W}
        height={CANVAS_H}
        labels={[label]}
        showGrid={false}
      />,
    );
    const off = captured.labelsLayer.at(-1);
    const offListening = {
      labels: findLayer('labels-layer')?.listening,
      overlay: findLayer('overlay-layer')?.listening,
    };

    captured.layers.length = 0;
    rerender(
      <CanvasShell frame={frame} width={CANVAS_W} height={CANVAS_H} labels={[label]} showGrid />,
    );
    const on = captured.labelsLayer.at(-1);

    expect(on?.labels).toEqual(off?.labels);
    expect(on?.geometry).toEqual(off?.geometry);
    expect(on?.readOnly).toEqual(off?.readOnly);
    // 격자를 켠 뒤에도 편집 레이어의 포인터 수신 여부가 그대로다(true → true).
    expect(findLayer('labels-layer')?.listening).toBe(offListening.labels);
    expect(findLayer('overlay-layer')?.listening).toBe(offListening.overlay);
    expect(offListening).toEqual({ labels: true, overlay: true });
  });
});

describe('CanvasShell — 새 props 미지정 시 기존 동작 유지', () => {
  it('회전_격자_props_없으면_변환도_격자레이어도_붙지_않는다', () => {
    const { getByTestId, queryByTestId } = render(
      <CanvasShell frame={frame} width={CANVAS_W} height={CANVAS_H} labels={[label]} />,
    );

    expect(getByTestId('canvas-shell')).toHaveAttribute('data-rotation', '0');
    expect(getByTestId('canvas-shell')).toHaveAttribute('data-show-grid', 'false');
    expect(queryByTestId('canvas-rotation-notice')).toBeNull();
    expect(findLayer('grid-layer')).toBeUndefined();
    expect(captured.lines).toHaveLength(0);

    // 변환 props 자체를 붙이지 않는다 — 회전 도입 전과 렌더 트리가 동일하다.
    for (const name of ['image-layer', 'labels-layer', 'overlay-layer']) {
      const layer = findLayer(name);
      expect(layer?.rotation).toBeUndefined();
      expect(layer?.offsetX).toBeUndefined();
      expect(layer?.offsetY).toBeUndefined();
    }
    // 편집 경로는 그대로 열려 있다.
    expect(findLayer('labels-layer')?.listening).toBe(true);
    expect(findLayer('overlay-layer')?.listening).toBe(true);
    expect(captured.labelsLayer.at(-1)?.readOnly).toBe(false);
  });
});
