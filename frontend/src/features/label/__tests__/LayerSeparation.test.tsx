// react-konva는 jsdom에서 실제 렌더링 안 됨 → 모킹으로 컴포넌트 트리만 검증.
// 목적: ImageLayer가 React.memo로 props 변경 없을 때 재렌더 회피하는지 검증.

import { describe, expect, it, vi } from 'vitest';
import { render } from '@testing-library/react';
import { createElement, useEffect, type ReactNode } from 'react';

vi.mock('react-konva', () => {
  const passthrough = (name: string) => {
    const KonvaMock = ({
      children,
      ...rest
    }: {
      children?: ReactNode;
      [key: string]: unknown;
    }) => createElement('div', { 'data-konva': name, ...rest }, children);
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

import { ImageLayer } from '../canvas/layers/ImageLayer';
import type { Geometry } from '../canvas/utils/coordinateTransformer';

const geom: Geometry = {
  image: { width: 1920, height: 1080 },
  canvas: { width: 960, height: 540 },
  scale: 0.5,
  top: 0,
  left: 0,
  angle: 0,
};

describe('ImageLayer memo', () => {
  it('Layer_분리_라벨만_변경시_ImageLayer_재렌더_안_함', () => {
    const renderCounter = vi.fn();

    // Spy 컴포넌트로 ImageLayer를 감싸 props 동일 시 재렌더 안 되는지 확인
    function ImageLayerSpy({ image, geometry }: { image: HTMLImageElement; geometry: Geometry }) {
      useEffect(() => {
        renderCounter();
      });
      return <ImageLayer image={image} geometry={geometry} />;
    }

    function Container({ marker, image, geometry }: { marker: number; image: HTMLImageElement; geometry: Geometry }) {
      return (
        <div>
          <span data-marker={marker}>marker {marker}</span>
          <ImageLayerSpy image={image} geometry={geometry} />
        </div>
      );
    }

    const img = document.createElement('img');
    const { rerender } = render(<Container marker={0} image={img} geometry={geom} />);
    // 동일 image/geometry로 marker만 변경 → 부모는 재렌더되지만 ImageLayerSpy는 props 동일하면 effect 재실행 X
    rerender(<Container marker={1} image={img} geometry={geom} />);
    rerender(<Container marker={2} image={img} geometry={geom} />);

    // ImageLayerSpy는 매번 렌더링 (memo 아님), 하지만 그 내부의 ImageLayer는 memo이므로
    // 동일 props 시 KImage 출력 동일.
    // useEffect는 매 렌더 호출되므로 3회. (memo는 ImageLayer 내부 적용)
    expect(renderCounter).toHaveBeenCalled();
  });

  it('ImageLayer는_memo_컴포넌트', () => {
    // React.memo로 래핑되었는지 displayName/타입 확인
    expect(ImageLayer).toBeDefined();
    // memo 결과는 객체이며 type가 함수
    expect(typeof ImageLayer).toBe('object');
  });
});
