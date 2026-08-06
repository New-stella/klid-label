import { useCallback, useEffect, useMemo } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';

import { Button } from '@/components/common/Button';
import { DataTable, type DataTableColumn } from '@/components/common/DataTable';
import { ErrorState } from '@/components/common/ErrorState';
import { EventTypeBadge } from '@/components/common/EventTypeBadge';
import { PageHeader } from '@/components/common/PageHeader';
import { StatusBadge } from '@/components/common/StatusBadge';
import { ReviewKpiCards } from '@/features/review/components/ReviewKpiCards';
import { ReviewListFilters } from '@/features/review/components/ReviewListFilters';
import { useReviewList } from '@/features/review/hooks/useReviewList';
import { useReviewSummary } from '@/features/review/hooks/useReviewSummary';
import {
  DEFAULT_REVIEW_FILTERS,
  DEFAULT_REVIEW_SORT,
  REVIEW_STATUS_LABEL,
  buildReviewListParams,
  buildReviewSummaryParams,
  parseReviewSort,
  searchParamsToFilters,
  searchParamsToPage,
  searchParamsToSize,
  searchParamsToSort,
  toReviewSearchParams,
  toReviewSortParam,
  type ReviewFilterValues,
  type ReviewSortEntry,
} from '@/features/review/reviewListParams';
import type { Review, ReviewStatus } from '@/features/review/types';
import { Role } from '@/lib/api/types';
import { useAuthStore } from '@/stores/useAuthStore';

/**
 * 현재 URL 이 정규화 결과와 **완전히 같은가** — 키 개수까지 본다.
 * 다르면(값이 틀렸거나 잉여 키가 남았거나) URL 을 교정한다.
 */
function isSameSearch(
  current: URLSearchParams,
  canonical: Record<string, string>,
): boolean {
  const keys = Object.keys(canonical);
  if (Array.from(current.keys()).length !== keys.length) return false;
  return keys.every((key) => current.get(key) === canonical[key]);
}

/**
 * SCR-REVIEW-001 검수 목록.
 *
 * UI/UX §4-9 정합:
 * - KPI 4종 (검수요청 / 검수중 / 승인 / 반려) — **서버 집계 전체 기준** + 클릭 필터(재클릭 해제)
 * - 검색·상태 필터 (영상명·작업자명) — **서버 필터**
 * - DataTable 컬럼: 작업자/제출일/라벨 수 + [검수시작 ▶] / [이어서 검수] / [결과보기 ▶]
 *
 * ★ 필터·정렬 축 (Phase 5):
 * - **진입 기본값 = 검수요청(PENDING) + 제출일 오래된 순(FIFO)** 이며, FE 가 명시 전송하고
 *   **URL 에도 기록**한다. 내부 state 로만 들고 있으면 새로고침·북마크·뒤로가기에서 유실돼
 *   "필터 없는 전체 목록" 으로 조용히 되돌아간다.
 * - 필터의 **단일 진실원은 URL** 이다 — 카드 `selected`, select `value`, 요청 파라미터가 모두
 *   URL 에서 파생된다(로컬 `statusFilter`/`keyword` state 없음).
 * - 화면에서 행을 **다시 거르지 않는다**. 현재 페이지 20건 안에서 거르면 목록·총건수·KPI 가
 *   서로 다른 값을 말하게 된다.
 * - KPI 실패는 목록 표시를 막지 않는다(페이지 레벨 error 는 목록 쿼리만 결정).
 * - **목록 실패 ≠ 0건**: 실패하면 표·건수 대신 에러만 보여준다. 정렬은 **제출일 축만** 노출한다
 *   (상태 축은 기본 화면의 FIFO 를 깨뜨린다). page 가 범위를 벗어나면 범위 안으로 되돌린다.
 *
 * 보안:
 * - 검색어·영상명은 텍스트 노드로만 렌더한다(`dangerouslySetInnerHTML` 미사용 — XSS).
 * - REVIEWER 역할 검증은 라우터 `InternalRoute` + BE `@PreAuthorize` 이중. 신설 집계 쿼리는
 *   `enabled` 로 비 REVIEWER 호출을 막아 403 스팸을 방지한다.
 */
export function ReviewListPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const navigate = useNavigate();
  const claims = useAuthStore((s) => s.claims);
  const isReviewer = claims?.role === Role.REVIEWER;

  // ── URL → 상태 (단일 진실원) ──────────────────────────────────────
  const filters = useMemo<ReviewFilterValues>(
    () => searchParamsToFilters(searchParams),
    [searchParams],
  );
  const sort = useMemo<ReviewSortEntry>(
    () => searchParamsToSort(searchParams),
    [searchParams],
  );
  const page = useMemo(() => searchParamsToPage(searchParams), [searchParams]);
  const size = useMemo(() => searchParamsToSize(searchParams), [searchParams]);

  /** 필터·정렬·페이지 → URL. 필터류 변경은 **항상 page 0** 을 동반한다(단일 헬퍼로 강제). */
  const writeSearchParams = useCallback(
    (next: {
      filters?: ReviewFilterValues;
      sort?: ReviewSortEntry;
      page?: number;
      /** 히스토리에 새 항목을 남기지 않는다(진입 정규화·범위 초과 복귀 등 자동 교정용). */
      replace?: boolean;
    }) => {
      setSearchParams(
        toReviewSearchParams(next.filters ?? filters, next.sort ?? sort, {
          // ★ 페이지를 명시하지 않은 변경(=필터·정렬)은 **항상 0페이지**로 되돌린다.
          // 5페이지를 보다 필터를 바꾸면 그 페이지엔 새 결과가 없어 "결과 없음" 으로 오인된다.
          page: next.page ?? 0,
          size,
        }),
        { replace: next.replace ?? false },
      );
    },
    [filters, setSearchParams, size, sort],
  );

  // URL 을 **정규화 결과와 일치**시킨다 — 히스토리를 더럽히지 않게 replace 로.
  //
  // ① 진입 기본값(검수요청·오래된순)을 심는다. 없으면 새로고침·공유 링크에서 기본 동선이 유실된다.
  // ② ★ 키가 다 있어도 **값이 정규화 결과와 다르면 교정**한다. `?status=BOGUS&sort=labelPayload,asc`
  //    처럼 둘 다 있는 URL 은 요청·표시만 정규화되고 주소는 그대로 남아, 화면과 어긋난 URL 이
  //    공유·북마크된다.
  //
  // 정규화는 멱등이다(정규값을 다시 정규화해도 같은 값) — 같으면 아무것도 하지 않으므로 루프가 없다.
  useEffect(() => {
    const canonical = toReviewSearchParams(filters, sort, { page, size });
    if (isSameSearch(searchParams, canonical)) return;
    setSearchParams(canonical, { replace: true });
  }, [filters, page, searchParams, setSearchParams, size, sort]);

  // ── 서버 조회 ────────────────────────────────────────────────────
  const listParams = useMemo(
    () => buildReviewListParams(filters, sort, { page, size }),
    [filters, page, size, sort],
  );
  const { data, isLoading, isFetching, error, refetch } =
    useReviewList(listParams);

  /**
   * 첫 로딩이 아닌 **재조회 중**인가.
   *
   * `keepPreviousData` 때문에 필터·정렬·페이지 전환 시 `isLoading` 은 false 다 — 그 왕복 동안
   * 표에는 **이전 조건의 행**이 남는데 KPI 선택·활성 필터 배지는 이미 새 조건을 말한다.
   * 그 구간을 사용자가 알아볼 수 있게 표시한다.
   */
  const isRefreshing = isFetching && !isLoading;

  // 총 페이지가 줄어 현재 page 가 범위를 벗어나면 되돌린다(작업목록과 같은 방식).
  // 그대로 두면 표는 "항목이 없습니다" 인데 바로 아래는 "1-9 / 총 9건" 인 자기모순 화면이 되고,
  // 이동 버튼이 모두 비활성이라 복구 경로도 없다. 수기 URL 없이도 도달한다(보던 중 총건수 감소).
  //
  // ★ 응답이 아직 없으면(로딩·실패) 판단하지 않는다 — 첫 로딩에 무조건 0페이지로 튕긴다.
  const totalPages = data?.totalPages;
  useEffect(() => {
    if (totalPages === undefined) return;
    const lastPage = Math.max(0, totalPages - 1);
    // 되돌린 뒤에는 page <= lastPage 라 조건이 다시 성립하지 않는다(루프·깜빡임 없음).
    if (page > lastPage) writeSearchParams({ page: lastPage, replace: true });
  }, [page, totalPages, writeSearchParams]);

  // KPI 집계 — 목록과 **독립 쿼리**. status 는 제외된다(카드 자체가 그 선택지).
  const summaryParams = useMemo(
    () => buildReviewSummaryParams(filters),
    [filters],
  );
  const {
    data: summary,
    isLoading: summaryLoading,
    isError: summaryIsError,
  } = useReviewSummary(summaryParams, { enabled: isReviewer });

  // ★ 서버가 이미 거른 결과를 그대로 그린다 — 클라이언트 재필터 금지.
  const rows = useMemo(() => data?.content ?? [], [data]);

  // ── 핸들러 ───────────────────────────────────────────────────────
  const handleSearchChange = useCallback(
    (q: string) => writeSearchParams({ filters: { ...filters, q } }),
    [filters, writeSearchParams],
  );

  const handleStatusChange = useCallback(
    (status: '' | ReviewStatus) =>
      writeSearchParams({ filters: { ...filters, status } }),
    [filters, writeSearchParams],
  );

  /** KPI 카드 클릭 — 같은 카드 재클릭은 해제(전체). */
  const handleKpiSelect = useCallback(
    (status: ReviewStatus | undefined) => handleStatusChange(status ?? ''),
    [handleStatusChange],
  );

  /** 초기화 — 전체가 아니라 **진입 기본값**(검수요청·오래된순)으로 되돌린다. */
  const handleReset = useCallback(
    () =>
      writeSearchParams({
        filters: { ...DEFAULT_REVIEW_FILTERS },
        sort: { ...DEFAULT_REVIEW_SORT },
      }),
    [writeSearchParams],
  );

  const handleSortChange = useCallback(
    (raw: string) => writeSearchParams({ sort: parseReviewSort(raw) }),
    [writeSearchParams],
  );

  const handlePageChange = useCallback(
    (next: number) => writeSearchParams({ page: next }),
    [writeSearchParams],
  );

  /**
   * 이미 진입 기본값(검수요청·오래된순)인가 — 초기화 버튼 비활성 판정.
   * ★ 정렬도 함께 본다: 정렬만 바꾼 사용자에게 버튼이 잠기면 기본 정렬 복구 경로가 사라진다.
   */
  const isDefaultView =
    filters.q === DEFAULT_REVIEW_FILTERS.q &&
    filters.status === DEFAULT_REVIEW_FILTERS.status &&
    sort.column === DEFAULT_REVIEW_SORT.column &&
    sort.direction === DEFAULT_REVIEW_SORT.direction;

  /**
   * 행 액션 문구.
   *
   * ★ 접근성 이름도 **이 문구를 그대로** 쓴다(WCAG 2.5.3 Label in Name). 눈에는 "결과보기" 인데
   * 스크린리더·음성제어에는 "검수 시작" 으로 읽히면 사용자가 보이는 대로 말해도 버튼이 눌리지 않는다.
   * 진행 방향 표식(▶)은 장식이라 `aria-hidden` 으로 분리해 이름에서 제외한다.
   */
  const actionLabel = (status: ReviewStatus) => {
    if (status === 'REVIEW_PENDING') return '검수시작';
    if (status === 'REVIEWING') return '이어서 검수';
    return '결과보기';
  };

  const actionVariant = (status: ReviewStatus): 'primary' | 'secondary' => {
    if (status === 'REVIEW_PENDING') return 'primary';
    // 검수중 / 완료(승인·반려) 모두 secondary로 통일 — 동일 위치의 다른 버튼과 시각 일관성 확보
    return 'secondary';
  };

  // columns: navigate closure 캡처 — eventName 은 row(r) 에서 직접 사용하므로 deps 불필요
  //
  // ★ `sortable` 은 **BE allowlist 대응 컬럼에만** 붙인다. 이 엔드포인트는 미등록 정렬 키를
  // 400 이 아니라 **조용히 기본 정렬로 폴백**하므로, allowlist 밖 컬럼에 붙이면 URL 만 바뀌고
  // 순서는 그대로인 무효 클릭이 된다.
  // 단 allowlist 에 있어도 **사용자에게 의미 없는 축(status)은 노출하지 않는다** — 아래 참조.
  const columns = useMemo<DataTableColumn<Review>[]>(
    () => [
      {
        key: 'cctvName',
        header: '영상명',
        render: (r) => (
          <div className="min-w-[160px]">
            <p className="truncate max-w-[200px] text-body-md font-medium text-gray-800">
              {r.cctvName}
            </p>
            <p className="text-caption text-gray-400">{`video-${String(r.videoId).padStart(4, '0')}`}</p>
          </div>
        ),
      },
      {
        key: 'eventName',
        header: '이벤트',
        render: (r) =>
          r.eventName ? (
            <EventTypeBadge eventType={r.eventName} />
          ) : (
            <span className="text-caption text-gray-400">-</span>
          ),
      },
      { key: 'workerName', header: '작업자', render: (r) => r.workerName },
      {
        key: 'submittedAt',
        header: '제출일',
        sortable: true, // BE allowlist: submittedAt → UPD_DT
        render: (r) => new Date(r.submittedAt).toLocaleString('ko-KR'),
      },
      {
        key: 'labelCount',
        header: '라벨 수',
        align: 'right',
        render: (r) => r.labelCount.toLocaleString('ko-KR'),
      },
      {
        key: 'status',
        header: '상태',
        // ★ sortable 을 붙이지 않는다(BE allowlist 에는 있지만 **사용자에게 의미가 없다**).
        // 진입 기본 화면은 status 가 한 종류(검수요청)로 수렴해 1차 정렬이 통째로 무효가 되고,
        // BE tie-break(영상 ID 역순)가 실질 정렬이 되어 R4/AC-5 의 FIFO(제출일 오래된 순)가
        // 조용히 뒤집힌다. 전체 상태 뷰에서도 정렬축이 BE 코드 사전순이라 화면 라벨
        // (승인/검수중/검수요청/반려)과 무관한 순서가 나온다. 정렬은 제출일 축만 노출한다.
        render: (r) => <StatusBadge status={r.status} />,
      },
      {
        key: 'actions',
        header: '액션',
        render: (r) => (
          <Button
            variant={actionVariant(r.status)}
            size="sm"
            onClick={() => navigate(`/review/${r.id}`)}
            aria-label={`${actionLabel(r.status)} ${r.cctvName}`}
          >
            {actionLabel(r.status)}
            {/* 간격은 Button 의 flex gap 이 준다 — 공백 문자를 넣지 않는다. */}
            {r.status !== 'REVIEWING' && <span aria-hidden>▶</span>}
          </Button>
        ),
      },
    ],
    [navigate],
  );

  return (
    <section className="flex flex-col gap-4" data-testid="review-list-page">
      <PageHeader
        title="검수 목록"
        description="작업자가 제출한 라벨링 결과를 검수합니다. 기본 화면은 검수요청 건을 제출일 오래된 순으로 보여줍니다."
      />

      {/* KPI 4종 — 서버 집계(전체 기준). 실패해도 목록은 그대로 표시된다. */}
      <ReviewKpiCards
        summary={summary}
        isLoading={summaryLoading}
        isError={summaryIsError}
        selected={filters.status}
        onSelect={handleKpiSelect}
      />

      <ReviewListFilters
        values={filters}
        onSearchChange={handleSearchChange}
        onStatusChange={handleStatusChange}
        onReset={handleReset}
        resetDisabled={isDefaultView}
      />

      {/*
        ★ 목록 실패는 **빈 목록과 구분**해서 말한다.
        `keepPreviousData` 는 이전 쿼리가 성공(pending)일 때만 적용되므로 실패하면 data 가 없어
        rows=[] · totalElements=0 이 된다. 그대로 표를 그리면 에러 배너 옆에서 "항목이 없습니다"
        (=없다고 단정) + "0-0 / 총 0건" 이 함께 떠, 조회 실패가 "대상 0건" 으로 오독된다.
      */}
      {error ? (
        <ErrorState
          title="검수 목록을 불러올 수 없습니다"
          message="목록을 가져오지 못했습니다. 잠시 후 다시 시도해 주세요. (건수·목록은 확인할 수 없습니다)"
          onRetry={() => void refetch()}
        />
      ) : (
        /* 필터·정렬 전환 왕복 동안 표는 **이전 결과**다(keepPreviousData) — 그 사실을 알린다.
           이 표시가 없으면 배지·KPI 선택은 새 필터인데 행은 옛 필터인 화면을 사실로 오인한다. */
        <div aria-busy={isRefreshing} className="flex flex-col gap-2">
          {isRefreshing && (
            <p
              data-testid="review-refreshing"
              role="status"
              className="text-sub text-gray-500"
            >
              갱신 중… (아래 목록은 이전 조건의 결과입니다)
            </p>
          )}
          <DataTable<Review>
            columns={columns}
            rows={rows}
            totalElements={data?.totalElements ?? 0}
            page={page}
            size={size}
            sort={toReviewSortParam(sort)}
            loading={isLoading}
            // 0건이 "전체 중 0건" 인지 "지금 건 필터 안에서 0건" 인지 구분해 말한다.
            emptyMessage={
              filters.status === ''
                ? '검수 항목이 없습니다'
                : `${REVIEW_STATUS_LABEL[filters.status]} 항목이 없습니다`
            }
            rowKey={(r) => r.id}
            onPageChange={handlePageChange}
            onSortChange={handleSortChange}
          />
        </div>
      )}
    </section>
  );
}
