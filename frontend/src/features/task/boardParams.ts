// 작업목록(SCR-TASK-001) 필터 상태 ↔ URL ↔ 서버 파라미터 **단일 매핑 지점**.
//
// 같은 필터 상태가 역할에 따라 **다른 엔드포인트**로 나간다 — REVIEWER 는 `/v1/tasks/board`,
// WORKER 는 `/v1/assignments`. 두 축은 워크플로 허용값이 다르므로(아래 asWorkStatusParam /
// asAssignmentWorkStatusParam) 조립 함수를 축별로 분리한다. 축을 섞어 보내면 BE 가 400 이다.
//
// 세 이름(로컬 필드 / URL 키 / API 파라미터)이 어긋나면 새로고침 후 필터가 유실되거나 잘못된 축으로
// 복원된다. 이 파일 밖에서 필터 이름을 다시 쓰지 않는 것이 유일한 방어다.
//
//   로컬 필드      URL 키        API 파라미터
//   ------------- ------------- --------------
//   q             q             q            (BE @Size max 100)
//   workStatus    status        workStatus   (워크플로 축 — 구 화면과 동일한 URL 키)
//   assigneeId    assigneeId    workerId     (양수만)
//   eventTypeCd   eventTypeCd   eventTypeCd  (BE @Size max 20)
//   (정렬)         sort          sort         (서버 키 배열, 최대 3)
//
// ★ 배치 상태 축(API `status`)은 **URL 에서 읽지 않는다.**
// 화면에 배치 축 필터 UI 가 없는데 URL 로만 바꿀 수 있으면, 구 URL(`?status=UNASSIGNED` — 그 시절엔
// 워크플로 값이었다)이 조용히 다른 집합(파이프라인 미완료 영상 포함)을 띄우고 부제("처리 완료된 영상만
// 표시")가 거짓이 된다. 배치 축은 {@link BOARD_BATCH_STATUS} 고정이다.

import { compactParams } from '@/lib/compactParams';

import {
  DEFAULT_BOARD_SORT,
  parseBoardSort,
  toBoardSortParams,
  type BoardSortEntry,
} from './boardSort';
import {
  ASSIGNMENT_STATUS_VALUES,
  BATCH_STATUS_PARAMS,
  WORK_STATUS_PARAMS,
  type AssignmentEventTypeParams,
  type AssignmentStatus,
  type BatchStatusParam,
  type TaskBoardEventTypeParams,
  type TaskBoardParams,
  type TaskBoardSummaryParams,
  type TaskListParams,
  type WorkStatusParam,
} from './types';

/**
 * 작업목록 필터 상태.
 *
 * 배치 상태 축은 필드로 두지 않는다 — 화면이 다룰 수 있는 축만 상태로 갖는다.
 */
export interface TaskFilterValues {
  q: string;
  /** 워크플로 축 — BE `workStatus`. 빈 문자열이면 필터 미적용. */
  workStatus: string;
  /** 작업자 PK 문자열 — BE `workerId`. */
  assigneeId: string;
  /** 이벤트유형 코드 — BE `eventTypeCd`. */
  eventTypeCd: string;
}

/** BE `@Size(max = 100)` 정합 — 초과분은 400 왕복 대신 FE 에서 자른다. */
export const MAX_SEARCH_KEYWORD_LENGTH = 100;

/**
 * 작업목록이 사용하는 **고정** 배치 상태 축.
 * 헤더 부제("처리 완료된 영상만 표시")와 한 몸이다 — 바꾸려면 부제와 함께 바꾼다.
 */
export const BOARD_BATCH_STATUS: BatchStatusParam = BATCH_STATUS_PARAMS.COMPLETED;

export const DEFAULT_TASK_FILTERS: TaskFilterValues = {
  q: '',
  workStatus: '',
  assigneeId: '',
  eventTypeCd: '',
};

/**
 * 화면(URL·select)에서 허용하는 워크플로 값 — **두 축의 합집합**이다.
 *
 * 상태 select 의 선택지는 역할마다 다르고(board 축은 `UNASSIGNED`, 배정 축은 `IN_PROGRESS`),
 * URL 은 두 역할이 공유한다. 합집합으로 두지 않으면 한쪽 값이 새로고침에서 조용히 사라진다.
 * 서버로 나갈 때는 축별 정규화({@link asWorkStatusParam} / {@link asAssignmentWorkStatusParam})가
 * 각자의 allowlist 밖 값을 떨어뜨린다.
 */
const UI_WORK_STATUS_VALUES: readonly string[] = [
  ...Object.values(WORK_STATUS_PARAMS),
  ...ASSIGNMENT_STATUS_VALUES,
];

/** URL/로컬 워크플로 값 정규화 — allowlist 밖이면 빈 문자열(필터 미적용). */
export function asUiWorkStatus(raw: string | null | undefined): string {
  const value = (raw ?? '').trim();
  return UI_WORK_STATUS_VALUES.includes(value) ? value : '';
}

/**
 * 서버 전송용 워크플로 축 정규화 — allowlist 밖이면 `undefined`(필터 미적용).
 *
 * 특히 `IN_PROGRESS` 는 BE 가 **반환하지도, 허용하지도 않는** 값이라 여기서 떨어진다.
 */
export function asWorkStatusParam(
  raw: string | null | undefined,
): WorkStatusParam | undefined {
  const value = (raw ?? '').trim();
  const allowed = Object.values(WORK_STATUS_PARAMS) as string[];
  return allowed.includes(value) ? (value as WorkStatusParam) : undefined;
}

/**
 * 배정 목록(`GET /v1/assignments`) 워크플로 축 정규화 — allowlist 밖이면 `undefined`.
 *
 * board 축과 값 집합이 다르다: `IN_PROGRESS` 는 여기서만 유효하고(BE 가 라벨 저장 이력으로 판정),
 * `UNASSIGNED` 는 여기 없다(본인에게 배정된 행만 다루므로 개념 자체가 없다). BE 는 미등록 값을
 * 400 으로 거부하므로 축을 섞어 보내면 목록 전체가 죽는다.
 */
export function asAssignmentWorkStatusParam(
  raw: string | null | undefined,
): AssignmentStatus | undefined {
  const value = (raw ?? '').trim();
  const allowed = ASSIGNMENT_STATUS_VALUES as readonly string[];
  return allowed.includes(value) ? (value as AssignmentStatus) : undefined;
}

/** 작업자 PK 정규화 — 양수만(BE `@Positive`). */
function asWorkerId(raw: string | null | undefined): number | undefined {
  const value = (raw ?? '').trim();
  if (!value) return undefined;
  const n = Number(value);
  return Number.isSafeInteger(n) && n > 0 ? n : undefined;
}

function asKeyword(raw: string): string | undefined {
  const value = raw.trim();
  return value ? value.slice(0, MAX_SEARCH_KEYWORD_LENGTH) : undefined;
}

function asEventTypeCd(raw: string): string | undefined {
  const value = raw.trim();
  return value ? value.slice(0, 20) : undefined;
}

interface BoardPageOptions {
  page: number;
  size: number;
  sort: readonly BoardSortEntry[];
}

/**
 * 목록 요청 파라미터. 빈 값은 `compactParams` 가 키째로 제거해 `status=` 400 을 만들지 않는다.
 * 정렬은 항상 명시 전송한다(BE 기본값 의존 금지).
 */
export function buildBoardParams(
  filters: TaskFilterValues,
  { page, size, sort }: BoardPageOptions,
): TaskBoardParams {
  return compactParams<TaskBoardParams>({
    status: BOARD_BATCH_STATUS,
    workStatus: asWorkStatusParam(filters.workStatus),
    q: asKeyword(filters.q),
    eventTypeCd: asEventTypeCd(filters.eventTypeCd),
    workerId: asWorkerId(filters.assigneeId),
    page,
    size,
    sort: toBoardSortParams(sort.length ? sort : DEFAULT_BOARD_SORT),
  }) as TaskBoardParams;
}

/**
 * KPI 집계 요청 파라미터.
 *
 * ★ `workStatus` 를 **의도적으로 제외**한다 — 카드 자체가 workStatus 선택지라,
 * 이미 좁혀진 집합 위에서 세면 선택한 카드 하나만 값을 갖는다.
 */
export function buildBoardSummaryParams(
  filters: TaskFilterValues,
): TaskBoardSummaryParams {
  return compactParams<TaskBoardSummaryParams>({
    status: BOARD_BATCH_STATUS,
    q: asKeyword(filters.q),
    eventTypeCd: asEventTypeCd(filters.eventTypeCd),
    workerId: asWorkerId(filters.assigneeId),
  }) as TaskBoardSummaryParams;
}

/**
 * 이벤트유형 옵션 요청 파라미터 — 배치 상태 축만. 검색어/작업자로 옵션이 좁아지면
 * 사용자가 필터를 건 뒤 되돌아갈 수 없다.
 */
export function buildBoardEventTypeParams(): TaskBoardEventTypeParams {
  return { status: BOARD_BATCH_STATUS };
}

interface AssignmentPageOptions {
  page: number;
  size: number;
}

/**
 * WORKER 작업목록(`GET /v1/assignments`) 요청 파라미터.
 *
 * 필터는 **서버**가 전체 배정 기준으로 적용한다(화면 재필터 금지). 빈 값은 `compactParams` 가
 * 키째로 제거해 "필터 미적용" 을 뜻하는 기존 호출 형태를 그대로 유지한다.
 *
 * ★ 정렬은 보내지 않는다 — WORKER 화면에는 정렬 UI 가 없고, URL 에 남아 있을 수 있는 board 정렬 키
 * (`shtDt`/`rawSn`)는 이 엔드포인트 allowlist 밖이라 그대로 전달하면 400 이 된다.
 */
export function buildAssignmentParams(
  filters: TaskFilterValues,
  { page, size }: AssignmentPageOptions,
): TaskListParams {
  return compactParams<TaskListParams>({
    q: asKeyword(filters.q),
    workStatus: asAssignmentWorkStatusParam(filters.workStatus),
    eventTypeCd: asEventTypeCd(filters.eventTypeCd),
    workerId: asWorkerId(filters.assigneeId),
    page,
    size,
  }) as TaskListParams;
}

/**
 * 배정 이벤트유형 옵션 요청 파라미터 — **아무 축도 보내지 않는다**.
 *
 * BE 는 목록과 같은 축(q/workStatus)을 받지만, 옵션이 다른 필터로 좁아지면 이미 고른 이벤트유형이
 * 목록에서 사라져 되돌아갈 수 없다(board 와 동일한 판단). 조회 범위는 서버 인가가 고정한다.
 */
export function buildAssignmentEventTypeParams(): AssignmentEventTypeParams {
  return {};
}

/** URL → 필터 상태. 허용되지 않은 값은 조용히 기본값으로 떨어진다(구 URL·수기 조작 방어). */
export function searchParamsToFilters(sp: URLSearchParams): TaskFilterValues {
  return {
    q: (sp.get('q') ?? '').slice(0, MAX_SEARCH_KEYWORD_LENGTH),
    // 구 화면과 동일하게 `status` = 워크플로 축이다(배치 축이 아니다).
    workStatus: asUiWorkStatus(sp.get('status')),
    assigneeId: String(asWorkerId(sp.get('assigneeId')) ?? ''),
    eventTypeCd: (sp.get('eventTypeCd') ?? '').trim().slice(0, 20),
  };
}

/** URL → 정렬 모델. 값이 없거나 전부 허용 밖이면 기본 정렬. */
export function searchParamsToSort(sp: URLSearchParams): BoardSortEntry[] {
  const parsed = parseBoardSort(sp.getAll('sort'));
  return parsed.length ? parsed : [...DEFAULT_BOARD_SORT];
}

/**
 * 필터·정렬 상태 → URL. 빈 값은 기록하지 않아 `?q=` 같은 잔재가 남지 않는다.
 * 기본 정렬도 기록하지 않는다(왕복 시 기본값으로 복원되므로 멱등).
 */
export function filtersToSearchParams(
  filters: TaskFilterValues,
  sort: readonly BoardSortEntry[] = DEFAULT_BOARD_SORT,
): Record<string, string | string[]> {
  const sortTokens = toBoardSortParams(sort);
  const isDefaultSort =
    sortTokens.join('|') === toBoardSortParams(DEFAULT_BOARD_SORT).join('|');
  return compactParams<Record<string, string | string[]>>({
    q: filters.q.trim(),
    status: asUiWorkStatus(filters.workStatus),
    assigneeId: filters.assigneeId.trim(),
    eventTypeCd: filters.eventTypeCd.trim(),
    sort: isDefaultSort ? [] : sortTokens,
  }) as Record<string, string | string[]>;
}
