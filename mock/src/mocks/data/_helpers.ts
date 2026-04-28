import dayjs from 'dayjs';

/** 시드 기반 의사난수 (0~1) */
function seededRandom(seed: number): number {
  const x = Math.sin(seed + 1) * 10000;
  return x - Math.floor(x);
}

/** 배열에서 시드 기반 선택 */
export function pick<T>(arr: readonly T[], seed: number): T {
  return arr[Math.floor(seededRandom(seed) * arr.length)];
}

/** 시드 기반 정수 (min ~ max inclusive) */
export function rangeInt(min: number, max: number, seed: number): number {
  return min + Math.floor(seededRandom(seed) * (max - min + 1));
}

/** 패딩된 ID */
export function id(prefix: string, i: number): string {
  return `${prefix}-${String(i + 1).padStart(4, '0')}`;
}

/** n일 전 ISO 문자열 */
export function daysAgo(n: number): string {
  return dayjs().subtract(n, 'day').toISOString();
}

/** n일 후 ISO 문자열 */
export function daysLater(n: number): string {
  return dayjs().add(n, 'day').toISOString();
}

/** 배열 범위 생성 */
export function range(n: number): number[] {
  return Array.from({ length: n }, (_, i) => i);
}
