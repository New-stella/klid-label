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

const frame: FrameSummary = {
  frameNo: 1,
  srcSn: 1,
  thumbnailUrl: '',
  imageUrl: '',
  imageWidth: 100,
  imageHeight: 100,
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

  it('clamp 하한(0.1)을 넘지 않는다', () => {
    useLabelStore.setState({ zoom: 0.1 });
    render(<CanvasShell frame={frame} width={200} height={200} labels={[]} />);
    wheelHandler!(wheelEvt(100));
    wheelHandler!(wheelEvt(100));
    expect(useLabelStore.getState().zoom).toBeGreaterThanOrEqual(0.1);
  });
});
