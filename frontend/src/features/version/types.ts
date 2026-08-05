// 버전 도메인 타입 (BE 확정 계약 — Phase 6 DB 스냅샷 기반 버전관리와 동기화)
//
// BE는 외부 VCS → DB(라벨 스냅샷) 기반으로 전환되었으나, 와이어 호환을 위해 응답 필드명
// (commitSha/shortHash)은 유지된다. 값의 의미는 모두 versionHash = 라벨 스냅샷 SHA-256 hex(64자).
// 프레임(srcSn) 단위로 라벨 스냅샷을 버전화한 이력 + diff(라벨 단위 변경) 표현.

import type { Shape } from '@/features/label/types';

/**
 * 프레임 1건의 라벨 스냅샷 버전 이력 항목 (시간 역순).
 * - commitSha: versionHash — 라벨 스냅샷 SHA-256 hex(64자). 멱등(동일 스냅샷 재커밋 시 동일 해시).
 * - shortHash: 표시용 짧은 해시 (앞 7자)
 * - authorName: 작성자 표시명(LS_ACNT_USER.USER_NM). ★ BE 계약 변경 — 이전에는 이 필드에
 *   사번(REG_ID)이 담겨 화면에 "2001" 같은 내부 번호가 찍혔다. 이름 해석 실패 시 null 이므로
 *   화면은 반드시 `resolveDisplayName(authorName, authorNo)` 로 사번 폴백한다(빈칸 금지).
 * - authorNo: 작성자 사번(REG_ID) — 폴백 원값
 * - message: 변경 사유 코드 (MANUAL | ROLLBACK | BATCH)
 * - isCurrent: 현재 버전 여부 — 목록 상단에 "현재" 뱃지 노출
 *
 * 보안: versionHash는 BE에서 hex 검증 후 클라이언트로 내려옴 — FE는 단순 전달만.
 */
export interface Version {
  commitSha: string; // = versionHash (SHA-256 hex 64)
  shortHash: string; // 앞 7자
  authorName: string | null;
  authorNo: string | null;
  message: string; // MANUAL | ROLLBACK | BATCH
  committedAt: string; // ISO-8601
  isCurrent: boolean;
}

export const DiffType = {
  ADDED: 'ADDED',
  MODIFIED: 'MODIFIED',
  REMOVED: 'REMOVED',
} as const;
export type DiffType = (typeof DiffType)[keyof typeof DiffType];

/**
 * 두 버전 간 라벨 단위 차이 (BE 확정 계약 LabelDiff).
 * BE: GET /v1/versions/{toHash}/diff?compareWith={fromHash} → LabelDiff[]
 * - ADDED: after만 존재 (새로 생성된 라벨), before=null
 * - MODIFIED: before/after 둘 다 (모양/위치 변경)
 * - REMOVED: before만 존재 (삭제된 라벨), after=null
 *
 * before/after Shape 는 diff 계약상 BBOX | POLYGON 만 사용 (label/types 의 Shape 부분집합).
 * BE 는 미존재 측을 JSON null 로 내려주므로 null/undefined 양쪽을 허용한다.
 */
export interface LabelDiff {
  type: DiffType;
  frameId: number;
  objectId: string;
  before?: Shape | null;
  after?: Shape | null;
}

/**
 * 롤백 결과 (BE 확정 계약).
 * 지정한 versionHash 의 라벨 스냅샷으로 되돌린 뒤 신규 이력(LS_DATA_LBL_HSTRY) 1건을 적재한 결과.
 * - lblHstrySn: 신규 라벨 이력 PK
 * - srcSn: 롤백 대상 프레임 PK
 * - versionHash: 롤백된(=복원된) 스냅샷 해시 (SHA-256 hex 64)
 * - registeredUserNo / registeredAt: 롤백 수행자 사번 / 시각
 *   ⚠ 사번은 **문자열**이다 — BE 는 `LS_DATA_LBL_HSTRY.REG_ID`(VARCHAR)를 그대로 내려주며
 *   숫자가 아닌 레거시 사번도 있을 수 있다(`VersionResponse.Item.registeredUserNo: String`).
 * - registeredUserName: 롤백 수행자 표시명(LS_ACNT_USER.USER_NM). 해석 실패 시 null →
 *   표시에는 `resolveDisplayName(registeredUserName, registeredUserNo)` 로 사번 폴백
 */
export interface RollbackResponse {
  lblHstrySn: number;
  srcSn: number;
  versionHash: string;
  registeredUserNo: string;
  registeredUserName: string | null;
  registeredAt: string; // ISO-8601
}
