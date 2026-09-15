import { useCallback, useEffect, useMemo, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { ArrowUpDown, ChevronDown, ChevronRight, ChevronUp, RefreshCw } from 'lucide-react';
import type { ColumnDef } from '@tanstack/react-table';

import { Button } from '@/components/common/Button';
import { Checkbox } from '@/components/common/Checkbox';
import { DataTable, DataTableSkeleton } from '@/components/common/DataTable';
import { ErrorState } from '@/components/common/ErrorState';
import { EventTypeBadge } from '@/components/common/EventTypeBadge';
import { PageHeader } from '@/components/common/PageHeader';
import { Pagination } from '@/components/common/Pagination';
import { StatusBadge } from '@/components/common/StatusBadge';
import { BulkApproveBar } from '@/features/review/components/BulkApproveBar';
import { BulkApproveConfirmModal } from '@/features/review/components/BulkApproveConfirmModal';
import { BulkApproveResultModal } from '@/features/review/components/BulkApproveResultModal';
import { ReviewKpiCards } from '@/features/review/components/ReviewKpiCards';
import { ReviewListFilters } from '@/features/review/components/ReviewListFilters';
import { useBatchApproveReviews } from '@/features/review/hooks/useBatchApproveReviews';
import { useReviewList } from '@/features/review/hooks/useReviewList';
import { useReviewSummary } from '@/features/review/hooks/useReviewSummary';
import {
  claimLabel,
  claimViewOf,
  isSelectable,
  numericUserId,
  unselectableReason,
} from '@/features/review/reviewClaim';
import {
  DEFAULT_REVIEW_FILTERS,
  DEFAULT_REVIEW_SORT,
  REVIEW_STATUS_LABEL,
  buildReviewListParams,
  buildReviewSummaryParams,
  searchParamsToFilters,
  searchParamsToPage,
  searchParamsToSize,
  searchParamsToSort,
  toReviewSearchParams,
  type ReviewFilterValues,
  type ReviewSortDirection,
  type ReviewSortEntry,
} from '@/features/review/reviewListParams';
import type { BatchApproveResponse, Review, ReviewStatus } from '@/features/review/types';
import { extractBeMessage } from '@/lib/api/extractBeMessage';
import { Role } from '@/lib/api/types';
import { roleSatisfies } from '@/lib/authz';
import { cn } from '@/lib/cn';
import { KRDS_FOCUS } from '@/lib/focusRing';
import { ROLE_LABEL } from '@/lib/roleDisplay';
import { useAuthStore } from '@/stores/useAuthStore';
import { useUiStore } from '@/stores/useUiStore';

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
 * - 점유 표시(「검수 중」)·최근 승인자·체크박스 + 일괄 검수완료 — [@design ADR-067]
 * - DataTable 컬럼: 작업자/제출일/라벨 수 + [검수시작 >] / [이어서 검수] / [결과보기 >]
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
  const isReviewer = roleSatisfies(claims?.role, Role.REVIEWER);

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
  // 그대로 두면 표는 "항목이 없습니다" 인데 바로 아래 페이저는 있지도 않은 페이지를 현재 페이지로
  // 가리키는 자기모순 화면이 되고, 이동 버튼이 모두 비활성이라 복구 경로도 없다.
  // 수기 URL 없이도 도달한다(보던 중 총건수 감소).
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
    refetch: refetchSummary,
  } = useReviewSummary(summaryParams, { enabled: isReviewer });

  /**
   * 헤더 새로고침 — 목록과 KPI 를 **함께** 다시 읽는다.
   * 목록만 갱신하면 표는 새 데이터인데 카드는 옛 집계라 두 값이 어긋난 화면이 된다.
   */
  const handleRefresh = useCallback(() => {
    void refetch();
    void refetchSummary();
  }, [refetch, refetchSummary]);

  // ★ 서버가 이미 거른 결과를 그대로 그린다 — 클라이언트 재필터 금지.
  const rows = useMemo(() => data?.content ?? [], [data]);

  // ── 일괄 검수완료 ────────────────────────────────────────────────
  //
  // 고를 수 있는 것은 **내가 잡고 있는 영상뿐**이며 그 판정은 서버가 행마다 내려준
  // `bulkApprovable` 하나로 한다(`reviewClaim.isSelectable`). 화면이 점유·상태로 다시 계산하지
  // 않는다 — 자격의 상태 축이 재검수 건(승인 상태 그대로)까지 포함해 화면 값만으로는 재현되지
  // 않고, 재현하려 들면 두 번째 진실원이 생긴다.
  const pushToast = useUiStore((s) => s.pushToast);
  const myUserId = useMemo(() => numericUserId(claims?.sub), [claims?.sub]);

  const [selectedVideoIds, setSelectedVideoIds] = useState<Set<number>>(new Set());
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [batchResult, setBatchResult] = useState<BatchApproveResponse | null>(null);

  /**
   * 실제로 보낼 대상 — **지금 화면에 있고 고를 수 있는 행**과의 교집합이다.
   *
   * ★상태만 믿고 보내지 않는 이유: 승인 뒤 재조회로 행이 목록에서 빠지거나 남이 먼저 집어가
   * `bulkApprovable` 이 꺼질 수 있는데, 그때 옛 선택이 남아 있으면 **화면에 보이지 않는 영상을
   * 보내게 된다**(무엇을 보내는지 확인할 수 없는 상태 — `SCREEN-018`).
   */
  const selectedRows = useMemo(
    () => rows.filter((r) => isSelectable(r) && selectedVideoIds.has(r.videoId)),
    [rows, selectedVideoIds],
  );

  /** 이번 페이지에서 고를 수 있는 행 — 머리행 전체선택의 대상 집합. */
  const selectableRows = useMemo(() => rows.filter(isSelectable), [rows]);

  const allSelectableChecked =
    selectableRows.length > 0 && selectedRows.length === selectableRows.length;
  const someSelectableChecked = selectedRows.length > 0 && !allSelectableChecked;

  // 조회 조건·페이지가 바뀌면 고른 것을 푼다(SCREEN-018) — 보이지 않는 행이 대상에 남지 않게.
  useEffect(() => {
    setSelectedVideoIds(new Set());
  }, [filters.q, filters.status, sort.column, sort.direction, page]);

  const toggleRow = useCallback((videoId: number) => {
    setSelectedVideoIds((prev) => {
      const next = new Set(prev);
      if (next.has(videoId)) next.delete(videoId);
      else next.add(videoId);
      return next;
    });
  }, []);

  /** 머리행 전체선택 — **고를 수 있는 행만** 고르고 고를 수 없는 행은 건너뛴다. */
  const toggleAllSelectable = useCallback(() => {
    setSelectedVideoIds((prev) => {
      const selectableIds = selectableRows.map((r) => r.videoId);
      const everySelected =
        selectableIds.length > 0 && selectableIds.every((id) => prev.has(id));
      if (everySelected) return new Set();
      return new Set(selectableIds);
    });
  }, [selectableRows]);

  const clearSelection = useCallback(() => setSelectedVideoIds(new Set()), []);

  const { mutate: runBatchApprove, isPending: batchPending } = useBatchApproveReviews({
    onSuccess: (res) => {
      setConfirmOpen(false);
      // ★전건 실패여도 결과 창으로 알린다 — 요청 자체는 받아들여졌다(API-250).
      setBatchResult(res);
    },
    onError: (err) => {
      // 여기로 오는 것은 요청 **자체**가 거부된 경우다(빈 목록·상한 초과 400, 인증·인가).
      setConfirmOpen(false);
      pushToast({
        variant: 'error',
        message: extractBeMessage(err, '일괄 검수완료 요청을 보내지 못했습니다.'),
      });
    },
  });

  const handleConfirmBatchApprove = useCallback(() => {
    if (selectedRows.length === 0) return;
    runBatchApprove({ videoIds: selectedRows.map((r) => r.videoId) });
  }, [runBatchApprove, selectedRows]);

  /** 결과 창을 닫는다 — 처리된 건의 상태·점유 표시를 새로 받는다. */
  const handleCloseBatchResult = useCallback(() => {
    setBatchResult(null);
    clearSelection();
    void refetch();
    void refetchSummary();
  }, [clearSelection, refetch, refetchSummary]);

  /** 실패한 건만 다시 고른 상태로 목록에 돌아간다. */
  const handleRetryFailed = useCallback(
    (videoIds: number[]) => {
      setBatchResult(null);
      setSelectedVideoIds(new Set(videoIds));
      void refetch();
      void refetchSummary();
    },
    [refetch, refetchSummary],
  );

  /** 결과 창이 식별자만 받으므로 이름은 현재 목록에서 잇는다. */
  const videoNameById = useMemo(() => {
    const map: Record<number, string> = {};
    rows.forEach((r) => {
      map[r.videoId] = r.cctvName;
    });
    return map;
  }, [rows]);

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

  /** 제출일 헤더 클릭 — 서버 정렬 축(제출일 단일)의 방향 토글. 클릭은 항상 0페이지로 리셋. */
  const handleSubmittedAtSortClick = useCallback(() => {
    const nextDirection: ReviewSortDirection =
      sort.column === 'submittedAt' && sort.direction === 'asc' ? 'desc' : 'asc';
    writeSearchParams({ sort: { column: 'submittedAt', direction: nextDirection } });
  }, [sort, writeSearchParams]);

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
   * 진행 방향 표식(셰브론 아이콘)은 장식이라 `aria-hidden` 으로 분리해 이름에서 제외한다.
   */
  const actionLabel = (r: Review) => {
    if (r.status === 'REVIEW_PENDING') return '검수시작';
    if (r.status === 'REVIEWING') return '이어서 검수';
    // ★재검수 건을 「결과보기」로 두지 않는다(SCREEN-018). 그 영상도 **검수 시작을 눌러야**
    //   그 사람이 영상을 잡고 일괄 검수완료 대상에 담을 수 있는데, 결과보기로 두면 그 길이
    //   화면에 드러나지 않는다(재검수 건은 승인 상태 그대로라 상태 배지로는 구분되지 않는다).
    if (r.status === 'COMPLETED' && r.needsRecheck) return '재검수 시작';
    return '결과보기';
  };

  const actionVariant = (r: Review): 'primary' | 'secondary' => {
    if (r.status === 'REVIEW_PENDING') return 'primary';
    // 재검수도 「해야 할 일」이라 검수 시작과 같은 무게로 둔다.
    if (r.status === 'COMPLETED' && r.needsRecheck) return 'primary';
    // 검수중 / 완료(승인·반려) 모두 secondary로 통일 — 동일 위치의 다른 버튼과 시각 일관성 확보
    return 'secondary';
  };

  // columns: navigate closure 캡처 — eventName 은 row(r) 에서 직접 사용하므로 deps 불필요
  //
  // ★ 서버 정렬(제출일)은 이 화면이 헤더 UI·정렬 상태를 직접 구성해 서버 정렬 콜백에 연결한다
  // (UI-007: sortable prop 은 로컬 정렬 전용이라 서버 정렬 화면에서는 켜지 않는다). 미등록 정렬
  // 키는 BE 가 400 이 아니라 **조용히 기본 정렬로 폴백**하므로, allowlist(submittedAt) 밖 컬럼에
  // 정렬 헤더를 두면 URL 만 바뀌고 순서는 그대로인 무효 클릭이 된다.
  // status 는 BE allowlist 에는 있어도 **사용자에게 의미 없는 축이라 정렬 헤더를 두지 않는다** — 아래 참조.
  const columns = useMemo<ColumnDef<Review, unknown>[]>(
    () => [
      {
        id: 'select',
        // 머리행 전체선택 — 이번 페이지에서 **고를 수 있는 행만** 고르고 나머지는 건너뛴다.
        // 일부만 골라져 있으면 중간 상태(`aria-checked="mixed"`)로 보인다.
        header: () => (
          <Checkbox
            aria-label="현재 페이지 전체 선택"
            checked={
              allSelectableChecked ? true : someSelectableChecked ? 'indeterminate' : false
            }
            disabled={selectableRows.length === 0}
            onCheckedChange={toggleAllSelectable}
            data-testid="review-select-all"
          />
        ),
        cell: ({ row }) => {
          const r = row.original;
          const selectable = isSelectable(r);
          const reason = unselectableReason(r, myUserId);
          return (
            <div className="flex min-w-[120px] items-center gap-2">
              <Checkbox
                aria-label={`${r.cctvName} 선택`}
                checked={selectedVideoIds.has(r.videoId) && selectable}
                disabled={!selectable}
                onCheckedChange={() => toggleRow(r.videoId)}
                data-testid={`review-select-${r.videoId}`}
              />
              {/* 체크칸만 꺼 두면 왜 안 되는지 알 길이 없다 — 사유를 함께 세운다(SCREEN-018). */}
              {reason && <span className="text-caption text-gray-500">{reason}</span>}
            </div>
          );
        },
      },
      {
        id: 'cctvName',
        header: '영상명',
        cell: ({ row }) => (
          <div className="min-w-[160px]">
            <p className="truncate max-w-[200px] text-body-md font-medium text-gray-800">
              {row.original.cctvName}
            </p>
            <p className="text-caption text-gray-400">{`video-${String(row.original.videoId).padStart(4, '0')}`}</p>
          </div>
        ),
      },
      {
        id: 'eventName',
        header: '이벤트',
        // 빈 값 표기(`-`)를 쓰는 칸이 이 행에 셋(이벤트·검수 중·최근 승인)이라 행 단위로는
        // 서로 구분되지 않는다 — 어느 칸의 폴백인지 집을 수 있게 식별자를 단다.
        cell: ({ row }) =>
          row.original.eventName ? (
            <EventTypeBadge eventType={row.original.eventName} />
          ) : (
            <span
              className="text-caption text-gray-400"
              data-testid={`review-event-${row.original.videoId}`}
            >
              -
            </span>
          ),
      },
      { id: 'workerName', header: '작업자', cell: ({ row }) => row.original.workerName },
      {
        id: 'submittedAt',
        // BE allowlist: submittedAt → UPD_DT. 헤더 자체가 정렬 버튼을 구성해 서버 정렬 콜백에 연결한다.
        header: () => {
          const isSorted = sort.column === 'submittedAt';
          const direction = isSorted ? sort.direction : undefined;
          return (
            <button
              type="button"
              onClick={handleSubmittedAtSortClick}
              className={cn('inline-flex items-center gap-1 hover:text-primary-600', KRDS_FOCUS)}
            >
              <span>제출일</span>
              {direction === 'asc' ? (
                <ChevronUp className="h-3 w-3" aria-hidden />
              ) : direction === 'desc' ? (
                <ChevronDown className="h-3 w-3" aria-hidden />
              ) : (
                <ArrowUpDown className="h-3 w-3 opacity-40" aria-hidden />
              )}
            </button>
          );
        },
        cell: ({ row }) => new Date(row.original.submittedAt).toLocaleString('ko-KR'),
        meta: {
          ariaSort:
            sort.column === 'submittedAt'
              ? sort.direction === 'asc'
                ? 'ascending'
                : 'descending'
              : 'none',
        },
      },
      {
        id: 'labelCount',
        header: '라벨 수',
        cell: ({ row }) => row.original.labelCount.toLocaleString('ko-KR'),
        meta: { align: 'right' },
      },
      {
        id: 'status',
        header: '상태',
        // ★ 정렬 헤더를 두지 않는다(BE allowlist 에는 있지만 **사용자에게 의미가 없다**).
        // 진입 기본 화면은 status 가 한 종류(검수요청)로 수렴해 1차 정렬이 통째로 무효가 되고,
        // BE tie-break(영상 ID 역순)가 실질 정렬이 되어 R4/AC-5 의 FIFO(제출일 오래된 순)가
        // 조용히 뒤집힌다. 전체 상태 뷰에서도 정렬축이 BE 코드 사전순이라 화면 라벨
        // (승인/검수중/검수요청/반려)과 무관한 순서가 나온다. 정렬은 제출일 축만 노출한다.
        cell: ({ row }) => (
          <div className="flex items-center gap-1.5">
            <StatusBadge status={row.original.status} />
            {/* 재검토 필요 표시 — 상태 배지와 나란히 병기(필터·정렬 축은 아니다). */}
            {row.original.needsRecheck && <StatusBadge status="NEEDS_RECHECK" />}
          </div>
        ),
      },
      {
        id: 'reviewing',
        header: '검수 중',
        // ★표시 전용이다 — 이 축으로 목록을 거르거나 정렬 헤더를 두지 않는다(SCREEN-018).
        //   검수 목록은 대기 **전체**를 보여주며 「내가 검수 중인 것만 보기」를 만들지 않는다.
        cell: ({ row }) => {
          const label = claimLabel(claimViewOf(row.original, myUserId));
          // 아무도 잡지 않았거나 유예가 지나 풀렸으면 칸을 비운다(만료 판정은 서버가 한다).
          if (!label) return <span className="text-caption text-gray-400">-</span>;
          return (
            <span
              className="whitespace-nowrap text-caption text-gray-700"
              data-testid={`review-claim-${row.original.videoId}`}
            >
              {label}
            </span>
          );
        },
      },
      {
        id: 'lastApproval',
        header: '최근 승인',
        // ★역할은 **승인한 그 시점에 기록된 값**이라 그 사람의 지금 역할과 다를 수 있고 그것이
        //   의도다. 역할만 비어 있는 옛 기록은 **빈 괄호를 남기지 않고** 이름과 시각만 보인다.
        cell: ({ row }) => {
          const r = row.original;
          if (!r.lastApprovedAt && !r.lastApproverName) {
            return <span className="text-caption text-gray-400">-</span>;
          }
          const name = r.lastApproverName?.trim();
          const role = r.lastApproverRole?.trim();
          // 표시명은 역할 표시 축의 **단일 진실원**을 쓴다 — 표를 복제하면 화면마다 갈린다.
          const who = name ? (role ? `${name}(${ROLE_LABEL[role] ?? role})` : name) : null;
          const at = r.lastApprovedAt
            ? new Date(r.lastApprovedAt).toLocaleString('ko-KR')
            : null;
          return (
            <span
              className="whitespace-nowrap text-caption text-gray-700"
              data-testid={`review-last-approval-${r.videoId}`}
            >
              {[who, at].filter(Boolean).join(' · ')}
            </span>
          );
        },
      },
      {
        id: 'actions',
        header: '액션',
        cell: ({ row }) => {
          const r = row.original;
          return (
            <Button
              variant={actionVariant(r)}
              size="sm"
              onClick={() => navigate(`/review/${r.id}`)}
              aria-label={`${actionLabel(r)} ${r.cctvName}`}
            >
              {actionLabel(r)}
              {/* 간격은 Button 의 flex gap 이 준다 — 공백 문자를 넣지 않는다.
                  진행 방향 표식은 장식이라 aria-hidden(버튼 이름은 위 aria-label 이 정한다). */}
              {r.status !== 'REVIEWING' && <ChevronRight className="h-3.5 w-3.5" aria-hidden />}
            </Button>
          );
        },
      },
    ],
    [
      allSelectableChecked,
      handleSubmittedAtSortClick,
      myUserId,
      navigate,
      selectableRows.length,
      selectedVideoIds,
      someSelectableChecked,
      sort.column,
      sort.direction,
      toggleAllSelectable,
      toggleRow,
    ],
  );

  return (
    <section className="flex flex-col gap-4" data-testid="review-list-page">
      <PageHeader
        title="검수 목록"
        description="작업자가 제출한 라벨링 결과를 검수합니다. 기본 화면은 검수요청 건을 제출일 오래된 순으로 보여줍니다."
        // 헤더 우측 새로고침 — 정상 상태에서 수동 재조회 수단이 전무했다(사양 SCREEN-018 '헤더(제목·새로고침)').
        // 목록·KPI 를 함께 다시 읽는다. PageHeader 의 기존 `actions` 슬롯을 쓰므로 공통 컴포넌트 변경은 없다.
        actions={
          <Button variant="secondary" size="sm" onClick={handleRefresh}>
            <RefreshCw className="h-3.5 w-3.5" aria-hidden />
            새로고침
          </Button>
        }
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
        rows=[] · totalPages=0 이 된다. 그대로 표를 그리면 에러 배너 옆에서 "항목이 없습니다"
        (=없다고 단정) + 빈 페이저가 함께 떠, 조회 실패가 "대상 0건" 으로 오독된다.
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
          {/* 일괄 검수완료 실행줄 — 표 바로 위. 한 건도 고르지 않았으면 그려지지 않는다. */}
          <BulkApproveBar
            selectedCount={selectedRows.length}
            limit={data?.bulkApproveLimit}
            onRequestApprove={() => setConfirmOpen(true)}
            onClearSelection={clearSelection}
            isPending={batchPending}
          />
          {isRefreshing && (
            <p
              data-testid="review-refreshing"
              role="status"
              className="text-sub text-gray-500"
            >
              갱신 중… (아래 목록은 이전 조건의 결과입니다)
            </p>
          )}
          {/* 로딩 중에는 DataTable 을 렌더하지 않는다 — 표 영역 전체를 스켈레톤으로 대체한다(UI-007). */}
          {isLoading ? (
            <DataTableSkeleton columnCount={columns.length} />
          ) : (
            <DataTable<Review>
              columns={columns}
              data={rows}
              getRowId={(r) => String(r.id)}
              // 0건이 "전체 중 0건" 인지 "지금 건 필터 안에서 0건" 인지 구분해 말한다.
              emptyMessage={
                filters.status === ''
                  ? '검수 항목이 없습니다'
                  : `${REVIEW_STATUS_LABEL[filters.status]} 항목이 없습니다`
              }
            />
          )}
          {/* 페이지네이션은 DataTable 아래에 호출부가 별도로 이어붙인다(UI-007). */}
          <Pagination
            page={page}
            totalPages={data?.totalPages ?? 0}
            onChange={handlePageChange}
          />
        </div>
      )}

      {/* 되돌릴 수 없는 처리라 곧바로 보내지 않고 대상 목록을 보여주는 확인 창을 거친다. */}
      <BulkApproveConfirmModal
        open={confirmOpen}
        targets={selectedRows.map((r) => ({ videoId: r.videoId, cctvName: r.cctvName }))}
        onConfirm={handleConfirmBatchApprove}
        onCancel={() => setConfirmOpen(false)}
        isPending={batchPending}
      />

      {/* 부분 실패를 허용하는 창구라 성공·실패를 함께 보인다 — 전건 실패도 오류 화면이 아니다. */}
      <BulkApproveResultModal
        open={batchResult !== null}
        result={batchResult}
        videoNameById={videoNameById}
        onRetryFailed={handleRetryFailed}
        onClose={handleCloseBatchResult}
      />
    </section>
  );
}
