/**
 * WCAG 2.1 상대휘도 기반 대비비 계산 유틸.
 *
 * 클래스 문자열 존재 여부만 세는 "가짜 가드"를 피하기 위해, 실제 sRGB 상대휘도 공식으로
 * 대비비를 계산한다. 색상 값은 항상 tailwind.config.js 에서 resolveConfig 로 읽어와야
 * 하며(하드코딩 hex 금지), 토큰 값이 바뀌면 이 계산도 함께 바뀌어 회귀를 잡는다.
 *
 * 참고: https://www.w3.org/TR/WCAG21/#contrast-minimum
 */

function srgbChannelToLinear(channel8bit: number): number {
  const c = channel8bit / 255;
  return c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
}

function hexToRgb(hex: string): { r: number; g: number; b: number } {
  const h = hex.replace('#', '');
  return {
    r: parseInt(h.slice(0, 2), 16),
    g: parseInt(h.slice(2, 4), 16),
    b: parseInt(h.slice(4, 6), 16),
  };
}

/** WCAG 상대휘도 (0~1). */
export function relativeLuminance(hex: string): number {
  const { r, g, b } = hexToRgb(hex);
  const R = srgbChannelToLinear(r);
  const G = srgbChannelToLinear(g);
  const B = srgbChannelToLinear(b);
  return 0.2126 * R + 0.7152 * G + 0.0722 * B;
}

/** 두 색의 WCAG 대비비 (1~21). */
export function contrastRatio(hexA: string, hexB: string): number {
  const lA = relativeLuminance(hexA);
  const lB = relativeLuminance(hexB);
  const lighter = Math.max(lA, lB);
  const darker = Math.min(lA, lB);
  return (lighter + 0.05) / (darker + 0.05);
}

/**
 * fg 색을 alpha(0~1) 불투명도로 bg 위에 합성했을 때의 실제 표시 색(hex).
 * Tailwind의 `bg-info/10` 같은 알파 유틸이 실제로 렌더링에서 만드는 색과 동일하다.
 */
export function compositeOver(fgHex: string, alpha: number, bgHex = '#FFFFFF'): string {
  const fg = hexToRgb(fgHex);
  const bg = hexToRgb(bgHex);
  const mix = (a: number, b: number) => Math.round(a * alpha + b * (1 - alpha));
  const r = mix(fg.r, bg.r);
  const g = mix(fg.g, bg.g);
  const b = mix(fg.b, bg.b);
  const toHex = (n: number) => n.toString(16).padStart(2, '0').toUpperCase();
  return `#${toHex(r)}${toHex(g)}${toHex(b)}`;
}

/** WCAG AA 통과 기준 (일반 텍스트 4.5:1 / 큰 텍스트·비텍스트 UI 3:1). */
export const WCAG_AA_NORMAL_TEXT = 4.5;
export const WCAG_AA_LARGE_TEXT_OR_ICON = 3;
