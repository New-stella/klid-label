// R17 이슈6: Stage onWheel — 커서 기준 줌 + clamp + preventDefault.

import { describe, expect, it, beforeEach, vi } from 'vitest';
import { render } from '@testing-library/react';

let wheelHandler: ((e: any) => void) | undefined;

vi.mock('react-konva', () => {
  const React = require('react');
  const passthrough = (name: string) => {
    return ({ children, onWheel, ...rest }: any) => {
      if (name === 'Stage' && onWheel) wheelHandler = onWheel;
      return React.createElement('div', { 'data-konva': name, ...rest }, children);
    };
  };
  return {
    Stage: passthrough('Stage'),
    Layer: passthrough('Layer'),
  };
});

vi.mock('../layers/ImageLayer', () => ({ ImageLayer: () => null }));
vi.mock('../layers/LabelsLayer', () => ({ LabelsLayer: () => null }));
vi.mock('../layers/OverlayLayer', () => ({ OverlayLayer: () => null }));
vi.mock('../../hooks/useSam2Segment', () => ({
  useSam2Segment: () => ({ segment: vi.fn() }),
}));

import { CanvasShell } from '../CanvasShell';
import { useLabelStore } from '@/stores/useLabelStore';
import type { FrameSummary } from '../../types';

// jsdom 은 실제 이미지를 로드하지 않으므로, naturalWidth/Height 를 갖춘 로드 완료를 동기
// 시뮬레이션한다. CanvasShell 은 이제 하드코딩이 아닌 로드된 이미지의 실측 네이티브 픽셀로
// geometry 를 산출하므로(좌표 어긋남 버그 수정), 휠 줌 검증에도 로드된 이미지가 전제된다.
class FakeImage {
  onload: (() => void) | null = null;
  onerror: (() => void) | null = null;
  crossOrigin = '';
  naturalWidth = 100;
  naturalHeight = 100;
  set src(_value: string) {
    // onload 는 src 설정 이전에 할당되므로(CanvasShell) 동기 발화 가능 → geometry 즉시 확정.
    this.onload?.();
  }
}
vi.stubGlobal('Image', FakeImage as unknown as typeof Image);

const frame: FrameSummary = {
  frameNo: 1,
  srcSn: 1,
  thumbnailUrl: '',
  // 실측 크기(naturalWidth/Height=100)는 FakeImage 가 공급 — imageUrl 은 로드 트리거용 non-empty.
  imageUrl: 'blob:mock-frame',
};

// preventDefault 추적용 wheel evt 빌더 — react-konva 는 evt.evt 에 native event 를 둔다.
function wheelEvt(deltaY: number, pointer = { x: 100, y: 100 }) {
  const native = { deltaY, preventDefault: vi.fn() };
  return {
    evt: native,
    target: {
      getStage: () => ({ getPointerPosition: () => pointer }),
    },
  };
}

beforeEach(() => {
  wheelHandler = undefined;
  useLabelStore.getState().reset();
});

describe('CanvasShell — 마우스 휠 줌', () => {
  it('Stage 에 onWheel 핸들러가 연결된다', () => {
    render(<CanvasShell frame={frame} width={200} height={200} labels={[]} />);
    expect(wheelHandler).toBeTypeOf('function');
  });

  it('휠 업(deltaY<0)에서 zoom 이 증가한다 (줌인)', () => {
    render(<CanvasShell frame={frame} width={200} height={200} labels={[]} />);
    const before = useLabelStore.getState().zoom;
    wheelHandler!(wheelEvt(-100));
    expect(useLabelStore.getState().zoom).toBeGreaterThan(before);
  });

  it('휠 다운(deltaY>0)에서 zoom 이 감소한다 (줌아웃)', () => {
    // fit(=1) 이 바닥이므로 줌인된 상태(2)에서 시작해 줌아웃 감소를 검증한다.
    useLabelStore.setState({ zoom: 2 });
    render(<CanvasShell frame={frame} width={200} height={200} labels={[]} />);
    const before = useLabelStore.getState().zoom;
    wheelHandler!(wheelEvt(100));
    expect(useLabelStore.getState().zoom).toBeLessThan(before);
  });

  it('preventDefault 가 호출되어 페이지 스크롤을 막는다', () => {
    render(<CanvasShell frame={frame} width={200} height={200} labels={[]} />);
    const e = wheelEvt(-100);
    wheelHandler!(e);
    expect(e.evt.preventDefault).toHaveBeenCalled();
  });

  it('clamp 상한(8)을 넘지 않는다', () => {
    useLabelStore.setState({ zoom: 8 });
    render(<CanvasShell frame={frame} width={200} height={200} labels={[]} />);
    wheelHandler!(wheelEvt(-100));
    wheelHandler!(wheelEvt(-100));
    expect(useLabelStore.getState().zoom).toBeLessThanOrEqual(8);
  });

  it('clamp 하한(fit=1.0) 아래로 줌아웃되지 않는다 (사방 여백 방지)', () => {
    // reset() 후 zoom=1(fit). 휠 다운을 반복해도 fit 아래로 축소되지 않아야 한다.
    render(<CanvasShell frame={frame} width={200} height={200} labels={[]} />);
    wheelHandler!(wheelEvt(100));
    wheelHandler!(wheelEvt(100));
    expect(useLabelStore.getState().zoom).toBeGreaterThanOrEqual(1);
  });
});
