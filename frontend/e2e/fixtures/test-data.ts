/**
 * E2E 테스트 데이터 fixture.
 *
 * 보안: 실제 시크릿/계정 사용 금지. BE {@code /v1/dev/tokens} 가 발급하는 테스트 JWT 만 사용.
 * (운영 prd 환경에서는 endpoint 자체가 비활성화되어 본 fixture 가 동작하지 않는다 — 안전 기본값.)
 *
 * userNo 는 LS_PJT_USER_AUTHRT 시드 기준:
 *   REVIEWER=1001, LABELER(WORKER)=2001 — rawDataId 9035/9036/9037 배정.
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
  /**
   * DB LS_PJT_USER_AUTHRT에 LABELER로 배정된 실제 사용자 (userNo=2001, rawDataId=9035~9037).
   * 전체 워크플로우 E2E(labeling-review-full-flow.spec.ts)에서 WORKER 역할로 사용.
   */
  labeler: {
    userNo: '2001',
    sub: '2001',
    name: '라벨작업자',
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

/**
 * 전체 워크플로우 E2E 전용 상수.
 * rawDataId=9035, labeler=2001이 배정됨. srcSn=241이 첫 번째 프레임.
 */
export const WORKFLOW_VIDEO_ID = 9035;
export const WORKFLOW_SRC_SN = 241;
