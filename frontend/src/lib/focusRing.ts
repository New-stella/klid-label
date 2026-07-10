/**
 * KRDS(대한민국 정부 디자인시스템) 표준 포커스 링 유틸.
 *
 * WCAG 2.1 AA / KRDS 접근성 규칙:
 *  - focus-visible 시 3px ring + 2px offset + primary 색
 *  - 키보드 포커스에만 노출(마우스 클릭 시 링 미표시)되도록 focus-visible 사용
 *
 * 모든 인터랙티브 공통 컴포넌트가 이 상수를 재사용해 포커스 스타일을 단일화한다.
 * (인라인 제각각 focus 스타일 `focus-visible:ring-2 ring-offset-1` 등을 대체)
 */
export const KRDS_FOCUS =
  'focus-visible:outline-none focus-visible:ring-[3px] focus-visible:ring-offset-2 focus-visible:ring-primary-500';

/**
 * KRDS 최소 터치 타깃 크기(44x44px).
 *  - 버튼/입력 등 높이 최소 보장: `min-h-11` (44px)
 *  - 아이콘 전용 버튼 정사각 보장: `h-11 w-11` (44x44px)
 */
export const KRDS_HIT_AREA_MIN = 'min-h-11'; // 높이 44px
export const KRDS_ICON_HIT_AREA = 'h-11 w-11'; // 44x44px 정사각
