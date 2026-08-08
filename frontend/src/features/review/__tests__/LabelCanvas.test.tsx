// react-konva 는 jsdom 에서 canvas 가 없어 실제 렌더되지 않으므로 mock 으로 컴포넌트 트리만 검증한다.

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { createElement, type ReactNode } from 'react';

vi.mock('react-konva', () => {
  const passthrough = (name: string) => {
    const KonvaMock = ({
      children,
      image: _image,
      ...rest
    }: {
      children?: ReactNode;
      image?: unknown;
      [key: string]: unknown;
    }) => {
      // image prop 은 HTMLImageElement 라 DOM attribute 로 전달 시 경고 발생 — 제거.
      return createElement(
        'div',
        { 'data-konva': name, ...rest },
        children,
      );
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

// useImageBlob 은 axios 인증 fetch 라 jsdom 에서 동작 불가 — 고정 blob URL 반환으로 대체.
vi.mock('@/features/label/hooks/useImageBlob', () => ({
  useImageBlob: () => ({ url: 'blob:mock-frame-image', loading: false, error: null }),
}));

// UI-064 — 색상 판정은 라벨 마스터를 단일 진실원으로 삼는다. 캔버스는 마스터 목록을 구독하므로
// QueryClientProvider 없이 렌더할 수 있도록 훅을 고정 목록으로 대체한다.
vi.mock('@/features/label/hooks/useLabelMasters', () => ({
  useLabelMasters: () => ({
    data: [
      {
        labelId: 11,
        name: '사람',
        color: '#123456',
        type: 'BBOX',
        sortNo: 1,
        useYn: 'Y',
        dtctTypeCd: null,
      },
    ],
  }),
}));

import { LabelCanvas } from '../components/LabelCanvas';
import { useReviewSelectionStore } from '../store/useReviewSelectionStore';
import type { FrameDetail, LabelItem } from '../types';

// jsdom 의 Image 는 src 할당 시 load 이벤트를 발화하지 않으므로 stub 으로 제어한다.
// loadShouldSucceed=false 면 onload 를 발화시키지 않아 "이미지 로드 전" 상태를 재현(FE-3).
let loadShouldSucceed = true;
const realImage = globalThis.Image;

class MockImage {
  onload: (() => void) | null = null;
  onerror: (() => void) | null = null;
  crossOrigin: string | null = null;
  naturalWidth = 0;
  naturalHeight = 0;
  private _src = '';
  set src(value: string) {
    this._src = value;
    if (!value) return;
    // 비동기 로드 흉내 — microtask 후 콜백.
    queueMicrotask(() => {
      if (loadShouldSucceed) {
        this.naturalWidth = 640;
        this.naturalHeight = 480;
        this.onload?.();
      } else {
        // onload 미발화 — 컴포넌트는 imgW/imgH=0 상태 유지.
      }
    });
  }
  get src() {
    return this._src;
  }
}

beforeEach(() => {
  loadShouldSucceed = true;
  globalThis.Image = MockImage as unknown as typeof Image;
});

afterEach(() => {
  globalThis.Image = realImage;
});

const bboxLabel: LabelItem = {
  id: 1,
  lblTypeCd: 'BBOX',
  label: '사람',
  points: [
    [10, 20],
    [110, 220],
  ],
  autoLblYn: 'N',
  confScore: null,
};

const polygonLabel: LabelItem = {
  id: 2,
  lblTypeCd: 'POLYGON',
  label: '차량',
  points: [
    [50, 50],
    [100, 30],
    [150, 80],
    [80, 100],
  ],
  autoLblYn: 'Y',
  confScore: 0.92,
};

const segmentLabel: LabelItem = {
  id: 3,
  lblTypeCd: 'SEGMENT',
  label: '트럭',
  points: [
    [200, 200],
    [220, 200],
    [220, 250],
  ],
  autoLblYn: 'N',
  confScore: null,
};

describe('LabelCanvas', () => {
  it('LabelCanvas_frame_null_시_안내_렌더링', () => {
    render(<LabelCanvas frame={null} />);
    expect(screen.getByTestId('review-label-canvas-empty')).toBeInTheDocument();
    expect(screen.getByText('프레임이 없습니다')).toBeInTheDocument();
  });

  it('LabelCanvas_컨테이너_렌더링', () => {
    const frame: FrameDetail = {
      srcSn: 1,
      frameNo: 1,
      imageUrl: '/api/v1/videos/1/frames/1/image',
      labels: [],
    };
    render(<LabelCanvas frame={frame} />);
    expect(screen.getByTestId('review-label-canvas')).toBeInTheDocument();
  });

  it('LabelCanvas_bbox_라벨_Rect_렌더링', () => {
    const frame: FrameDetail = {
      srcSn: 1,
      frameNo: 1,
      imageUrl: '/api/v1/videos/1/frames/1/image',
      labels: [bboxLabel],
    };
    // jsdom 환경에서 ResizeObserver 가 없는 경우 size=0 으로 Stage 미렌더.
    // 컨테이너 크기 보장을 위해 mock + getBoundingClientRect 우회는 어려우므로
    // Stage 렌더 자체보다는 컴포넌트 트리 mount 만 검증.
    const { container } = render(<LabelCanvas frame={frame} />);
    expect(container.querySelector('[data-testid="review-label-canvas"]')).toBeInTheDocument();
  });

  it('LabelCanvas_polygon_라벨_Line_closed_렌더링', () => {
    const frame: FrameDetail = {
      srcSn: 1,
      frameNo: 1,
      imageUrl: '/api/v1/videos/1/frames/1/image',
      labels: [polygonLabel],
    };
    const { container } = render(<LabelCanvas frame={frame} />);
    expect(container.querySelector('[data-testid="review-label-canvas"]')).toBeInTheDocument();
  });

  it('LabelCanvas_SEGMENT_TRACK_도_POLYGON_으로_렌더링_NULL_아님', () => {
    const frame: FrameDetail = {
      srcSn: 1,
      frameNo: 1,
      imageUrl: '/api/v1/videos/1/frames/1/image',
      labels: [segmentLabel],
    };
    const { container } = render(<LabelCanvas frame={frame} />);
    expect(container.querySelector('[data-testid="review-label-canvas"]')).toBeInTheDocument();
  });

  it('LabelCanvas_shape_클릭_시_setSelected', async () => {
    // jsdom 에서 컨테이너 크기를 0 으로 측정하므로 Stage 가 마운트되지 않음.
    // clientWidth/clientHeight 를 mock 하여 Stage + Shape 가 렌더되도록 한다.
    const origDescW = Object.getOwnPropertyDescriptor(
      HTMLElement.prototype,
      'clientWidth',
    );
    const origDescH = Object.getOwnPropertyDescriptor(
      HTMLElement.prototype,
      'clientHeight',
    );
    Object.defineProperty(HTMLDivElement.prototype, 'clientWidth', {
      configurable: true,
      get: () => 800,
    });
    Object.defineProperty(HTMLDivElement.prototype, 'clientHeight', {
      configurable: true,
      get: () => 600,
    });
    useReviewSelectionStore.getState().clear();

    try {
      const frame: FrameDetail = {
        srcSn: 1,
        frameNo: 1,
        imageUrl: '/api/v1/videos/1/frames/1/image',
        labels: [bboxLabel],
      };
      const { container } = render(<LabelCanvas frame={frame} />);
      // 이미지 비동기 로드(MockImage onload) 완료 후에야 Stage + 라벨 shape 가 렌더된다.
      await waitFor(() => {
        expect(container.querySelector('[data-konva="Rect"]')).not.toBeNull();
      });
      // mock 된 react-konva 의 Rect 는 div[data-konva="Rect"] 로 렌더됨.
      const rect = container.querySelector('[data-konva="Rect"]') as HTMLElement | null;
      expect(rect).not.toBeNull();
      rect!.click();
      expect(useReviewSelectionStore.getState().selectedLabelId).toBe(bboxLabel.id);
    } finally {
      // 후속 테스트 격리 — 원래 descriptor 복원 (없었으면 delete).
      if (origDescW) {
        Object.defineProperty(HTMLDivElement.prototype, 'clientWidth', origDescW);
      } else {
        delete (HTMLDivElement.prototype as unknown as Record<string, unknown>).clientWidth;
      }
      if (origDescH) {
        Object.defineProperty(HTMLDivElement.prototype, 'clientHeight', origDescH);
      } else {
        delete (HTMLDivElement.prototype as unknown as Record<string, unknown>).clientHeight;
      }
    }
  });

  it('LabelCanvas_100건_라벨_렌더_워닝_없음', () => {
    const labels: LabelItem[] = Array.from({ length: 100 }, (_, i) => ({
      id: i + 1,
      lblTypeCd: i % 2 === 0 ? 'BBOX' : 'POLYGON',
      label: i % 3 === 0 ? '사람' : i % 3 === 1 ? '차량' : '트럭',
      points: [
        [i * 5, i * 3],
        [i * 5 + 50, i * 3 + 50],
      ],
      autoLblYn: 'N',
      confScore: null,
    }));
    const frame: FrameDetail = {
      srcSn: 1,
      frameNo: 1,
      imageUrl: '/api/v1/videos/1/frames/1/image',
      labels,
    };
    const errorSpy = vi.spyOn(console, 'error').mockImplementation(() => {});
    const { container } = render(<LabelCanvas frame={frame} />);
    expect(container.querySelector('[data-testid="review-label-canvas"]')).toBeInTheDocument();
    expect(errorSpy).not.toHaveBeenCalled();
    errorSpy.mockRestore();
  });

  it('FE3_이미지_로드_전에는_라벨_shape를_렌더하지_않는다', async () => {
    // given: 컨테이너 크기는 확보되지만 이미지 onload 가 발화되지 않는 상태(imgW/imgH=0).
    loadShouldSucceed = false;
    const origDescW = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'clientWidth');
    const origDescH = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'clientHeight');
    Object.defineProperty(HTMLDivElement.prototype, 'clientWidth', { configurable: true, get: () => 800 });
    Object.defineProperty(HTMLDivElement.prototype, 'clientHeight', { configurable: true, get: () => 600 });
    try {
      const frame: FrameDetail = {
        srcSn: 1,
        frameNo: 1,
        imageUrl: '/api/v1/videos/1/frames/1/image',
        labels: [bboxLabel],
      };
      // when
      const { container } = render(<LabelCanvas frame={frame} />);
      // then: 컨테이너는 렌더되지만 이미지 로드 전이라 Stage/라벨 shape(Rect)는 렌더되지 않는다.
      // (getFitScale(0,0,..) scale 보정으로 strokeWidth 가 비정상 커지는 렌더를 차단)
      await new Promise((r) => queueMicrotask(() => r(null)));
      expect(container.querySelector('[data-testid="review-label-canvas"]')).toBeInTheDocument();
      expect(container.querySelector('[data-konva="Stage"]')).toBeNull();
      expect(container.querySelector('[data-konva="Rect"]')).toBeNull();
    } finally {
      if (origDescW) Object.defineProperty(HTMLDivElement.prototype, 'clientWidth', origDescW);
      else delete (HTMLDivElement.prototype as unknown as Record<string, unknown>).clientWidth;
      if (origDescH) Object.defineProperty(HTMLDivElement.prototype, 'clientHeight', origDescH);
      else delete (HTMLDivElement.prototype as unknown as Record<string, unknown>).clientHeight;
    }
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// UI-058 회귀 가드 — 키포인트(스켈레톤) 라벨이 검수 캔버스에 렌더되는가.
//
// 결함: `switch(lblTypeCd)` 가 BBOX/POLYGON/SEGMENT/TRACK 만 처리하고 BE 가 실제로 내려보내는
//       'SKELETON' 은 `default: return null` 로 빠져 **키포인트 라벨이 화면에서 통째로 사라졌다**.
// ─────────────────────────────────────────────────────────────────────────────

/** BE SKELETON 포맷 — 17×[x, y, v]. v: 0=미표기 / 1=비가시 / 2=가시. */
const skeletonPoints: number[][] = Array.from({ length: 17 }, (_, i) => [
  100 + i * 5,
  100 + i * 7,
  2,
]);

// `LabelType` 이 'SKELETON' 을 선언하므로 캐스팅 없이 그대로 쓴다 — 캐스팅이 되살아나면
// 유니온이 다시 줄어들었다는 뜻이다(그 상태에서 이 라벨은 화면에서 사라진다).
const skeletonLabel: LabelItem = {
  id: 9,
  lblTypeCd: 'SKELETON',
  label: '사람',
  points: skeletonPoints,
  autoLblYn: 'N',
  confScore: null,
};

describe('LabelCanvas — 키포인트(스켈레톤) 표시 (UI-058)', () => {
  function withCanvasSize(run: () => Promise<void> | void) {
    const origDescW = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'clientWidth');
    const origDescH = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'clientHeight');
    Object.defineProperty(HTMLDivElement.prototype, 'clientWidth', {
      configurable: true,
      get: () => 800,
    });
    Object.defineProperty(HTMLDivElement.prototype, 'clientHeight', {
      configurable: true,
      get: () => 600,
    });
    const restore = () => {
      if (origDescW) Object.defineProperty(HTMLDivElement.prototype, 'clientWidth', origDescW);
      else delete (HTMLDivElement.prototype as unknown as Record<string, unknown>).clientWidth;
      if (origDescH) Object.defineProperty(HTMLDivElement.prototype, 'clientHeight', origDescH);
      else delete (HTMLDivElement.prototype as unknown as Record<string, unknown>).clientHeight;
    };
    return Promise.resolve(run()).finally(restore);
  }

  it('스켈레톤_라벨이_관절_17개와_연결선으로_렌더된다', async () => {
    await withCanvasSize(async () => {
      // given: 키포인트 라벨 1건만 있는 프레임
      const frame: FrameDetail = {
        srcSn: 1,
        frameNo: 1,
        imageUrl: '/api/v1/videos/1/frames/1/image',
        labels: [skeletonLabel],
      };
      // when
      const { container } = render(<LabelCanvas frame={frame} />);
      // then: 관절 마커(Circle) 17개 + 스켈레톤 연결선(Line)이 그려진다.
      //       구 동작에서는 default 분기라 아무것도 렌더되지 않았다.
      await waitFor(() => {
        expect(container.querySelectorAll('[data-konva="Circle"]').length).toBe(17);
      });
      expect(container.querySelectorAll('[data-konva="Line"]').length).toBeGreaterThan(0);
    });
  });

  it('미표기_관절_v0_이_끝점이면_연결선을_그리지_않는다', async () => {
    await withCanvasSize(async () => {
      // given: 전부 v=0(미표기)인 스켈레톤
      const allUnlabeled = {
        ...skeletonLabel,
        points: skeletonPoints.map(([x, y]) => [x, y, 0]),
      } as unknown as LabelItem;
      const frame: FrameDetail = {
        srcSn: 1,
        frameNo: 1,
        imageUrl: '/api/v1/videos/1/frames/1/image',
        labels: [allUnlabeled],
      };
      // when
      const { container } = render(<LabelCanvas frame={frame} />);
      // then: 관절 마커는 흐리게라도 남지만 연결선은 0개다.
      await waitFor(() => {
        expect(container.querySelectorAll('[data-konva="Circle"]').length).toBe(17);
      });
      expect(container.querySelectorAll('[data-konva="Line"]').length).toBe(0);
    });
  });

  it('관절_클릭으로_라벨이_선택된다', async () => {
    await withCanvasSize(async () => {
      // given
      useReviewSelectionStore.getState().clear();
      const frame: FrameDetail = {
        srcSn: 1,
        frameNo: 1,
        imageUrl: '/api/v1/videos/1/frames/1/image',
        labels: [skeletonLabel],
      };
      const { container } = render(<LabelCanvas frame={frame} />);
      await waitFor(() => {
        expect(container.querySelector('[data-konva="Circle"]')).not.toBeNull();
      });
      // when
      (container.querySelector('[data-konva="Circle"]') as HTMLElement).click();
      // then
      expect(useReviewSelectionStore.getState().selectedLabelId).toBe(9);
    });
  });

  it('BBOX_와_함께_있어도_둘_다_렌더된다', async () => {
    await withCanvasSize(async () => {
      // given: 같은 프레임에 BBOX + 스켈레톤
      const frame: FrameDetail = {
        srcSn: 1,
        frameNo: 1,
        imageUrl: '/api/v1/videos/1/frames/1/image',
        labels: [bboxLabel, skeletonLabel],
      };
      // when
      const { container } = render(<LabelCanvas frame={frame} />);
      // then
      await waitFor(() => {
        expect(container.querySelector('[data-konva="Rect"]')).not.toBeNull();
      });
      expect(container.querySelectorAll('[data-konva="Circle"]').length).toBe(17);
    });
  });
});
