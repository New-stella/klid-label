/**
 * E2E 테스트 데이터 fixture.
 *
 * 보안: 실제 시크릿/계정 사용 금지. BE {@code /v1/dev/tokens} 가 발급하는 테스트 JWT 만 사용.
 * (운영 prd 환경에서는 endpoint 자체가 비활성화되어 본 fixture 가 동작하지 않는다 — 안전 기본값.)
 *
 * userNo 는 시드 데이터 기준 (LS_USER) — REVIEWER=1001, WORKER=1003 (실제 DB 시드와 매칭).
 */

export const TEST_USERS = {
  reviewer: {
    userNo: '1001',
    sub: '1001',
    name: '검수자김',
    role: 'REVIEWER' as const,
    channel: 'INTERNAL' as const,
  },
  worker: {
    userNo: '1003',
    sub: '1003',
    name: '작업자이',
    role: 'WORKER' as const,
    channel: 'INTERNAL' as const,
  },
  portalUser: {
    userNo: '2001',
    sub: '2001',
    name: '포털사용자',
    role: 'PORTAL_USER' as const,
    channel: 'PORTAL' as const,
  },
};

/** 시드 데이터의 srcSn 1~5 에 라벨 존재 — 라벨링 진입 테스트는 1 사용. */
export const TEST_VIDEO_WITH_LABEL = 1;
