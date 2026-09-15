// CanvasShell 이 immediateSegment(즉시 그리기 토글) 와 isSegmenting(요청 in-flight) 을
// OverlayLayer 로 중계 전달하는지 검증하는 배선 스모크.
import { describe, expect, it, beforeEach, vi } from 'vitest';
import { act, render } from '@testing-library/react';

vi.mock('react-konva', async () => (await import('@/test/konvaMock')).createKonvaMock());

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
// 훅에 넘어간 인자(프레임·포털 채널) — 캔버스가 채널 인자를 버리면 포털 분할이 내부 창구로 샌다.
const segmentHookArgs: unknown[][] = [];
vi.mock('../../hooks/useSam2Segment', () => ({
  useSam2Segment: (...args: unknown[]) => {
    segmentHookArgs.push(args);
    return { segment: vi.fn(), isSegmenting: mockIsSegmenting };
  },
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
  segmentHookArgs.length = 0;
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

  it('portalMode면_AI_분할_훅에_포털_채널을_넘기고_기본은_내부_채널이다', () => {
    // SCREEN-029 · API-257 — 포털 채널의 AI 분할 요청·취소는 포털 전용 창구로 간다.
    const { unmount } = render(
      <CanvasShell frame={frame} width={200} height={200} labels={[]} portalMode />,
    );
    expect(segmentHookArgs[segmentHookArgs.length - 1]).toEqual([1, true]);
    unmount();
    segmentHookArgs.length = 0;
    render(<CanvasShell frame={frame} width={200} height={200} labels={[]} />);
    expect(segmentHookArgs[segmentHookArgs.length - 1]).toEqual([1, false]);
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
