// Mask 브러시 도구 — HTML5 Canvas 2D + 브러시 픽셀 마스크 → CVAT RLE.
//
// 보안: 1M 픽셀 한도 (maskRleConverter 내부). 좌표는 클라이언트 측 캔버스 좌표.

import { useCallback, useEffect, useRef, useState } from 'react';

import { imageDataToRLE, type BBox } from '../utils/maskRleConverter';

export interface MaskBrushToolProps {
  width: number;
  height: number;
  /** 브러시 반경 (px) */
  radius?: number;
  /** mask 변경 시마다 RLE 통보 ([run0..N, left, top, right, bottom]) */
  onMaskChange?: (rle: number[]) => void;
}

/**
 * Mask 브러시 도구. 마우스 드래그로 alpha 채널 픽셀을 채운다.
 * onMaskChange는 드래그 중/완료 시 RLE 통보.
 */
export function MaskBrushTool({
  width,
  height,
  radius = 4,
  onMaskChange,
}: MaskBrushToolProps) {
  const canvasRef = useRef<HTMLCanvasElement | null>(null);
  const [drawing, setDrawing] = useState(false);

  // 캔버스 초기화 (투명)
  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const ctx = canvas.getContext('2d');
    if (!ctx) return;
    ctx.clearRect(0, 0, width, height);
  }, [width, height]);

  const paintAt = useCallback(
    (x: number, y: number) => {
      const canvas = canvasRef.current;
      if (!canvas) return;
      const ctx = canvas.getContext('2d');
      if (!ctx) return;
      ctx.fillStyle = 'rgba(255,255,255,1)';
      ctx.beginPath();
      ctx.arc(x, y, radius, 0, Math.PI * 2);
      ctx.fill();
    },
    [radius],
  );

  const emit = useCallback(() => {
    const canvas = canvasRef.current;
    if (!canvas || !onMaskChange) return;
    const ctx = canvas.getContext('2d');
    if (!ctx) return;
    const data = ctx.getImageData(0, 0, width, height);
    const rle = imageDataToRLE(data.data, width, height);
    const bbox: BBox = computeAlphaBBox(data.data, width, height);
    onMaskChange([...rle, bbox.left, bbox.top, bbox.right, bbox.bottom]);
  }, [onMaskChange, width, height]);

  const handleMouseDown = (e: React.MouseEvent<HTMLCanvasElement>) => {
    if (e.button !== 0) return;
    setDrawing(true);
    const rect = e.currentTarget.getBoundingClientRect();
    paintAt(e.clientX - rect.left, e.clientY - rect.top);
    emit();
  };

  const handleMouseMove = (e: React.MouseEvent<HTMLCanvasElement>) => {
    if (!drawing) return;
    const rect = e.currentTarget.getBoundingClientRect();
    paintAt(e.clientX - rect.left, e.clientY - rect.top);
    emit();
  };

  const handleMouseUp = () => {
    if (!drawing) return;
    setDrawing(false);
    emit();
  };

  return (
    <canvas
      ref={canvasRef}
      width={width}
      height={height}
      data-testid="mask-brush-canvas"
      onMouseDown={handleMouseDown}
      onMouseMove={handleMouseMove}
      onMouseUp={handleMouseUp}
      onMouseLeave={handleMouseUp}
      className="cursor-crosshair"
      aria-label="마스크 브러시 영역"
    />
  );
}

/**
 * RGBA 데이터에서 alpha > 0인 픽셀의 BBox 계산. 비어있으면 0,0,0,0.
 */
export function computeAlphaBBox(
  data: Uint8ClampedArray,
  width: number,
  height: number,
): BBox {
  let left = width;
  let top = height;
  let right = -1;
  let bottom = -1;
  for (let y = 0; y < height; y++) {
    for (let x = 0; x < width; x++) {
      const idx = (y * width + x) * 4 + 3;
      if (data[idx] > 0) {
        if (x < left) left = x;
        if (x > right) right = x;
        if (y < top) top = y;
        if (y > bottom) bottom = y;
      }
    }
  }
  if (right < 0) {
    return { left: 0, top: 0, right: 0, bottom: 0 };
  }
  return { left, top, right, bottom };
}
