/**
 * 역할 표시 축의 **단일 진실원** — 표시명 + 범주 구분색.
 *
 * 왜 필요한가: 이 표(`ROLE_LABEL` / `ROLE_COLOR`)가 GNB(`Gnb`)와 접근 거부 화면
 * (`ForbiddenPage`)에 **글자 그대로 복제**돼 있었다. 같은 역할 배지의 표시명·색이 두 곳에서
 * 각자 관리되면 한쪽만 갱신돼 화면마다 다른 이름·다른 색으로 갈린다. 이 저장소가 라벨 색·
 * 표시명에서 반복해 문제 삼은 "두 번째 진실원" 패턴이라 한 곳으로 합친다.
 *
 * 색은 범주 구분색 축이다 — 역할은 서로 우열이 없는 대등한 분류라 semantic(성패·경고)이 아니라
 * DS-001 의 **범주 구분색 8슬롯**(`category-N`)에서 고른다. 이벤트 유형·라벨 형태 축과 같은 한
 * 팔레트를 공유한다.
 *
 * ⚠ semantic 대역(emerald/green/red/amber)을 되돌리지 말 것 — 포털이 emerald 면 배지가
 *   **성공**으로 읽힌다. 역할은 그 축이 아니다.
 *
 * ⚠ `RoleBadge`(UI-110 / SCREEN-024, 사용자 관리 화면)와 **합치지 말 것** — 그쪽은 미배정
 *   variant 와 경고 아이콘을 갖고 semantic 토큰(primary/secondary/warning)으로 대비를 따로
 *   실측한 별도 사양이다. 표시명이 겹치는 것은 우연이 아니라 같은 역할을 가리키기 때문이고,
 *   색 축은 서로 다르다(그 화면 사양을 바꾸는 것은 이 축의 범위가 아니다).
 */
// [@design DS-001]
export const ROLE_LABEL: Record<string, string> = {
  ADMIN: '관리자',
  REVIEWER: '검수자',
  WORKER: '작업자',
  PORTAL_USER: '포털',
};

// [@design DS-001]
export const ROLE_COLOR: Record<string, string> = {
  // 관리자는 검수자와 **다른 슬롯**을 쓴다 — 계층으로 이어져 있다고 해서 같은 색을 주면
  // 배지가 두 역할을 구분하지 못한다(배지의 존재 이유가 그 구분이다).
  ADMIN: 'bg-category-5-100 text-category-5-700',
  REVIEWER: 'bg-category-3-100 text-category-3-700',
  WORKER: 'bg-category-1-100 text-category-1-700',
  PORTAL_USER: 'bg-category-4-100 text-category-4-700',
};

/** 매핑 밖 역할 코드의 중립 폴백 — 배지를 비우지 않는다(코드 원문을 그대로 노출). */
export const ROLE_COLOR_FALLBACK = 'bg-gray-100 text-gray-600';
