// Phase 3 R7 — 캔버스 이미지 로드 스피너 + SAM2 분할 in-flight 진행 인디케이터.
import { describe, expect, it, beforeEach, vi } from 'vitest';
import { act, render, screen } from '@testing-library/react';
import { createElement, type ReactNode } from 'react';

vi.mock('react-konva', () => {
  const passthrough = (name: string) => {
    const KonvaMock = ({
      children,
      onWheel,
      ...rest
    }: {
      children?: ReactNode;
      onWheel?: (event: unknown) => void;
      [key: string]: unknown;
    }) => {
      const { listening, ...domRest } = rest;
      return createElement('div', { 'data-konva': name, ...domRest }, children);
    };
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
vi.mock('../layers/OverlayLayer', () => ({ OverlayLayer: () => null }));

// SAM2 훅 isSegmenting 을 테스트별로 제어.
let mockIsSegmenting = false;
vi.mock('../../hooks/useSam2Segment', () => ({
  useSam2Segment: () => ({ segment: vi.fn(), isSegmenting: mockIsSegmenting }),
}));

import { CanvasShell } from '../CanvasShell';
import { useLabelStore } from '@/stores/useLabelStore';
import type { FrameSummary } from '../../types';

// 로드 완료를 수동 제어하는 FakeImage — 인스턴스를 캡처해 onload 를 테스트가 발화한다.
const images: FakeImage[] = [];
class FakeImage {
  onload: (() => void) | null = null;
  onerror: (() => void) | null = null;
  crossOrigin = '';
  naturalWidth = 100;
  naturalHeight = 100;
  set src(_value: string) {
    images.push(this); // 자동 발화하지 않음 → imageLoading true 유지.
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
  mockIsSegmenting = false;
  useLabelStore.getState().reset();
});

describe('CanvasShell — R7 로딩/진행 인디케이터', () => {
  it('이미지_로드중이면_캔버스에_스피너가_표시된다_로드완료시_해제', () => {
    render(<CanvasShell frame={frame} width={200} height={200} labels={[]} />);
    // 로드 완료 전 — 스피너 노출.
    expect(screen.getByTestId('canvas-image-spinner')).toBeInTheDocument();

    // onload 발화 → 스피너 해제.
    act(() => {
      images[0].onload?.();
    });
    expect(screen.queryByTestId('canvas-image-spinner')).toBeNull();
  });

  it('이미지_로드_실패시에도_스피너가_해제된다', () => {
    render(<CanvasShell frame={frame} width={200} height={200} labels={[]} />);
    expect(screen.getByTestId('canvas-image-spinner')).toBeInTheDocument();
    act(() => {
      images[0].onerror?.();
    });
    expect(screen.queryByTestId('canvas-image-spinner')).toBeNull();
  });

  it('빈_imageUrl이면_스피너_미표시', () => {
    render(
      <CanvasShell frame={{ ...frame, imageUrl: '' }} width={200} height={200} labels={[]} />,
    );
    expect(screen.queryByTestId('canvas-image-spinner')).toBeNull();
  });

  it('SAM2분할_요청중이면_진행_인디케이터가_표시된다', () => {
    mockIsSegmenting = true;
    render(<CanvasShell frame={frame} width={200} height={200} labels={[]} />);
    expect(screen.getByTestId('sam2-progress')).toBeInTheDocument();
  });

  it('SAM2분할_미진행이면_진행_인디케이터_미표시', () => {
    mockIsSegmenting = false;
    render(<CanvasShell frame={frame} width={200} height={200} labels={[]} />);
    expect(screen.queryByTestId('sam2-progress')).toBeNull();
  });
});
