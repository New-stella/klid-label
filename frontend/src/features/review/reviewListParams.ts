// 검수목록(SCR-REVIEW-001) 필터·정렬 상태 ↔ URL ↔ 서버 파라미터 **단일 매핑 지점**.
//
// 작업목록의 `boardParams.ts` 와 같은 역할이지만 **BE 정책이 반대**라 별도 모듈이다:
//   - 작업목록(/v1/tasks/board) : 미등록 정렬 키 → 400 (strict)
//   - 검수목록(/v1/reviews)     : 미등록 정렬 키 → 400 이 아니라 **조용히 기본 정렬 폴백** (lenient)
// 조용한 폴백이라 잘못된 정렬 키는 "눌렀는데 순서가 안 바뀐다" 로만 드러난다. 그래서 FE 가
// allowlist 를 스스로 지키는 것이 유일한 방어다(아래 REVIEW_SORT_COLUMNS).
//
//   로컬 필드   URL 키    API 파라미터
//   ---------- --------- ---------------------------------------------
//   q          q         q       (BE @Size max 100)
//   status     status    status  (★ FE 코드로 담고 api.ts 가 BE 코드로 역매핑)
//   (정렬)      sort      sort    ("{key},{dir}" — allowlist 밖이면 기본 정렬로 정규화)
//   page/size  page/size page/size
//
// ★ 상태 축의 단일 진실원은 **URL** 이다. 로컬 state 로 들고 있으면 새로고침·북마크·뒤로가기에서
// 진입 기본값(검수요청·오래된순)이 통째로 날아가고 "필터 없는 전체 목록" 으로 되돌아간다.

import { compactParams } from '@/lib/compactParams';

import type {
  ReviewListParams,
  ReviewStatus,
  ReviewSummaryParams,
} from './types';

/** BE `@Size(max = 100)` 정합 — 초과분은 400 왕복 대신 FE 에서 자른다. */
export const MAX_SEARCH_KEYWORD_LENGTH = 100;

/**
 * "전체" 를 뜻하는 URL 표기.
 *
 * 키를 지우는 방식은 쓸 수 없다 — 그러면 "아직 진입 기본값을 심지 않은 상태(키 없음)" 와
 * "사용자가 전체를 골랐다" 를 구분할 수 없어, 전체를 고른 뒤 새로고침하면 다시 검수요청으로
 * 되돌아간다.
 */
export const REVIEW_STATUS_ALL = 'ALL';

/** 화면(select·KPI 카드)이 다루는 상태 값 — BE 4종과 1:1 대응하는 FE 코드. */
export const UI_REVIEW_STATUSES: readonly ReviewStatus[] = [
  'REVIEW_PENDING',
  'REVIEWING',
  'COMPLETED',
  'REJECTED',
];

/** 상태 표시 문구 — select·활성 필터 배지·빈 목록 안내가 같은 말을 쓰게 한다. */
export const REVIEW_STATUS_LABEL: Record<ReviewStatus, string> = {
  REVIEW_PENDING: '검수요청',
  REVIEWING: '검수중',
  COMPLETED: '승인',
  REJECTED: '반려',
};

/**
 * **BE 정렬 allowlist 대응 컬럼**(`SortAllowlist.REVIEW`: submittedAt|updDt→updDt,
 * videoId→rawDataId, status→dataSttsCd).
 *
 * ⚠ 이 목록 밖의 컬럼에 `sortable` 을 붙이면 URL 만 바뀌고 목록 순서는 그대로다(BE 가 조용히
 * 기본 정렬로 폴백하므로 에러도 안 난다). 컬럼을 늘릴 때는 BE allowlist 를 먼저 확인한다.
 *
 * ⚠ 반대로 **여기 있다고 헤더에 노출해도 되는 것은 아니다**. `status` 는 BE 가 받지만 화면에는
 * 정렬 버튼을 두지 않는다 — 진입 기본 화면은 status 가 한 종류로 수렴해 1차 정렬이 무효가 되고
 * BE tie-break(영상 ID 역순)가 실질 정렬이 되어 FIFO(제출일 오래된 순)가 조용히 뒤집힌다.
 * (상세 사유는 `ReviewListPage` 의 status 컬럼 정의 주석)
 */
export const REVIEW_SORT_COLUMNS = ['submittedAt', 'videoId', 'status'] as const;
export type ReviewSortColumn = (typeof REVIEW_SORT_COLUMNS)[number];
export type ReviewSortDirection = 'asc' | 'desc';

export interface ReviewSortEntry {
  column: ReviewSortColumn;
  direction: ReviewSortDirection;
}

/**
 * 진입 기본 정렬 — **제출일 오래된 순(FIFO)**. 오래 기다린 검수 요청이 위에 오게 한다.
 * FE 가 **명시 전송**한다(BE 기본값은 최신순이라 의존하면 정반대가 된다).
 */
export const DEFAULT_REVIEW_SORT: ReviewSortEntry = {
  column: 'submittedAt',
  direction: 'asc',
};

/** 검수목록 필터 상태. `status: ''` 는 전체(필터 미적용). */
export interface ReviewFilterValues {
  q: string;
  status: '' | ReviewStatus;
  /**
   * **제외분만 보기** — BE `excludedOnly`. [@design SCREEN-018] [@design ADR-069]
   *
   * ★<b>`status` 와 성질이 다르다</b> — 그쪽은 「무엇을 거를까」이고 이것은 <b>「어느 쪽을
   * 볼까」</b>(가시 범위)다.
   *
   * ⚠ 켜지면 {@link buildReviewListParams} 가 **검수 상태 축을 빼고** 요청한다 — 「제외됨 건수」를
   * 주는 집계 창구가 그 축을 반영하지 않고 세기 때문이다. 상태로 좁힌 채 그 숫자를 누르면
   * 전환 결과가 누른 숫자보다 적어진다.
   */
  excludedOnly: boolean;
}

/**
 * 진입 기본 필터 — **검수요청만**. "초기화" 도 전체가 아니라 이 값으로 되돌린다.
 *
 * ⚠ `excludedOnly` 기본값은 **거짓**이다 — 기본 목록이 종전과 같은 집합이어야 한다.
 */
export const DEFAULT_REVIEW_FILTERS: ReviewFilterValues = {
  q: '',
  status: 'REVIEW_PENDING',
  excludedOnly: false,
};

export function isReviewSortColumn(value: string): value is ReviewSortColumn {
  return (REVIEW_SORT_COLUMNS as readonly string[]).includes(value);
}

function isUiReviewStatus(value: string): value is ReviewStatus {
  return (UI_REVIEW_STATUSES as readonly string[]).includes(value);
}

/**
 * URL/입력 → 상태 필터.
 *
 * - 키 없음      → 진입 기본값(검수요청) — 첫 진입·구 북마크가 기본 동선을 타게 한다.
 * - `ALL`       → 전체(필터 미적용)
 * - allowlist 밖 → 진입 기본값 (수기 조작·구 URL 방어)
 */
export function asUiReviewStatus(
  raw: string | null | undefined,
): '' | ReviewStatus {
  const value = (raw ?? '').trim();
  if (!value) return DEFAULT_REVIEW_FILTERS.status;
  if (value === REVIEW_STATUS_ALL) return '';
  return isUiReviewStatus(value) ? value : DEFAULT_REVIEW_FILTERS.status;
}

/** 정렬 문자열 → 정렬 모델. allowlist 밖·형식 오류는 **기본 정렬로 정규화**한다. */
export function parseReviewSort(raw: string | null | undefined): ReviewSortEntry {
  const [column, dir] = (raw ?? '').trim().split(',');
  if (!column || !isReviewSortColumn(column)) return { ...DEFAULT_REVIEW_SORT };
  return { column, direction: dir === 'desc' ? 'desc' : 'asc' };
}

/** 정렬 모델 → 요청/URL 파라미터(`"submittedAt,asc"`). */
export function toReviewSortParam(entry: ReviewSortEntry): string {
  return `${entry.column},${entry.direction}`;
}

function asKeyword(raw: string): string | undefined {
  const value = raw.trim();
  return value ? value.slice(0, MAX_SEARCH_KEYWORD_LENGTH) : undefined;
}

interface ReviewPageOptions {
  page: number;
  size: number;
}

/**
 * 목록 요청 파라미터. 빈 값은 `compactParams` 가 키째 제거한다(`status=` 같은 잔재 금지).
 * `status` 는 FE 코드 그대로 담고, BE 코드 역매핑은 `api.ts` 가 전송 직전에 한 번만 한다.
 */
export function buildReviewListParams(
  filters: ReviewFilterValues,
  sort: ReviewSortEntry,
  { page, size }: ReviewPageOptions,
): ReviewListParams {
  return compactParams<ReviewListParams>({
    // ★★제외분만 보는 동안에는 **검수 상태 축을 보내지 않는다** — 구조적 보장이다.
    //   「제외됨 N건」을 주는 집계 창구(`countExcluded`)는 이 축을 **반영하지 않고** 세는데,
    //   목록에만 상태 조건이 남으면 전환 결과가 **누른 숫자보다 적어져** 숫자와 결과가 어긋난다.
    //   ⚠ 화면이 상태 선택을 함께 비우기도 하지만(UX), 그것만으로는 다음 사람이 이 상태에서
    //     상태 값을 세우는 순간 어긋남이 되살아난다 — 그래서 조립 지점에서도 막는다.
    //   ⚠⚠ **세 목록의 규칙이 서로 다르다**: 영상 처리 현황은 어떤 필터도 빼지 않고, 작업 목록은
    //     작업 진행 상태 축을 뺀다. 「일관성」을 이유로 하나로 맞추지 말 것.
    status: filters.excludedOnly ? undefined : filters.status || undefined,
    q: asKeyword(filters.q),
    // 꺼져 있으면 키 자체를 뺀다 — `compactParams` 는 `false` 를 보존하므로 명시적으로 접는다.
    excludedOnly: filters.excludedOnly || undefined,
    page,
    size,
    sort: toReviewSortParam(sort),
  }) as ReviewListParams;
}

/**
 * KPI 집계 요청 파라미터.
 *
 * ★ `status` 를 **넣지 않는다** — 카드 자체가 status 선택지라, 이미 좁혀진 집합 위에서 세면
 * 선택한 카드 하나만 값을 갖는다. 타입({@link ReviewSummaryParams})이 `q` 만 허용해
 * 실수로 넣는 것 자체가 컴파일 에러다.
 */
export function buildReviewSummaryParams(
  filters: ReviewFilterValues,
): ReviewSummaryParams {
  return compactParams<ReviewSummaryParams>({
    q: asKeyword(filters.q),
  }) as ReviewSummaryParams;
}

/** URL → 필터 상태. */
export function searchParamsToFilters(sp: URLSearchParams): ReviewFilterValues {
  return {
    q: (sp.get('q') ?? '').slice(0, MAX_SEARCH_KEYWORD_LENGTH),
    status: asUiReviewStatus(sp.get('status')),
    // ★`'true'` **정확히 그 문자열일 때만** 켠다 — 수기 URL·구 북마크 방어(위 상태 축과 같은 규약).
    excludedOnly: sp.get('excludedOnly') === 'true',
  };
}

/** URL → 정렬 모델. */
export function searchParamsToSort(sp: URLSearchParams): ReviewSortEntry {
  return parseReviewSort(sp.get('sort'));
}

/** URL → 페이지. 음수·비정상 값은 0. */
export function searchParamsToPage(sp: URLSearchParams): number {
  const n = Number(sp.get('page') ?? '0');
  return Number.isSafeInteger(n) && n >= 0 ? n : 0;
}

/** URL → 페이지 크기. BE 상한(100) 밖이면 기본값 20. */
export function searchParamsToSize(sp: URLSearchParams): number {
  const n = Number(sp.get('size') ?? '20');
  return Number.isSafeInteger(n) && n > 0 && n <= 100 ? n : 20;
}

/**
 * 필터·정렬·페이지 → URL 파라미터.
 *
 * ★ 진입 기본값(status/sort)도 **반드시 기록**한다 — 새로고침·북마크·뒤로가기에서 유실되면
 * 사용자가 보던 화면(검수요청·오래된순)이 조용히 다른 집합(전체·최신순)으로 바뀐다.
 * 빈 검색어는 기록하지 않는다(`?q=` 잔재 방지).
 */
export function toReviewSearchParams(
  filters: ReviewFilterValues,
  sort: ReviewSortEntry,
  { page, size }: ReviewPageOptions,
): Record<string, string> {
  return compactParams<Record<string, string>>({
    q: filters.q.trim(),
    status: filters.status === '' ? REVIEW_STATUS_ALL : filters.status,
    // 켜졌을 때만 기록한다 — 빈 문자열은 `compactParams` 가 키째 지운다. 이 화면은 URL 을
    // 정규화 결과와 **키 개수까지** 대조하므로(`isSameSearch`), 꺼진 상태에서 키를 남기면
    // 이 축을 모르던 기존 URL 이 매번 교정 대상이 되어 히스토리가 더러워진다.
    excludedOnly: filters.excludedOnly ? 'true' : '',
    sort: toReviewSortParam(sort),
    page: String(page),
    size: String(size),
  }) as Record<string, string>;
}
