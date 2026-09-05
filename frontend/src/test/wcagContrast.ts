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

function parseHex(hex: string): { r: number; g: number; b: number } {
  const h = hex.replace('#', '');
  return {
    r: parseInt(h.slice(0, 2), 16),
    g: parseInt(h.slice(2, 4), 16),
    b: parseInt(h.slice(4, 6), 16),
  };
}

/**
 * `oklch(L% C H)` → sRGB 8bit.
 *
 * 왜 필요한가: Tailwind v4 팔레트(`tailwindcss/colors`)는 hex 가 아니라 **oklch 문자열**로
 * 값을 준다. 범주 구분색 토큰(`category-N`)은 그 팔레트를 그대로 참조하므로(전사 오류 차단),
 * 대비를 계산하려면 여기서 sRGB 로 내려야 한다. 값을 hex 로 따로 적어 두면 그 표가 곧
 * 두 번째 진실원이 된다.
 *
 * 변환은 Björn Ottosson 의 OKLab 역변환 + sRGB 전달함수다. 색역 밖 성분은 클램프한다
 * (Tailwind 가 sRGB 화면에서 실제로 그리는 색과 같다).
 */
function parseOklch(value: string): { r: number; g: number; b: number } {
  const m = /^oklch\(\s*([\d.]+)%\s+([\d.]+)\s+([\d.]+)\s*\)$/.exec(value.trim());
  if (!m) throw new Error(`oklch 로 해석할 수 없는 색 값: ${value}`);
  const L = parseFloat(m[1]) / 100;
  const C = parseFloat(m[2]);
  const hRad = (parseFloat(m[3]) * Math.PI) / 180;
  const a = C * Math.cos(hRad);
  const bb = C * Math.sin(hRad);

  const lCube = (L + 0.3963377774 * a + 0.2158037573 * bb) ** 3;
  const mCube = (L - 0.1055613458 * a - 0.0638541728 * bb) ** 3;
  const sCube = (L - 0.0894841775 * a - 1.291485548 * bb) ** 3;

  const linear = [
    4.0767416621 * lCube - 3.3077115913 * mCube + 0.2309699292 * sCube,
    -1.2684380046 * lCube + 2.6097574011 * mCube - 0.3413193965 * sCube,
    -0.0041960863 * lCube - 0.7034186147 * mCube + 1.707614701 * sCube,
  ];
  const encode = (v: number): number => {
    const enc = v <= 0.0031308 ? 12.92 * v : 1.055 * v ** (1 / 2.4) - 0.055;
    return Math.round(Math.min(1, Math.max(0, enc)) * 255);
  };
  return { r: encode(linear[0]), g: encode(linear[1]), b: encode(linear[2]) };
}

/** hex(`#RRGGBB`) 또는 `oklch(...)` 문자열을 sRGB 8bit 로 해석한다. */
function hexToRgb(color: string): { r: number; g: number; b: number } {
  return color.trim().startsWith('oklch(') ? parseOklch(color) : parseHex(color);
}

/** 색 값(hex 또는 oklch)을 대문자 `#RRGGBB` 로 정규화한다. */
export function toHex(color: string): string {
  const { r, g, b } = hexToRgb(color);
  const two = (n: number) => n.toString(16).padStart(2, '0').toUpperCase();
  return `#${two(r)}${two(g)}${two(b)}`;
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
