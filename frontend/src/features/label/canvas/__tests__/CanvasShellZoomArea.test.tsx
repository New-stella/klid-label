// UI-046 / SCREEN-005 §좌측 도구바 — 영역 확대(보기 조작).
//
// 이 기능의 **유일한 위험**은 보기 조작이 편집으로 새는 것이다. 같은 좌클릭 드래그가 확대와 도형
// 생성 양쪽으로 발화하면 화면을 확대했을 뿐인데 라벨이 만들어져 저장된다(데이터 오염).
// 그래서 가드의 초점은 다음 다섯이다:
//   ① 영역 드래그가 라벨을 만들지 않는다 — 편집 입력 경로가 봉인된다
//   ② 드래그가 끝나면 배율·중심이 그 영역에 맞춰진다
//   ③ 임계 미만(오클릭) 드래그는 아무 일도 하지 않는다
//   ④ 배율은 기존 줌 정책의 상·하한을 넘지 않는다
//   ⑤ 회전 중에도 같은 결과가 나온다(포인터를 회전 이전 뷰 좌표로 되돌린다)
// 여기에 ⑥ 새 prop 미지정 시 기존 렌더·동작이 그대로인지(무회귀)를 더한다.

import { describe, expect, it, beforeEach, vi } from 'vitest';
import { act, render } from '@testing-library/react';

// 모킹된 konva 노드에 전달된 props 를 **렌더 순서 그대로 한 배열에** 수집한다
// (vi.hoisted — 팩토리보다 먼저 초기화 보장). Stage 는 포인터 좌표를 흉내 내야 하므로
// ref 로 getPointerPosition 을 노출한다.
//
// ⚠ 배열은 렌더마다 누적된다. "이 레이어가 사라졌다"를 확인하려면 **마지막 렌더 구간만** 봐야 한다 —
//   전체에서 찾으면 중간 렌더의 잔상을 "아직 있다"로 잘못 읽는다(스토어 갱신과 React state 갱신이
//   같은 조작에서 서로 다른 렌더로 갈릴 수 있다). Stage 는 렌더마다 정확히 한 번 밀어 넣으므로
//   마지막 Stage 이후가 곧 마지막 렌더 구간이다.
const captured = vi.hoisted(() => ({
  nodes: [] as Array<{ kind: string; props: Record<string, unknown> }>,
  labelsLayer: [] as Array<Record<string, unknown>>,
  pointer: { x: 0, y: 0 } as { x: number; y: number } | null,
}));

// stageHandle 은 ref 를 **읽는 시점마다** 불리므로, 테스트가 도중에 바꾼 pointer 가 그대로 반영된다.
vi.mock('react-konva', async () =>
  (await import('@/test/konvaMock')).createKonvaMock({
    onNode: (name, props) => captured.nodes.push({ kind: name, props }),
    stageHandle: () => ({ getPointerPosition: () => captured.pointer }),
  }),
);

vi.mock('../layers/ImageLayer', () => ({ ImageLayer: () => null }));
vi.mock('../layers/LabelsLayer', () => ({
  LabelsLayer: (props: Record<string, unknown>) => {
    captured.labelsLayer.push(props);
    return null;
  },
}));
vi.mock('../layers/OverlayLayer', () => ({ OverlayLayer: () => null }));
vi.mock('../../hooks/useSam2Segment', () => ({
  useSam2Segment: () => ({ segment: vi.fn(), isSegmenting: false }),
}));

import { MAX_ZOOM, MIN_ZOOM, useLabelStore } from '@/stores/useLabelStore';

import { CanvasShell, ZOOM_AREA_MIN_DRAG_PX, zoomToArea } from '../CanvasShell';
import { buildGeometry } from '../utils/canvasGeometry';
import { LabelSource, ShapeType, type FrameSummary, type Label } from '../../types';

// jsdom 은 이미지를 실제로 로드하지 않는다 — 실측 100×100 로드 완료를 동기 시뮬레이션한다.
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

const frame: FrameSummary = { frameNo: 1, srcSn: 1, thumbnailUrl: '', imageUrl: 'blob:mock-frame' };

const label: Label = {
  id: 'lbl-1',
  frameNo: 1,
  classId: 1,
  className: '사람',
  labelId: 11,
  source: LabelSource.MANUAL,
  shape: { type: ShapeType.BBOX, left: 10, top: 20, right: 60, bottom: 80 },
};

/** 정사각 캔버스 + 정사각 이미지 — fitScale=2 라 기대값을 손으로 검산할 수 있다. */
const CANVAS = 200;

/** 마지막 렌더 구간(마지막 Stage 이후)의 노드 — "사라졌다"를 판정할 수 있는 유일한 범위. */
function lastRenderNodes() {
  const at = captured.nodes.map((n) => n.kind).lastIndexOf('Stage');
  return at < 0 ? [] : captured.nodes.slice(at);
}
function findLayer(name: string) {
  return lastRenderNodes().find((n) => n.kind === 'Layer' && n.props.name === name)?.props;
}
function lastRect() {
  return lastRenderNodes()
    .filter((n) => n.kind === 'Rect')
    .at(-1)?.props;
}
function stageProps() {
  return captured.nodes.filter((n) => n.kind === 'Stage').at(-1)?.props as unknown as {
    onMouseDown: (e: unknown) => void;
    onMouseMove: () => void;
    onMouseUp: () => void;
    onMouseLeave: () => void;
  };
}
/** 좌클릭 드래그 시뮬레이션 — 좌표는 **Stage(화면) 좌표**다. */
function drag(
  from: { x: number; y: number },
  to: { x: number; y: number },
  end: 'up' | 'leave' = 'up',
) {
  const evt = { button: 0, preventDefault: vi.fn() };
  act(() => {
    captured.pointer = from;
    stageProps().onMouseDown({ evt });
  });
  act(() => {
    captured.pointer = to;
    stageProps().onMouseMove();
  });
  act(() => {
    if (end === 'up') stageProps().onMouseUp();
    else stageProps().onMouseLeave();
  });
}
function view() {
  const { zoom, panX, panY } = useLabelStore.getState();
  return { zoom, panX, panY };
}

beforeEach(() => {
  captured.nodes.length = 0;
  captured.labelsLayer.length = 0;
  captured.pointer = { x: 0, y: 0 };
  useLabelStore.getState().reset();
});

describe('CanvasShell — 영역 확대는 보기 조작이지 편집이 아니다', () => {
  it('영역_드래그는_라벨을_만들지_않는다_편집_입력이_봉인된다', () => {
    const onLabelAdd = vi.fn();
    render(
      <CanvasShell
        frame={frame}
        width={CANVAS}
        height={CANVAS}
        labels={[label]}
        onLabelAdd={onLabelAdd}
        zoomAreaMode
      />,
    );

    drag({ x: 20, y: 20 }, { x: 120, y: 120 });

    // ① 라벨 생성 경로가 한 번도 발화하지 않는다.
    expect(onLabelAdd).not.toHaveBeenCalled();
    // ② 입구 자체가 닫혀 있다 — 드로잉/선택 레이어는 포인터 이벤트를 받지 않는다.
    expect(findLayer('overlay-layer')?.listening).toBe(false);
    expect(findLayer('labels-layer')?.listening).toBe(false);
    // ③ 두 번째 방어선 — 이동·리사이즈 핸들도 붙지 않는다.
    expect(captured.labelsLayer.at(-1)?.readOnly).toBe(true);
    // ④ 스토어의 라벨은 그대로다(보기 조작이 저장 축을 건드리지 않는다).
    expect(useLabelStore.getState().labels).toEqual([]);
  });

  it('선택_사각형은_순수_표시다_히트테스트에_참여하지_않는다', () => {
    render(<CanvasShell frame={frame} width={CANVAS} height={CANVAS} labels={[]} zoomAreaMode />);

    const evt = { button: 0, preventDefault: vi.fn() };
    act(() => {
      captured.pointer = { x: 40, y: 30 };
      stageProps().onMouseDown({ evt });
    });
    act(() => {
      captured.pointer = { x: 140, y: 110 };
      stageProps().onMouseMove();
    });

    const layer = findLayer('zoom-area-layer');
    expect(layer?.listening).toBe(false);
    // 드래그 방향과 무관하게 정규화된 사각형(뷰 좌표)이다.
    expect(lastRect()).toMatchObject({
      x: 40,
      y: 30,
      width: 100,
      height: 80,
      listening: false,
    });

    // 드래그가 끝나면 사각형은 사라진다(잔상 없음).
    act(() => stageProps().onMouseUp());
    expect(findLayer('zoom-area-layer')).toBeUndefined();
  });
});

describe('CanvasShell — 드래그한 영역에 배율·중심을 맞춘다', () => {
  it('드래그_후_배율과_중심이_그_영역에_맞춰진다', () => {
    render(<CanvasShell frame={frame} width={CANVAS} height={CANVAS} labels={[]} zoomAreaMode />);

    // 뷰 200×200 에서 좌상단 50×50 영역 → 배율 4배, 그 영역이 화면을 가득 채운다.
    drag({ x: 0, y: 0 }, { x: 50, y: 50 });

    expect(view()).toEqual({ zoom: 4, panX: 300, panY: 300 });

    // 선택 영역의 두 모서리가 확대 후 화면의 두 모서리로 간다(= 영역이 화면을 채운다).
    const geom = buildGeometry(
      { width: 100, height: 100 },
      { width: CANVAS, height: CANVAS },
      4,
      300,
      300,
    );
    // 확대 전 뷰 (0,0)·(50,50) 은 이미지 좌표 (0,0)·(25,25) 였다.
    expect(geom.left + 0 * geom.scale).toBeCloseTo(0);
    expect(geom.left + 25 * geom.scale).toBeCloseTo(CANVAS);
    expect(geom.top + 25 * geom.scale).toBeCloseTo(CANVAS);
  });

  it('임계_미만_드래그는_아무_일도_하지_않는다_오클릭_보호', () => {
    render(<CanvasShell frame={frame} width={CANVAS} height={CANVAS} labels={[]} zoomAreaMode />);

    // 한 축만 임계를 넘겨도 무시한다 — 얇은 띠 드래그(사실상 선긋기)를 확대로 받지 않는다.
    drag({ x: 100, y: 100 }, { x: 100 + ZOOM_AREA_MIN_DRAG_PX * 3, y: 100 + 3 });
    expect(view()).toEqual({ zoom: 1, panX: 0, panY: 0 });

    // 클릭만 한 경우(이동 0)도 마찬가지다.
    drag({ x: 100, y: 100 }, { x: 100, y: 100 });
    expect(view()).toEqual({ zoom: 1, panX: 0, panY: 0 });
  });

  it('배율은_기존_줌_정책의_상하한을_넘지_않는다', () => {
    render(<CanvasShell frame={frame} width={CANVAS} height={CANVAS} labels={[]} zoomAreaMode />);

    // 20×20 영역 → 산술적으로는 10배지만 상한(MAX_ZOOM)에서 잘린다.
    drag({ x: 80, y: 80 }, { x: 100, y: 100 });
    expect(view().zoom).toBe(MAX_ZOOM);

    // 팬도 렌더 geometry 와 같은 규칙으로 클램프된다 — 이미지가 화면 밖으로 빠지지 않는다.
    const limit = (100 * 2 * MAX_ZOOM - CANVAS) / 2;
    expect(Math.abs(view().panX)).toBeLessThanOrEqual(limit);
    expect(Math.abs(view().panY)).toBeLessThanOrEqual(limit);

    // ★상한에서 잘린 배율과 팬이 **같은 배율 기준**으로 계산돼야 한다. 팬만 잘리기 전 배율로
    //   계산하면(한계값을 공유하지 않으면) 선택한 영역이 화면 중앙에서 밀린다.
    const clamped = buildGeometry(
      { width: 100, height: 100 },
      { width: CANVAS, height: CANVAS },
      view().zoom,
      view().panX,
      view().panY,
    );
    // 드래그한 영역의 중심(뷰 90,90)은 확대 전 이미지 좌표 45 였다 → 확대 후 화면 중앙에 온다.
    expect(clamped.left + 45 * clamped.scale).toBeCloseTo(CANVAS / 2);
    expect(clamped.top + 45 * clamped.scale).toBeCloseTo(CANVAS / 2);

    // 순수 함수 축 — 하한(MIN_ZOOM) 아래로는 내려가지 않는다(축소는 이 도구의 일이 아니다).
    const geom = buildGeometry(
      { width: 100, height: 100 },
      { width: CANVAS, height: CANVAS },
      1,
      0,
      0,
    );
    const huge = zoomToArea(
      geom,
      { x: -500, y: -500, width: 1500, height: 1500 },
      MIN_ZOOM,
      MAX_ZOOM,
    );
    expect(huge?.zoom).toBe(MIN_ZOOM);
  });

  it('캔버스_밖에서_손을_떼면_확대하지_않는다_끝점을_모르는_드래그는_버린다', () => {
    render(<CanvasShell frame={frame} width={CANVAS} height={CANVAS} labels={[]} zoomAreaMode />);

    drag({ x: 10, y: 10 }, { x: 120, y: 120 }, 'leave');

    expect(view()).toEqual({ zoom: 1, panX: 0, panY: 0 });
    expect(findLayer('zoom-area-layer')).toBeUndefined();
  });
});

describe('CanvasShell — 회전 중에도 같은 영역을 확대한다', () => {
  it('회전_중_드래그는_회전_이전_뷰_좌표로_환산되어_적용된다', () => {
    // 90° 에서는 뷰(회전 전 캔버스)의 가로·세로가 뒤바뀐다 — Stage 200×400 → 뷰 400×200.
    render(
      <CanvasShell frame={frame} width={200} height={400} labels={[]} rotation={90} zoomAreaMode />,
    );

    // 뷰 좌표 (100,0)~(200,50) 에 해당하는 Stage 좌표로 끈다.
    // (뷰 → Stage: 중심 기준 +90° 회전 후 Stage 중심으로 평행이동)
    drag({ x: 200, y: 100 }, { x: 150, y: 200 });

    // 뷰 좌표계에서 같은 사각형을 끈 것과 정확히 같은 결과여야 한다(역변환이 빠지면 어긋난다).
    expect(view()).toEqual({ zoom: 4, panX: 200, panY: 300 });
  });

  it('회전_중_선택_사각형은_뷰_좌표로_그려지고_회전_변환을_함께_받는다', () => {
    render(
      <CanvasShell frame={frame} width={200} height={400} labels={[]} rotation={90} zoomAreaMode />,
    );

    const evt = { button: 0, preventDefault: vi.fn() };
    act(() => {
      captured.pointer = { x: 200, y: 100 };
      stageProps().onMouseDown({ evt });
    });
    act(() => {
      captured.pointer = { x: 150, y: 200 };
      stageProps().onMouseMove();
    });

    // Stage 좌표 (200,100)~(150,200) 은 뷰 좌표 (100,0)~(200,50) 이다.
    expect(lastRect()).toMatchObject({ x: 100, y: 0, width: 100, height: 50 });
    // 같은 회전 변환을 얹으므로 화면에서는 포인터를 따라간 자리에 정확히 그려진다.
    expect(findLayer('zoom-area-layer')).toMatchObject({
      rotation: 90,
      offsetX: 200,
      offsetY: 100,
      listening: false,
    });
  });

  it('회전_중에도_보기_조작은_열려_있고_편집만_잠긴다', () => {
    const onLabelAdd = vi.fn();
    render(
      <CanvasShell
        frame={frame}
        width={200}
        height={400}
        labels={[label]}
        onLabelAdd={onLabelAdd}
        rotation={90}
        zoomAreaMode
      />,
    );

    drag({ x: 200, y: 100 }, { x: 150, y: 200 });

    expect(onLabelAdd).not.toHaveBeenCalled();
    expect(view().zoom).toBe(4); // 확대는 됐다
    expect(findLayer('labels-layer')?.listening).toBe(false); // 편집은 잠겼다
  });
});

describe('CanvasShell — 새 prop 미지정 시 기존 동작 유지', () => {
  it('영역확대_prop_없으면_좌클릭_드래그가_확대를_일으키지_않는다', () => {
    const { getByTestId, queryByTestId } = render(
      <CanvasShell frame={frame} width={CANVAS} height={CANVAS} labels={[label]} />,
    );

    drag({ x: 0, y: 0 }, { x: 50, y: 50 });

    expect(view()).toEqual({ zoom: 1, panX: 0, panY: 0 });
    expect(findLayer('zoom-area-layer')).toBeUndefined();
    expect(queryByTestId('canvas-zoom-area-notice')).toBeNull();
    expect(getByTestId('canvas-shell')).toHaveAttribute('data-zoom-area', 'false');
    // 편집 경로는 그대로 열려 있다.
    expect(findLayer('labels-layer')?.listening).toBe(true);
    expect(findLayer('overlay-layer')?.listening).toBe(true);
    expect(captured.labelsLayer.at(-1)?.readOnly).toBe(false);
  });

  it('모드가_켜지면_왜_그리기가_안_되는지와_되돌리는_법을_화면에서_알린다', () => {
    const { getByTestId } = render(
      <CanvasShell frame={frame} width={CANVAS} height={CANVAS} labels={[]} zoomAreaMode />,
    );

    expect(getByTestId('canvas-shell')).toHaveAttribute('data-zoom-area', 'true');
    expect(getByTestId('canvas-zoom-area-notice')).toHaveTextContent('화면 맞춤');
  });
});
