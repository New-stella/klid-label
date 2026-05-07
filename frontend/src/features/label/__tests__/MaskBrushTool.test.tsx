// Mask 브러시/지우개 도구 테스트.

import { fireEvent, screen } from '@testing-library/react';
import { beforeAll, describe, expect, it, vi } from 'vitest';

import { renderWithProviders } from '@/test/renderWithProviders';

import { MaskBrushTool } from '../canvas/tools/MaskBrushTool';
import { MaskEraserTool } from '../canvas/tools/MaskEraserTool';

// jsdom에 HTMLCanvasElement.getContext mock — 픽셀 버퍼만 있으면 RLE 통보 검증 가능.
beforeAll(() => {
  const buffers = new WeakMap<HTMLCanvasElement, Uint8ClampedArray>();
  function ensureBuffer(canvas: HTMLCanvasElement): Uint8ClampedArray {
    let buf = buffers.get(canvas);
    if (!buf) {
      const w = canvas.width || 1;
      const h = canvas.height || 1;
      buf = new Uint8ClampedArray(w * h * 4);
      buffers.set(canvas, buf);
    }
    return buf;
  }

  HTMLCanvasElement.prototype.getContext = function (this: HTMLCanvasElement, type: string) {
    if (type !== '2d') return null;
    const canvas = this;
    let composite: 'source-over' | 'destination-out' = 'source-over';
    let lastArc: { x: number; y: number; r: number } | null = null;
    return {
      canvas,
      get globalCompositeOperation() {
        return composite;
      },
      set globalCompositeOperation(v: 'source-over' | 'destination-out') {
        composite = v;
      },
      fillStyle: '#fff',
      save: vi.fn(),
      restore: vi.fn(),
      clearRect: () => {
        const buf = ensureBuffer(canvas);
        buf.fill(0);
      },
      beginPath: () => {
        lastArc = null;
      },
      arc: (x: number, y: number, r: number) => {
        lastArc = { x, y, r };
      },
      fill: () => {
        if (!lastArc) return;
        const buf = ensureBuffer(canvas);
        const w = canvas.width;
        const h = canvas.height;
        const r2 = lastArc.r * lastArc.r;
        for (let py = Math.max(0, Math.floor(lastArc.y - lastArc.r)); py < Math.min(h, Math.ceil(lastArc.y + lastArc.r) + 1); py++) {
          for (let px = Math.max(0, Math.floor(lastArc.x - lastArc.r)); px < Math.min(w, Math.ceil(lastArc.x + lastArc.r) + 1); px++) {
            const dx = px - lastArc.x;
            const dy = py - lastArc.y;
            if (dx * dx + dy * dy <= r2) {
              const off = (py * w + px) * 4;
              if (composite === 'destination-out') {
                buf[off] = 0;
                buf[off + 1] = 0;
                buf[off + 2] = 0;
                buf[off + 3] = 0;
              } else {
                buf[off] = 255;
                buf[off + 1] = 255;
                buf[off + 2] = 255;
                buf[off + 3] = 255;
              }
            }
          }
        }
      },
      getImageData: (sx: number, sy: number, sw: number, sh: number) => {
        const buf = ensureBuffer(canvas);
        const out = new Uint8ClampedArray(sw * sh * 4);
        for (let y = 0; y < sh; y++) {
          for (let x = 0; x < sw; x++) {
            const fromOff = ((sy + y) * canvas.width + (sx + x)) * 4;
            const toOff = (y * sw + x) * 4;
            out[toOff] = buf[fromOff];
            out[toOff + 1] = buf[fromOff + 1];
            out[toOff + 2] = buf[fromOff + 2];
            out[toOff + 3] = buf[fromOff + 3];
          }
        }
        return { data: out, width: sw, height: sh, colorSpace: 'srgb' };
      },
      createImageData: (sw: number, sh: number) => {
        return {
          data: new Uint8ClampedArray(sw * sh * 4),
          width: sw,
          height: sh,
          colorSpace: 'srgb',
        };
      },
      putImageData: (
        imgData: { data: Uint8ClampedArray; width: number; height: number },
        dx: number,
        dy: number,
      ) => {
        const buf = ensureBuffer(canvas);
        for (let y = 0; y < imgData.height; y++) {
          for (let x = 0; x < imgData.width; x++) {
            const fromOff = (y * imgData.width + x) * 4;
            const toOff = ((dy + y) * canvas.width + (dx + x)) * 4;
            buf[toOff] = imgData.data[fromOff];
            buf[toOff + 1] = imgData.data[fromOff + 1];
            buf[toOff + 2] = imgData.data[fromOff + 2];
            buf[toOff + 3] = imgData.data[fromOff + 3];
          }
        }
      },
    } as unknown as CanvasRenderingContext2D;
  } as typeof HTMLCanvasElement.prototype.getContext;

  // ImageData 글로벌 mock (jsdom에 있으면 그대로 사용)
  if (typeof globalThis.ImageData === 'undefined') {
    // @ts-expect-error — 테스트 환경 한정
    globalThis.ImageData = class {
      data: Uint8ClampedArray;
      width: number;
      height: number;
      colorSpace: string;
      constructor(data: Uint8ClampedArray, w: number, h: number) {
        this.data = data;
        this.width = w;
        this.height = h;
        this.colorSpace = 'srgb';
      }
    };
  }
});

describe('MaskBrushTool / MaskEraserTool', () => {
  it('Mask_브러시_드래그시_RLE_업데이트', () => {
    const updates: number[][] = [];
    renderWithProviders(
      <MaskBrushTool
        width={10}
        height={10}
        radius={1}
        onMaskChange={(rle) => updates.push(rle)}
      />,
    );
    const canvas = screen.getByTestId('mask-brush-canvas') as HTMLCanvasElement;
    fireEvent.mouseDown(canvas, { clientX: 5, clientY: 5, button: 0 });
    fireEvent.mouseMove(canvas, { clientX: 6, clientY: 5 });
    fireEvent.mouseUp(canvas);
    expect(updates.length).toBeGreaterThan(0);
    // RLE는 number[] (CVAT 형식: [...runs, left, top, right, bottom])
    expect(Array.isArray(updates[updates.length - 1])).toBe(true);
    expect(updates[updates.length - 1].length).toBeGreaterThanOrEqual(5);
  });

  it('Mask_지우개_드래그시_픽셀_제거', () => {
    const updates: number[][] = [];
    // 초기 마스크 — 모든 픽셀 on
    const initialRle = [0, 100, 0, 0, 9, 9]; // 10x10 = 100 pixels all on
    renderWithProviders(
      <MaskEraserTool
        width={10}
        height={10}
        radius={1}
        initialRle={initialRle}
        onMaskChange={(rle) => updates.push(rle)}
      />,
    );
    const canvas = screen.getByTestId('mask-eraser-canvas') as HTMLCanvasElement;
    fireEvent.mouseDown(canvas, { clientX: 5, clientY: 5, button: 0 });
    fireEvent.mouseMove(canvas, { clientX: 6, clientY: 5 });
    fireEvent.mouseUp(canvas);
    expect(updates.length).toBeGreaterThan(0);
    // 지우개 후 on 픽셀 수 < 100
    const lastRle = updates[updates.length - 1];
    const runs = lastRle.slice(0, -4);
    let onPixels = 0;
    let isOn = false; // CVAT 규칙: 첫 run은 off
    for (const r of runs) {
      if (isOn) onPixels += r;
      isOn = !isOn;
    }
    expect(onPixels).toBeLessThan(100);
  });
});
