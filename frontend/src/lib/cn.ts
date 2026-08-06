import { clsx, type ClassValue } from 'clsx';
import { extendTailwindMerge } from 'tailwind-merge';

/**
 * 프로젝트 커스텀 폰트 크기 토큰 — `tailwind.config.js` 의 `theme.extend.fontSize` 키 전량에서
 * <b>표준 t-shirt 스케일(xs/sm/base/lg/xl/2xl/3xl)을 제외한 것</b>.
 *
 * ★ 왜 필요한가 (조용한 버그)
 *   tailwind-merge 의 기본 설정은 `text-*` 를 두 그룹으로 나눈다 —
 *   `font-size`(t-shirt 스케일 검증기 통과분)와 `text-color`(그 외 전부, 검증기 `isAny`).
 *   그래서 `text-body`·`text-sub` 같은 커스텀 크기 토큰이 <b>색 그룹으로 오인</b>되어,
 *   같은 병합에 뒤따르는 색 클래스(`text-gray-900` 등)에 밀려 <b>조용히 삭제</b>됐다.
 *
 *     twMerge('text-body text-gray-900')  →  'text-gray-900'   // 크기 소멸(구 동작)
 *     twMerge('text-xs  text-gray-700')   →  'text-xs text-gray-700'  // 표준 스케일만 생존
 *
 *   실제 피해: 공통 `Textarea`/`Select` 는 자기 기본 클래스 문자열 안에서
 *   `text-body` 가 뒤따르는 `text-gray-900` 에 먹혀 <b>크기 지정 없이</b> 렌더됐고,
 *   `cn('text-sub', SUB_TEXT)` 처럼 색을 덧칠하는 모든 호출부에서 같은 일이 일어났다.
 *
 * ★ 유지보수 규칙
 *   `tailwind.config.js` 의 `theme.extend.fontSize` 에 <b>표준 스케일 밖 키를 추가하면
 *   이 배열에도 반드시 추가</b>한다. 빠뜨리면 그 토큰만 다시 색으로 오인되어 조용히 사라진다.
 *   (표준 스케일 xs/sm/base/lg/xl/2xl/3xl 은 tailwind-merge 기본 검증기가 이미 인식하므로 제외.)
 */
const CUSTOM_FONT_SIZE_TOKENS = [
  // DS-001 KRDS ladder 15단
  'display-xl',
  'display-lg',
  'display-md',
  'display-sm',
  'title-lg',
  'title-md',
  'title-sm',
  'body-lg',
  'body-md',
  'body-sm',
  'label',
  'caption',
  'button',
  'nav-link',
  'mono',
  // 기존 UI/UX §2.3 토큰(값은 ladder 로 재매핑됨)
  'page-title',
  'section-title',
  'body',
  'sub',
  'btn-label',
  'table-header',
] as const;

const twMerge = extendTailwindMerge({
  extend: {
    classGroups: {
      'font-size': [{ text: [...CUSTOM_FONT_SIZE_TOKENS] }],
    },
  },
});

/**
 * clsx + tailwind-merge — 조건부 클래스 결합 + Tailwind 충돌 해소
 */
export function cn(...inputs: ClassValue[]): string {
  return twMerge(clsx(inputs));
}
