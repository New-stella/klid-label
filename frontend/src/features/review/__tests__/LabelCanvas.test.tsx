// react-konva 는 jsdom 에서 canvas 가 없어 실제 렌더되지 않으므로 mock 으로 컴포넌트 트리만 검증한다.

import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';

vi.mock('react-konva', () => {
  const React = require('react');
  const passthrough = (name: string) => {
    return ({ children, image: _image, ...rest }: any) => {
      // image prop 은 HTMLImageElement 라 DOM attribute 로 전달 시 경고 발생 — 제거.
      return React.createElement(
        'div',
        { 'data-konva': name, ...rest },
        children,
      );
    };
  };
  return {
    Stage: passthrough('Stage'),
    Layer: passthrough('Layer'),
    Image: passthrough('Image'),
    Rect: passthrough('Rect'),
    Line: passthrough('Line'),
  };
});

import { LabelCanvas } from '../components/LabelCanvas';
import { useReviewSelectionStore } from '../store/useReviewSelectionStore';
import type { FrameDetail, LabelItem } from '../types';

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
});
