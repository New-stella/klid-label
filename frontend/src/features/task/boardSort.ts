// 작업목록(GET /v1/tasks/board) 정렬 모델 — 순수 함수 모듈.
//
// BE 는 정렬 키 allowlist 밖이거나 정렬 항목이 4개 이상이면 **400** 이다.
// 400 이 사용자에게 도달하기 전에 FE 단계에서 구조적으로 막는 것이 이 모듈의 유일한 책임이다.

/**
 * FE 컬럼 key → BE 정렬 키 매핑 (**단일 상수**).
 *
 * ⚠️ BE allowlist(`SortAllowlist.TASK_BOARD`)와 동기화되어야 한다:
 *   regDt / capturedAt→shtDt / shtDt / rawSn / videoId→rawSn
 * BE 가 allowlist 를 바꾸면 이 상수도 같은 커밋에서 바꾼다. 여기 없는 키는 애초에 전송되지 않는다.
 *
 * ★ `regDt` 는 **기본 정렬 축이자 URL 로만 지정 가능**하다(의도된 상태). BE 응답
 * (`TaskBoardItemResponse`)에 `regDt` 필드가 없어 화면에 등록일 컬럼을 만들 수 없고,
 * 따라서 헤더 토글로 방향을 바꾸거나 되돌릴 수 없다. `?sort=regDt,asc` 로 진입한 사용자의
 * 복구 경로는 **검색폼의 "초기화" 버튼**이다(정렬을 기본값으로 되돌린다).
 */
export const BOARD_SORT_KEY_BY_COLUMN = {
  regDt: 'regDt',
  capturedAt: 'shtDt',
  shtDt: 'shtDt',
  rawSn: 'rawSn',
  videoId: 'rawSn',
} as const;

export type BoardSortColumn = keyof typeof BOARD_SORT_KEY_BY_COLUMN;
export type BoardSortDirection = 'asc' | 'desc';

export interface BoardSortEntry {
  column: BoardSortColumn;
  direction: BoardSortDirection;
}

/** BE 상한과 동일(초과 시 400) — FE 가 스스로 지켜 400 을 만들지 않는다. */
export const MAX_BOARD_SORT_KEYS = 3;

/** 진입 기본 정렬 — 등록일 최신순. FE 가 **명시 전송**한다(BE 기본값에 의존하지 않는다). */
export const DEFAULT_BOARD_SORT: readonly BoardSortEntry[] = [
  { column: 'regDt', direction: 'desc' },
];

function isSortColumn(value: string): value is BoardSortColumn {
  return Object.prototype.hasOwnProperty.call(BOARD_SORT_KEY_BY_COLUMN, value);
}

/**
 * 정렬 키를 추가/갱신한다.
 *
 * - 방금 클릭한 컬럼을 **맨 앞(1순위)** 에 둔다. 뒤에 붙이면 앞선 키가 정렬을 지배해
 *   "헤더를 눌렀는데 순서가 그대로" 인 조용한 무효 클릭이 된다(기본 정렬 regDt 가 항상 앞이므로).
 * - 같은 **서버 키**를 가리키는 기존 항목은 제거한다(중복 전송 방지).
 * - 상한을 넘으면 **가장 오래된 키를 버린다** — 4번째 클릭에서 400 이 나지 않게 한다.
 */
export function applyBoardSort(
  current: readonly BoardSortEntry[],
  column: BoardSortColumn,
  direction: BoardSortDirection,
): BoardSortEntry[] {
  const serverKey = BOARD_SORT_KEY_BY_COLUMN[column];
  const kept = current.filter(
    (e) => BOARD_SORT_KEY_BY_COLUMN[e.column] !== serverKey,
  );
  const next = [{ column, direction }, ...kept];
  return next.slice(0, MAX_BOARD_SORT_KEYS);
}

/**
 * 현재 정렬에서 해당 컬럼의 방향을 찾는다(없으면 `undefined`).
 *
 * ★ 비교는 **서버 키 기준**이다 — URL 왕복 뒤에는 `videoId` 가 `rawSn` 으로 돌아오므로
 * 컬럼 이름으로 비교하면 새로고침 후 헤더의 정렬 표시(aria-sort)가 사라진다.
 */
export function sortDirectionOf(
  entries: readonly BoardSortEntry[],
  column: BoardSortColumn,
): BoardSortDirection | undefined {
  const serverKey = BOARD_SORT_KEY_BY_COLUMN[column];
  return entries.find((e) => BOARD_SORT_KEY_BY_COLUMN[e.column] === serverKey)
    ?.direction;
}

/**
 * 헤더 클릭 1회에 대응하는 다음 정렬 모델.
 * 첫 클릭은 내림차순(최신·큰 값 우선), 같은 컬럼 재클릭은 오름차순으로 토글한다.
 */
export function toggleBoardSort(
  current: readonly BoardSortEntry[],
  column: BoardSortColumn,
): BoardSortEntry[] {
  const direction: BoardSortDirection =
    sortDirectionOf(current, column) === 'desc' ? 'asc' : 'desc';
  return applyBoardSort(current, column, direction);
}

/**
 * 외부 입력(URL 등) → 정렬 모델. allowlist 밖 키·잘못된 형식은 **조용히 제거**한다.
 * 사용자가 URL 을 손대도 BE 400 이 되지 않아야 한다.
 *
 * 입력 **순서를 그대로 보존**한다 — `applyBoardSort` 로 누적하면 앞뒤가 뒤집혀
 * URL 왕복에서 1순위 키가 바뀐다.
 */
export function parseBoardSort(raw: readonly string[]): BoardSortEntry[] {
  const seen = new Set<string>();
  const entries: BoardSortEntry[] = [];
  raw.forEach((token) => {
    const [column, dir] = token.split(',');
    if (!column || !isSortColumn(column)) return;
    const serverKey = BOARD_SORT_KEY_BY_COLUMN[column];
    if (seen.has(serverKey)) return;
    seen.add(serverKey);
    entries.push({ column, direction: dir === 'asc' ? 'asc' : 'desc' });
  });
  return entries.slice(0, MAX_BOARD_SORT_KEYS);
}

/**
 * 정렬 모델 → 요청 파라미터(`["regDt,desc", ...]`).
 * 서버 키 중복 제거 + 상한 클램프를 한 번 더 적용한다(호출부 실수에 대한 최종 방어).
 */
export function toBoardSortParams(entries: readonly BoardSortEntry[]): string[] {
  const seen = new Set<string>();
  const out: string[] = [];
  entries.forEach((e) => {
    const serverKey = BOARD_SORT_KEY_BY_COLUMN[e.column];
    if (!serverKey || seen.has(serverKey)) return;
    seen.add(serverKey);
    out.push(`${serverKey},${e.direction}`);
  });
  return out.slice(0, MAX_BOARD_SORT_KEYS);
}
