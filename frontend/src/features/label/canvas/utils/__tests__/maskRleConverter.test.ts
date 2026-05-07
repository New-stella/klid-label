// CVAT MASK ↔ RLE 변환 테스트 — BE Java MaskRleConverter와 라운드트립 동등성 검증
// 참고: docs/analysis/portable-modules/02-mask-rle-conversion.md

import { describe, expect, it } from 'vitest';

import {
  imageDataToRLE,
  maskToRle,
  rleToImageData,
  rleToMask,
  type BBox,
} from '../maskRleConverter';

function makeMask(rows: string[]): boolean[][] {
  return rows.map((r) => r.split('').map((c) => c === '1'));
}

function maskEquals(a: boolean[][], b: boolean[][]): boolean {
  if (a.length !== b.length) return false;
  for (let i = 0; i < a.length; i++) {
    if (a[i].length !== b[i].length) return false;
    for (let j = 0; j < a[i].length; j++) {
      if (a[i][j] !== b[i][j]) return false;
    }
  }
  return true;
}

describe('maskRleConverter', () => {
  describe('Mask_RLE_imageData_라운드트립', () => {
    it('단순_사각형_마스크_라운드트립', () => {
      // tight mask (bbox 영역만)
      const tight = makeMask(['111', '111']);
      const bbox: BBox = { left: 1, top: 1, right: 3, bottom: 2 };
      const rle = maskToRle(tight, bbox);
      const decoded = rleToMask(rle);
      expect(decoded.bbox).toEqual(bbox);
      expect(maskEquals(decoded.mask, tight)).toBe(true);
    });

    it('CVAT_규칙_첫_run은_항상_0_off_시작', () => {
      // 모든 픽셀이 1인 2x2 마스크
      const mask = makeMask(['11', '11']);
      const bbox: BBox = { left: 0, top: 0, right: 1, bottom: 1 };
      const rle = maskToRle(mask, bbox);
      // [0, 4, 0, 0, 1, 1] — 첫 run 0(off) + 4 픽셀 on + bbox
      expect(rle[0]).toBe(0);
      // 마지막 4개는 bbox
      expect(rle.slice(-4)).toEqual([0, 0, 1, 1]);
      // run 합 = 4 (마스크 픽셀 개수)
      const runs = rle.slice(0, -4);
      const sum = runs.reduce((a, b) => a + b, 0);
      expect(sum).toBe(4);
    });
  });

  describe('Mask_RLE_빈_mask_정상_처리', () => {
    it('전부_0인_마스크', () => {
      const mask = makeMask(['00', '00']);
      const bbox: BBox = { left: 0, top: 0, right: 1, bottom: 1 };
      const rle = maskToRle(mask, bbox);
      const decoded = rleToMask(rle);
      expect(decoded.bbox).toEqual(bbox);
      expect(maskEquals(decoded.mask, mask)).toBe(true);
    });
  });

  describe('단일_픽셀_마스크', () => {
    it('1픽셀_마스크_라운드트립', () => {
      const mask = makeMask(['1']);
      const bbox: BBox = { left: 5, top: 7, right: 5, bottom: 7 };
      const rle = maskToRle(mask, bbox);
      const decoded = rleToMask(rle);
      expect(decoded.bbox).toEqual(bbox);
      expect(maskEquals(decoded.mask, mask)).toBe(true);
    });
  });

  describe('imageDataToRLE_RGBA_alpha_채널_기반', () => {
    it('alpha_0이면_off_alpha_255면_on', () => {
      // 4픽셀 (2x2): on, off, off, on
      // RGBA 형식 (R, G, B, A)
      const w = 2;
      const h = 2;
      const data = new Uint8ClampedArray([
        255, 0, 0, 255, // (0,0) on
        0, 0, 0, 0,     // (1,0) off
        0, 0, 0, 0,     // (0,1) off
        255, 0, 0, 255, // (1,1) on
      ]);
      const rle = imageDataToRLE(data, w, h);
      // CVAT 규칙: 첫 run 0 (alpha[0] != 0이면 0 삽입)
      // RLE만 (bbox 미포함, imageDataToRLE는 BBox 미포함 형식)
      expect(rle[0]).toBe(0); // off부터 시작 가정
      const sum = rle.reduce((a, b) => a + b, 0);
      expect(sum).toBe(w * h);
    });

    it('imageDataToRLE_라운드트립', () => {
      const w = 4;
      const h = 4;
      const data = new Uint8ClampedArray(w * h * 4);
      // (1,1)~(2,2) 사각형 on
      for (let y = 1; y <= 2; y++) {
        for (let x = 1; x <= 2; x++) {
          const idx = (y * w + x) * 4;
          data[idx] = 255;
          data[idx + 1] = 255;
          data[idx + 2] = 255;
          data[idx + 3] = 255;
        }
      }
      const rle = imageDataToRLE(data, w, h);
      const restored = rleToImageData(rle, w, h);
      // alpha 채널 비교
      for (let i = 0; i < w * h; i++) {
        const aOrig = data[i * 4 + 3] > 0 ? 1 : 0;
        const aBack = restored[i * 4 + 3] > 0 ? 1 : 0;
        expect(aBack).toBe(aOrig);
      }
    });
  });

  describe('큰_random_마스크_라운드트립_1000회', () => {
    it('random_마스크_라운드트립_정합', () => {
      const W = 32;
      const H = 32;
      let seed = 1;
      // 간단한 LCG 의사난수 (재현성)
      const rand = () => {
        seed = (seed * 1103515245 + 12345) & 0x7fffffff;
        return seed / 0x7fffffff;
      };

      for (let trial = 0; trial < 100; trial++) {
        const mask: boolean[][] = [];
        for (let y = 0; y < H; y++) {
          const row: boolean[] = [];
          for (let x = 0; x < W; x++) {
            row.push(rand() > 0.6);
          }
          mask.push(row);
        }
        const bbox: BBox = { left: 0, top: 0, right: W - 1, bottom: H - 1 };
        const rle = maskToRle(mask, bbox);
        const decoded = rleToMask(rle);
        if (!maskEquals(decoded.mask, mask)) {
          throw new Error(`라운드트립 실패 trial=${trial}`);
        }
      }
    });

    it('1000회_random_라운드트립_부하_테스트', () => {
      const W = 16;
      const H = 16;
      let seed = 42;
      const rand = () => {
        seed = (seed * 1103515245 + 12345) & 0x7fffffff;
        return seed / 0x7fffffff;
      };

      for (let trial = 0; trial < 1000; trial++) {
        const mask: boolean[][] = [];
        for (let y = 0; y < H; y++) {
          const row: boolean[] = [];
          for (let x = 0; x < W; x++) {
            row.push(rand() > 0.5);
          }
          mask.push(row);
        }
        const bbox: BBox = { left: 0, top: 0, right: W - 1, bottom: H - 1 };
        const rle = maskToRle(mask, bbox);
        const decoded = rleToMask(rle);
        expect(maskEquals(decoded.mask, mask)).toBe(true);
      }
    });
  });

  describe('보안_픽셀_한도', () => {
    it('1M_픽셀_초과시_에러', () => {
      const w = 2000;
      const h = 2000; // 4M > 1M
      const data = new Uint8ClampedArray(w * h * 4);
      expect(() => imageDataToRLE(data, w, h)).toThrow(/픽셀.*한도|too large/i);
    });
  });
});
