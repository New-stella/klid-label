// CVAT MASK ↔ RLE 변환 (TS 포팅).
// 참고: docs/analysis/portable-modules/02-mask-rle-conversion.md
// BE Java MaskRleConverter와 라운드트립 동등성 보장.
//
// CVAT RLE 형식: [run0, run1, ..., runN, left, top, right, bottom]
// - 첫 run은 항상 0(off) 픽셀 개수로 시작
// - 마지막 4개는 inclusive BBOX (right/bottom 포함)
//
// 보안: 1M 픽셀 한도 (frontend-performance + DoS 방어).

export interface BBox {
  left: number;
  top: number;
  right: number;
  bottom: number;
}

const MAX_PIXELS = 1_000_000;

/**
 * 2D boolean mask + bbox → CVAT RLE.
 * mask는 bbox로 cropped된 tight binary mask. 행=top..bottom, 열=left..right.
 */
export function maskToRle(mask: boolean[][], bbox: BBox): number[] {
  const h = bbox.bottom - bbox.top + 1;
  const w = bbox.right - bbox.left + 1;
  if (h * w > MAX_PIXELS) {
    throw new Error(`Mask 픽셀 한도 초과: ${h * w} > ${MAX_PIXELS} (too large)`);
  }
  // row-major flatten
  const flat: number[] = new Array(h * w);
  for (let y = 0; y < h; y++) {
    const row = mask[y] ?? [];
    for (let x = 0; x < w; x++) {
      flat[y * w + x] = row[x] ? 1 : 0;
    }
  }
  return [...rleEncode(flat), bbox.left, bbox.top, bbox.right, bbox.bottom];
}

/**
 * CVAT RLE → tight 2D boolean mask + bbox.
 */
export function rleToMask(rle: number[]): { mask: boolean[][]; bbox: BBox } {
  if (rle.length < 4) {
    throw new Error('Invalid RLE: 길이 부족 (bbox 누락)');
  }
  const left = rle[rle.length - 4];
  const top = rle[rle.length - 3];
  const right = rle[rle.length - 2];
  const bottom = rle[rle.length - 1];
  const h = bottom - top + 1;
  const w = right - left + 1;
  const runs = rle.slice(0, -4);
  const flat = rleDecode(runs, h * w);
  const mask: boolean[][] = [];
  for (let y = 0; y < h; y++) {
    const row: boolean[] = [];
    for (let x = 0; x < w; x++) {
      row.push(flat[y * w + x] === 1);
    }
    mask.push(row);
  }
  return { mask, bbox: { left, top, right, bottom } };
}

/**
 * RGBA Uint8ClampedArray (alpha 채널) → CVAT raw RLE (BBox 미포함).
 * 첫 run은 항상 0 픽셀 (CVAT 규칙) — alpha[0]가 1이면 [0, ...] 삽입.
 */
export function imageDataToRLE(
  imageData: Uint8ClampedArray,
  width: number,
  height: number,
): number[] {
  const totalPixels = width * height;
  if (totalPixels > MAX_PIXELS) {
    throw new Error(`ImageData 픽셀 한도 초과: ${totalPixels} > ${MAX_PIXELS} (too large)`);
  }
  if (imageData.length < totalPixels * 4) {
    throw new Error('ImageData 길이가 width*height*4보다 작습니다');
  }
  const rle: number[] = [];
  let prev = 0;
  let summ = 0;
  // CVAT TS 원본 (shared.ts:408-426) 동일 — alpha 채널만 본다
  for (let i = 3; i < totalPixels * 4; i += 4) {
    const alpha = imageData[i] > 0 ? 1 : 0;
    if (prev !== alpha) {
      rle.push(summ);
      prev = alpha;
      summ = 1;
    } else {
      summ++;
    }
  }
  rle.push(summ);
  return rle;
}

/**
 * CVAT raw RLE → RGBA Uint8ClampedArray.
 * on 픽셀은 흰색 alpha=255, off는 alpha=0.
 */
export function rleToImageData(
  rle: number[],
  width: number,
  height: number,
): Uint8ClampedArray {
  const totalPixels = width * height;
  if (totalPixels > MAX_PIXELS) {
    throw new Error(`Width*Height 한도 초과: ${totalPixels} > ${MAX_PIXELS} (too large)`);
  }
  const out = new Uint8ClampedArray(totalPixels * 4);
  let idx = 0;
  let val = 0; // CVAT 규칙: 첫 run은 off
  for (const run of rle) {
    for (let k = 0; k < run; k++) {
      const offset = (idx + k) * 4;
      if (val === 1) {
        out[offset] = 255;
        out[offset + 1] = 255;
        out[offset + 2] = 255;
        out[offset + 3] = 255;
      }
    }
    idx += run;
    val = 1 - val;
  }
  return out;
}

/**
 * 빈 RLE 체크 (masksHandler.ts:641 패턴).
 */
export function isEmptyRle(rle: number[]): boolean {
  return rle.length < 6;
}

// 내부 헬퍼: 0/1 flat 배열 → run-length 시퀀스 (CVAT 규칙)
function rleEncode(flat: number[]): number[] {
  const n = flat.length;
  if (n === 0) return [];
  const runs: number[] = [];
  let prev = 0; // 항상 0(off)에서 시작 가정
  let summ = 0;
  for (let i = 0; i < n; i++) {
    const v = flat[i] ? 1 : 0;
    if (v !== prev) {
      runs.push(summ);
      prev = v;
      summ = 1;
    } else {
      summ++;
    }
  }
  runs.push(summ);
  return runs;
}

// 내부 헬퍼: run-length 시퀀스 → 0/1 flat 배열
function rleDecode(runs: number[], totalSize: number): number[] {
  const out = new Array<number>(totalSize).fill(0);
  let idx = 0;
  let val = 0; // 첫 run은 off
  for (const run of runs) {
    for (let k = 0; k < run; k++) {
      if (idx + k < totalSize) out[idx + k] = val;
    }
    idx += run;
    val = 1 - val;
  }
  return out;
}
