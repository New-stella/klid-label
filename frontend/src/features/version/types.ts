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

/**
 * 영상 단위 산출 버전 1건 — 「시작 버전 선택」 목록 항목 (BE VideoVersionItem 과 1:1).
 *
 * - versionNo   : 산출 버전 번호. 관제가 픽업하는 산출 폴더 `v{n}` 과 **같은 번호**다.
 * - snapshotCnt : 그 회차에 <b>내용이 바뀌어</b> 스냅샷이 새로 생긴 프레임 수(영상 전체 프레임 수가 아니다).
 * - latestRegDt : 그 회차 스냅샷 중 가장 늦은 생성 시각.
 *
 * ⚠ <b>번호가 건너뛰어 보이는 것은 정상이다</b> — 어떤 회차에 모든 프레임 내용이 그대로였다면 그
 * 회차 스냅샷이 하나도 생기지 않아 목록에서 빠진다(직전 회차와 완전히 같은 상태라 선택지로서
 * 의미가 없다). 화면이 이를 결손으로 표시하거나 빠진 번호를 만들어 채우지 않는다.
 *
 * @design D4
 * @req R6
 */
export interface VideoVersion {
  versionNo: number;
  snapshotCnt: number;
  latestRegDt: string | null; // ISO-8601 (LocalDateTime)
}

/**
 * API-195 — 불러온 산출 회차의 프레임 1건 (BE VersionLabelsResponse.Frame 과 1:1).
 *
 * ⚠ <b>이 값은 아직 확정이 아니다</b> — 불러오기는 서버에 아무것도 쓰지 않으므로, 저장(API-196)을
 * 누르지 않고 화면을 떠나면 서버 작업본이 그대로 남는다.
 *
 * - `lblVer`   : 확정 저장에 <b>되돌려 보낼</b> 낙관적 동시성 토큰. 보내지 않으면 BE 가 전수 검증을
 *                할 수 없어 남의 저장을 덮어쓴다.
 * - `resolved` : `false` 면 그 회차 이하 스냅샷이 없어 <b>현재 작업본</b>이 실려 온 것이다(그 프레임은
 *                이 세트를 저장해도 no-op). 화면은 이 사실을 감추지 않는다.
 * - `items`    : BE 라벨 항목(스냅샷 원형). `labelId` 가 반드시 함께 온다 — 잃으면 저장 후 라벨
 *                마스터 조인이 끊겨 색상·라벨명·속성 정의가 함께 사라진다.
 *
 * @design API-195
 * @req R6
 */
export interface VersionLabelFrame {
  srcSn: number;
  frmNo: number;
  dscdYn: 'Y' | 'N';
  lblVer: number;
  resolved: boolean;
  items: VersionLabelItem[];
}

/** 불러온 라벨 항목 — BE LabelResponse.Item 부분집합(확정 저장에 되돌려 보내는 필드만 선언). */
export interface VersionLabelItem {
  id: number | null;
  lblTypeCd: string;
  label: string | null;
  labelId: number | null;
  points: number[][];
  trackId?: string | null;
}

/** API-195 응답 — 그 회차 시점의 영상 전체 라벨·폐기 상태. */
export interface VersionLabelsResponse {
  rawSn: number;
  version: number;
  frames: VersionLabelFrame[];
}

/**
 * API-196 — 영상 라벨 일괄 확정 저장 요청.
 *
 * `loadedVersion` 은 어느 산출 회차에서 시작한 편집인지 기록용이며 저장 여부를 가르지 않는다.
 * 불러오기를 거치지 않았으면 비운다.
 *
 * @design API-196
 */
export interface VideoLabelSavePayload {
  frames: VideoLabelSaveFrame[];
  loadedVersion?: string | null;
}

export interface VideoLabelSaveFrame {
  srcSn: number;
  /** 불러오기가 내려준 판번호를 그대로 되돌려 보낸다 — 하나라도 어긋나면 영상 전체가 409 다. */
  lblVer: number;
  items: VersionLabelItem[];
  /** 보내지 않으면 BE 가 현재 폐기 값을 그대로 둔다. */
  dscdYn?: 'Y' | 'N' | null;
}

/** API-196 응답 — 저장 결과 요약(다음 저장에 쓸 판번호 포함). */
export interface VideoLabelSaveResult {
  rawSn: number;
  frames: { srcSn: number; dscdYn: 'Y' | 'N'; lblVer: number }[];
  savedFrameCount: number;
  discardedFrameCount: number;
}
