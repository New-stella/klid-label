// CanvasShell 이 immediateSegment(즉시 그리기 토글) 와 isSegmenting(요청 in-flight) 을
// OverlayLayer 로 중계 전달하는지 검증하는 배선 스모크.
import { describe, expect, it, beforeEach, vi } from 'vitest';
import { act, render } from '@testing-library/react';
import { createElement, type ReactNode } from 'react';

vi.mock('react-konva', () => {
  const passthrough = (name: string) => {
    const KonvaMock = ({
      children,
      onWheel: _onWheel,
      listening: _listening,
      ...domRest
    }: { children?: ReactNode; [key: string]: unknown }) =>
      createElement('div', { 'data-konva': name, ...domRest }, children);
    KonvaMock.displayName = `KonvaMock(${name})`;
    return KonvaMock;
  };
  return {
    Stage: passthrough('Stage'),
    Layer: passthrough('Layer'),
  };
});

vi.mock('../layers/ImageLayer', () => ({ ImageLayer: () => null }));
vi.mock('../layers/LabelsLayer', () => ({ LabelsLayer: () => null }));

// OverlayLayer 에 전달된 props 를 캡처.
const overlayProps: Array<Record<string, unknown>> = [];
vi.mock('../layers/OverlayLayer', () => ({
  OverlayLayer: (props: Record<string, unknown>) => {
    overlayProps.push(props);
    return null;
  },
}));

let mockIsSegmenting = false;
vi.mock('../../hooks/useSam2Segment', () => ({
  useSam2Segment: () => ({ segment: vi.fn(), isSegmenting: mockIsSegmenting }),
}));

import { useLabelStore } from '@/stores/useLabelStore';

import { CanvasShell } from '../CanvasShell';
import type { FrameSummary } from '../../types';

// 로드 완료를 수동 제어하는 FakeImage — geometry 산출을 위해 onload 를 발화한다.
const images: FakeImage[] = [];
class FakeImage {
  onload: (() => void) | null = null;
  onerror: (() => void) | null = null;
  crossOrigin = '';
  naturalWidth = 100;
  naturalHeight = 100;
  set src(_value: string) {
    images.push(this);
  }
}
vi.stubGlobal('Image', FakeImage as unknown as typeof Image);

const frame: FrameSummary = {
  frameNo: 1,
  srcSn: 1,
  thumbnailUrl: '',
  imageUrl: 'blob:mock-frame',
};

beforeEach(() => {
  images.length = 0;
  overlayProps.length = 0;
  mockIsSegmenting = false;
  useLabelStore.getState().reset();
});

describe('CanvasShell — 즉시 그리기/진행 상태 배선', () => {
  it('CanvasShell이_immediateSegment와_isSegmenting을_OverlayLayer로_전달한다', () => {
    mockIsSegmenting = true;
    render(<CanvasShell frame={frame} width={200} height={200} labels={[]} immediateSegment />);
    act(() => {
      images[0].onload?.();
    });
    const last = overlayProps[overlayProps.length - 1];
    expect(last.immediateSegment).toBe(true);
    expect(last.isSegmenting).toBe(true);
  });

  it('immediateSegment_미지정이면_기본_false로_전달된다', () => {
    render(<CanvasShell frame={frame} width={200} height={200} labels={[]} />);
    act(() => {
      images[0].onload?.();
    });
    const last = overlayProps[overlayProps.length - 1];
    expect(last.immediateSegment).toBeFalsy();
    expect(last.isSegmenting).toBe(false);
  });
});
