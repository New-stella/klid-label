// Mask 지우개 도구 — 기존 RLE 기반 마스크에서 픽셀 제거.

import { useCallback, useEffect, useRef, useState } from 'react';

import { imageDataToRLE, rleToImageData, type BBox } from '../utils/maskRleConverter';
import { computeAlphaBBox } from './MaskBrushTool';

export interface MaskEraserToolProps {
  width: number;
  height: number;
  radius?: number;
  /** 초기 RLE (CVAT 형식: [...runs, left, top, right, bottom]) */
  initialRle?: number[];
  onMaskChange?: (rle: number[]) => void;
}

/**
 * Mask 지우개 도구. 초기 RLE를 캔버스에 그린 뒤 드래그로 alpha를 0으로.
 */
export function MaskEraserTool({
  width,
  height,
  radius = 4,
  initialRle,
  onMaskChange,
}: MaskEraserToolProps) {
  const canvasRef = useRef<HTMLCanvasElement | null>(null);
  const [drawing, setDrawing] = useState(false);

  // 초기 RLE 그리기
  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const ctx = canvas.getContext('2d');
    if (!ctx) return;
    ctx.clearRect(0, 0, width, height);
    if (initialRle && initialRle.length >= 4) {
      const runsOnly = initialRle.slice(0, -4);
      const data = rleToImageData(runsOnly, width, height);
      // Use createImageData + set 으로 ArrayBuffer 타입 분기 회피.
      const imgData = ctx.createImageData(width, height);
      imgData.data.set(data);
      ctx.putImageData(imgData, 0, 0);
    }
  }, [initialRle, width, height]);

  const eraseAt = useCallback(
    (x: number, y: number) => {
      const canvas = canvasRef.current;
      if (!canvas) return;
      const ctx = canvas.getContext('2d');
      if (!ctx) return;
      ctx.save();
      ctx.globalCompositeOperation = 'destination-out';
      ctx.beginPath();
      ctx.arc(x, y, radius, 0, Math.PI * 2);
      ctx.fill();
      ctx.restore();
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
    eraseAt(e.clientX - rect.left, e.clientY - rect.top);
    emit();
  };

  const handleMouseMove = (e: React.MouseEvent<HTMLCanvasElement>) => {
    if (!drawing) return;
    const rect = e.currentTarget.getBoundingClientRect();
    eraseAt(e.clientX - rect.left, e.clientY - rect.top);
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
      data-testid="mask-eraser-canvas"
      onMouseDown={handleMouseDown}
      onMouseMove={handleMouseMove}
      onMouseUp={handleMouseUp}
      onMouseLeave={handleMouseUp}
      className="cursor-crosshair"
      aria-label="마스크 지우개 영역"
    />
  );
}
