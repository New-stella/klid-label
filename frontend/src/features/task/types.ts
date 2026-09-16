// 작업 배정 도메인 타입 (BE OpenAPI alias)
//
// ★검수자 배정 축이 이 도메인에서 빠졌다 — 검수는 배정 없이 전체 대기열에서 집어가므로
// 「이 영상의 검수자」라는 값이 존재하지 않는다. 이력 원장에는 그 대신 「검수 시작」과
// **행위 시점 역할**이 들어온다.
//
// [@design ADR-067] [@design API-070] [@design API-072] [@design API-116]

import { type AugType } from '@/features/augment/augTypeLabel';

/**
 * 배정 워크플로 상태 — BE `GET /v1/assignments` 의 **응답 status 와 필터 `workStatus` 가 같은 값 집합**이다
 * (BE `AssignmentWorkStatus` allowlist 와 1:1). 그래서 표시용 타입을 그대로 필터 파라미터로 쓴다.
 *
 * ★ board 축({@link WorkStatusParam})과 혼동 금지 — 그쪽은 `IN_PROGRESS` 를 반환하지도 허용하지도 않고
 * 대신 `UNASSIGNED` 를 갖는다(미배정 영상까지 다루므로). 두 축은 값 집합이 다르다.
 */
export const ASSIGNMENT_STATUS_VALUES = [
  'PENDING',
  'IN_PROGRESS',
  'REVIEW_PENDING',
  'COMPLETED',
  'REJECTED',
] as const;

export type AssignmentStatus = (typeof ASSIGNMENT_STATUS_VALUES)[number];

export interface Worker {
  id: number;
  name: string;
  email?: string;
  active: boolean;
}

export interface Task {
  id: number;
  videoId: number;
  cctvName: string;
  workerId: number;
  workerName: string;
  status: AssignmentStatus;
  assignedAt: string;
  /**
   * 해당 영상의 첫 프레임 SRC_SN (LS_DATA_SRC PK).
   * WORKER 가 "작업" 버튼 클릭 시 /label/{firstSrcSn} 으로 navigate 한다.
   * 프레임이 아직 생성되지 않은 영상이면 undefined.
   */
  firstSrcSn?: number;
  /**
   * 영상 이벤트 정보 (LS_DATA_RAW.EVNT_TYPE_CD 기반).
   * 영상 메타가 없거나 코드값 부재 시 undefined — FE 는 "-" 폴백 표시.
   * WORKER 시각 TaskListPage 가 useVideos 의존 없이 이벤트 컬럼을 채울 수 있도록 BE 에서 enrich.
   */
  eventName?: string;
  eventTypeCd?: string;
  /**
   * 비식별 처리 여부/상태 (BE LS_DATA_RAW.DE_IDENT_YN / deidentStatus).
   * 마킹 진입 차단(AC4) 판정용 — 'Y'/DONE 이 아니면 WORKER 마킹 버튼을 비활성화한다.
   */
  deIdntfYn?: 'Y' | 'N' | 'F';
  deidentStatus?: string;
  /**
   * 증강/해상도 파생 데이터 여부 (BE additive). true 면 작업 목록에 증강 뱃지를 노출한다.
   * 구 응답에 없을 수 있어 optional — 없으면 원본(false)으로 취급한다.
   */
  augmented?: boolean;
  /**
   * 증강 종류 코드 (WINTER/NIGHT/RAIN/RESL_1080P/RESL_720P/RESL_480P).
   * null=종류 미상(뱃지에 '증강'만 표시). augmented=false 면 무시된다.
   */
  augType?: AugType | null;
}

export interface Assignment {
  id: number;
  videoId: number;
  workerId: number;
  status: AssignmentStatus;
  assignedAt: string;
}

/**
 * `GET /v1/assignments` 쿼리 파라미터 (WORKER 작업목록).
 *
 * - `q`/`workStatus`/`eventTypeCd` 는 **서버사이드 필터**다 — 전체 배정 기준으로 걸러지고
 *   `totalElements` 도 그 결과 기준이다(화면에서 다시 거르지 않는다).
 * - 빈 문자열은 절대 넣지 않는다 — 값이 없으면 **키 자체를 생략**한다(조립은 `boardParams.ts` 빌더).
 * - `workerId` 는 REVIEWER 전용 필터이며 WORKER 요청에서는 **서버가 무시**한다(403 아님).
 * - `sort` 는 BE allowlist(regDt/assignedAt/rawDataId/videoId/assignmentId/id) 밖이면 400 이다.
 *   WORKER 화면에는 정렬 UI 가 없어 보내지 않는다 — board 정렬 키(shtDt/rawSn)는 이 allowlist 밖이라
 *   URL 에 남아 있는 값을 그대로 흘려보내면 목록이 400 으로 죽는다.
 */
export interface TaskListParams {
  workerId?: number;
  q?: string;
  workStatus?: AssignmentStatus;
  eventTypeCd?: string;
  page?: number;
  size?: number;
  sort?: string;
}

/**
 * `GET /v1/assignments/event-types` 파라미터.
 *
 * BE 는 목록과 같은 축(workerId/q/workStatus)을 받지만 **FE 는 아무것도 보내지 않는다** — 옵션이
 * 다른 필터로 좁아지면 이미 고른 이벤트유형이 목록에서 사라져 되돌아갈 수 없다(board 와 같은 규약).
 * 조회 범위는 서버가 인가로 고정한다(WORKER = 본인 배정).
 */
export type AssignmentEventTypeParams = Pick<TaskListParams, 'workerId'>;

/**
 * REVIEWER 통합 작업 목록 — BE /v1/tasks/board 응답 1행.
 *
 * 처리 완료 영상 + (optional) LABELER 배정을 LEFT JOIN 한 형태.
 * task 측 필드(workerId/workerName/assignmentId/assignedAt/firstSrcSn) 가
 * 모두 null 인 경우 미배정 영상이며, status 는 'UNASSIGNED' 로 폴백된다.
 *
 * ★검수자 축은 이 응답에 **없다** — 검수는 배정 없이 전체 대기열에서 집어간다(`ADR-067`).
 * 「지금 누가 검수 중인가」는 검수 목록의 점유 표시가 따로 보여준다.
 *
 * AssignmentStatus 외에 'UNASSIGNED' 값을 갖는 점에 주의 — FE STATUS_BADGE_MAP 매핑은
 * TaskListPage 의 RowStatus(= AssignmentStatus | 'UNASSIGNED') 에 그대로 흘려보낸다.
 */
export interface TaskBoardItem {
  videoId: number;
  cctvName: string | null;
  eventName: string | null;
  eventTypeCd: string | null;
  frameCount: number;
  capturedAt: string | null;
  batchStatus: string | null;
  status: AssignmentStatus | 'UNASSIGNED';
  // LABELER 배정 (left-join)
  assignmentId: number | null;
  workerId: number | null;
  workerName: string | null;
  assignedAt: string | null;
  firstSrcSn: number | null;
  // 비식별 처리 여부/상태 (마킹 진입 차단 판정 — AC4). BE 미전송 시 null/undefined.
  deIdntfYn?: 'Y' | 'N' | 'F' | null;
  deidentStatus?: string | null;
  // 증강/해상도 파생 여부 + 종류 (BE additive) — 작업 목록 증강 뱃지용.
  augmented?: boolean | null;
  augType?: AugType | null;
}

/**
 * BE `GET /v1/tasks/board` 의 **배치 상태 축**(`status`) 허용값 — `LS_DATA_RAW.DATA_STTS_CD` 기반.
 *
 * `UNASSIGNED` 는 "배치 상태 필터를 끄고 미배정 전체" 를 뜻하는 **가상 status** 다.
 * 워크플로 축의 `UNASSIGNED`(= 현재 배치 상태 안에서의 미배정)와 **다른 집합**이므로
 * KPI 카드 클릭은 이 축이 아니라 {@link WorkStatusParam} 축으로 보내야 한다.
 */
export const BATCH_STATUS_PARAMS = {
  COMPLETED: 'COMPLETED',
  UNASSIGNED: 'UNASSIGNED',
  ASSIGNED: 'ASSIGNED',
  PENDING: 'PENDING',
  IN_REVIEW: 'IN_REVIEW',
  APPROVED: 'APPROVED',
  REJECTED: 'REJECTED',
} as const;

export type BatchStatusParam =
  (typeof BATCH_STATUS_PARAMS)[keyof typeof BATCH_STATUS_PARAMS];

/**
 * BE `GET /v1/tasks/board` 의 **워크플로 축**(`workStatus`) 허용값.
 *
 * ★ 표시용 상태({@link AssignmentStatus} / RowStatus)와 **일부러 분리**한 서버 전송 전용 union 이다.
 * BE `TaskBoardService.mapBoardStatus` 는 `IN_PROGRESS` 를 **절대 반환하지 않으며**(배정됨 = PENDING),
 * 그 값을 파라미터로 보내면 400 이다. 표시용 타입을 그대로 재사용하면 이 차이가 타입에서 사라진다.
 */
export const WORK_STATUS_PARAMS = {
  UNASSIGNED: 'UNASSIGNED',
  PENDING: 'PENDING',
  REVIEW_PENDING: 'REVIEW_PENDING',
  COMPLETED: 'COMPLETED',
  REJECTED: 'REJECTED',
} as const;

export type WorkStatusParam =
  (typeof WORK_STATUS_PARAMS)[keyof typeof WORK_STATUS_PARAMS];

/**
 * `GET /v1/tasks/board` 쿼리 파라미터.
 *
 * - `status` / `workStatus` 는 **독립 축**이며 AND 결합된다 (필드명을 분리해 혼동을 타입에서 차단).
 * - 빈 문자열은 절대 넣지 않는다 — BE `status=` 는 400 이다. 값이 없으면 **키 자체를 생략**한다
 *   (조립은 `boardParams.ts` 의 빌더가 담당).
 * - `sort` 는 `"{key},{dir}"` 문자열 배열. BE allowlist(regDt/shtDt/rawSn) 밖이거나 4개 이상이면 400 이라
 *   `boardSort.ts` 가 매핑·상한을 강제한다.
 */
export interface TaskBoardParams {
  status?: BatchStatusParam;
  workStatus?: WorkStatusParam;
  q?: string;
  eventTypeCd?: string;
  workerId?: number;
  page?: number;
  size?: number;
  sort?: string[];
  /**
   * **제외분만 보기** — BE `excludedOnly`. [@design API-073] [@design ADR-069]
   *
   * 보내지 않거나 `false` 면 제외 표시가 붙지 않은 영상만 보는 기본 목록이고, `true` 면 제외된
   * 영상만 남는다. **가시 범위 축이지 거르는 값이 아니다** — 그래서 집계 요청에도 그대로 실린다
   * (아래 {@link TaskBoardSummaryParams} 가 이 키를 빼지 않는 이유).
   *
   * ⚠ 이 값이 켜지면 **작업 진행 상태 축(`workStatus`)을 함께 보내지 않는다** — 「제외됨 건수」를
   * 주는 집계 창구가 그 축을 반영하지 않고 세기 때문이다. 상태로 좁힌 채 그 숫자를 누르면
   * 전환 결과가 누른 숫자보다 적어진다. 배선은 `boardParams.buildBoardParams` 한 곳이 소유한다.
   */
  excludedOnly?: boolean;
}

/**
 * `GET /v1/tasks/board/summary` 파라미터 — ★ workStatus 는 보내지 않는다(카드 자체가 선택지).
 *
 * ⚠ `excludedOnly` 는 **뺀 목록에 넣지 않는다** — 그것은 거르는 값이 아니라 가시 범위라,
 *   집계도 목록과 같은 범위를 봐야 카드 숫자와 목록이 어긋나지 않는다.
 */
export type TaskBoardSummaryParams = Omit<
  TaskBoardParams,
  'workStatus' | 'page' | 'size' | 'sort'
>;

/** `GET /v1/tasks/board/event-types` 파라미터 — status 축만 반영한다. */
export interface TaskBoardEventTypeParams {
  status?: BatchStatusParam;
}

/**
 * `GET /v1/tasks/board/summary` 응답 — **필터 결과 전체 기준** KPI 집계(현재 페이지가 아니다).
 *
 * 불변식: `total === unassigned + inProgress + reviewPending + completed + rejected`.
 * `inProgress` 는 BoardWorkStatus.PENDING(배정됨·검수 미제출) 집계다 — IN_PROGRESS 값은 존재하지 않는다.
 */
export interface TaskBoardSummary {
  total: number;
  unassigned: number;
  inProgress: number;
  reviewPending: number;
  completed: number;
  rejected: number;
  /**
   * **제외됨** — 화면 목록에서 뺀 영상 건수. [@design API-136] [@design AC-1124] [@design ADR-069]
   *
   * ★<b>위 불변식(버킷 합 = total)의 항이 아니다</b> — 제외분은 `total` 에서도 이미 빠져 있는
   * **별개 축**이라 합에 더하면 불변식이 깨진다. KPI 카드로 그리지 않고 목록 표 위에 따로 둔다.
   *
   * ★<b>이 숫자의 진실원은 집계 창구 하나다</b> — 목록 응답(`GET /v1/tasks/board`)에는 이 키가
   * 없다. 같은 숫자를 두 창구가 각각 계산하면 한쪽만 조건이 바뀌어도 드러나지 않는다.
   *
   * ⚠ <b>작업 진행 상태 축을 반영하지 않고 센다</b> — 상태에 가려진 제외분까지 세어야 감춰진
   * 것이 있다는 사실 자체가 드러나기 때문이다. 그래서 화면은 이 숫자를 눌러 제외분 보기로
   * 전환할 때 **그 상태 축 조건을 빼고** 요청한다(그러지 않으면 전환 결과가 누른 숫자보다 적다).
   *
   * 값이 `0` 이어도 응답에 실린다 — 0 이라고 키가 빠지면 화면이 「제외된 것이 없다」와
   * 「제외 기능이 없다」를 구분해 보여 주지 못한다. 값을 못 내리는 구 응답만 `undefined` 다.
   */
  excludedCount?: number;
}

/**
 * 이벤트유형 옵션 응답 — ★ 배열이 아니라 **객체**다.
 * `GET /v1/tasks/board/event-types`(REVIEWER)와 `GET /v1/assignments/event-types`(WORKER)가
 * **같은 형태**를 반환하므로 화면이 한 컴포넌트로 두 경로를 다룬다.
 *
 * `truncated=true` 면 `items` 는 전체가 아니다(상한 500 초과 절단) — 화면이 그 사실을 알려야
 * "그 이벤트유형 영상이 없다" 는 오인을 막는다.
 */
export interface EventTypeOptionsResponse {
  items: string[];
  truncated: boolean;
}

/**
 * 작업 배정 요청.
 *
 * ★검수자 항목이 **없다** — 검수는 배정 없이 전체 대기열에서 집어간다(`ADR-067`). 이 항목은
 * 값을 쌓기만 하고 아무것도 게이트하지 않는 장식이었다(검수 목록·승인·반려 어디에도 배정 검사가
 * 없었다). BE 는 옛 호출자가 보내면 400 이 아니라 **무시**하지만, 화면이 다시 보내지는 않는다.
 */
export interface AssignTaskRequest {
  workerId: number;
  /** 영상(LS_DATA_RAW) PK 목록 — 1건 이상 필수. */
  rawDataIds: number[];
}

export interface ReassignTaskRequest {
  workerId: number;
}

/**
 * 작업(영상) 단위 이벤트 타입 — LS_TASK_EVNT_LOG.EVENT_TYPE_CD.
 * SCR-TASK-003 타임라인에서 배정/재배정/검수 제출/승인/반려를 동일 구조로 표현한다.
 *
 * ★**값역의 정본은 BE 의 `LsTaskEventLog` 상수다** — 여기서 지어내지 말고 그 목록과 대조해
 * 채운다. 이력 조회에는 **종류로 거르는 질의 항목이 없어**, 원장에 쌓이는 종류는 예외 없이
 * 화면까지 실려 온다. 즉 여기 빠진 종류는 「안 오는 값」이 아니라 **문구 없이 오는 값**이고,
 * `describeEvent` 의 폴백이 그 코드값을 사람에게 그대로 노출한다(`UI-084` 가 금지한 상태).
 */
export type TaskEventType =
  | 'ASSIGN'
  | 'REASSIGN'
  // 검수 시작 — 그 영상의 **점유를 세우는** 기록이다(`ADR-067`). 전용 컬럼·표를 두지 않고
  // 이 원장 한 곳으로 표현하므로, 타임라인에 이 줄이 새로 보인다. 문구가 없으면
  // `describeEvent` 의 폴백이 코드값(`START_REVIEW`)을 그대로 노출한다.
  | 'START_REVIEW'
  | 'SUBMIT'
  | 'CANCEL_SUBMIT'
  | 'APPROVE'
  | 'REJECT'
  // 감사(OWASP A09) 이벤트 — 배정/검수 워크플로가 아니라 개인정보 선언 변경 추적용.
  // BE 는 판단값(Y/N)을 보내지 않는다(CWE-359) — 사유는 고정 문구뿐이다.
  | 'PRIVACY_META_UPDATE'
  | 'PRIVACY_META_RESET'
  // 산출물 구성·시작점을 바꾼 기록 — 배정·검수가 앞으로 나아간 일이 아니라 **무엇이 언제
  // 바뀌었는지**를 남긴 축이다(그래서 점 색상도 개인정보 감사 2종과 같은 중립 톤이다).
  | 'FRAME_DISCARD'
  | 'FRAME_RESTORE'
  | 'START_VERSION_APPLY';

/**
 * 배정 이력 한 row — BE `AssignmentHistoryResponse` alias.
 *
 * 이벤트 타입별 사용 필드:
 * - ASSIGN       : actor=배정자(REVIEWER), subject=배정된 작업자
 * - REASSIGN     : actor=재배정자(REVIEWER), subject=새 작업자, prev=이전 작업자
 * - START_REVIEW : actor=검수를 시작한 사람 — 그 영상의 점유를 세운다
 * - SUBMIT       : actor=subject=작업자(본인 제출)
 * - APPROVE      : actor=검수자(REVIEWER 또는 ADMIN)
 * - REJECT       : actor=검수자(REVIEWER 또는 ADMIN), reason=반려 사유
 */
export interface AssignmentHistory {
  eventSeq: number;
  eventTypeCd: TaskEventType;
  actorUserNo: number | null;
  actorUserName: string | null;
  /**
   * 행위 **시점**의 행위자 역할(`ADMIN`/`REVIEWER`/`WORKER`).
   *
   * ★조회 시점에 그 사람의 **지금 역할**을 다시 읽은 값이 아니다 — 역할이 바뀌어도 과거 행위의
   * 역할은 그대로 남는 것이 의도다(관리자가 승인한 건은 영영 관리자로 남는다).
   *
   * 이 축이 생기기 전에 쌓인 옛 이력은 `null` 이다 — **백필하지 않았다**(복원할 수 없는 값을
   * 지어내면 사실처럼 남는다). 화면은 그때 **역할을 비워** 보이고 빈 괄호를 남기지 않는다.
   */
  actorRoleCd?: string | null;
  subjectUserNo: number | null;
  subjectUserName: string | null;
  prevUserNo: number | null;
  prevUserName: string | null;
  reason: string | null;
  occurredAt: string;
}
