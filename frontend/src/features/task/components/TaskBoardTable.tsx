import { useEffect, useRef, useState, type RefObject } from 'react';
import {
  ArrowUpDown,
  ChevronDown,
  ChevronUp,
  History,
  ListTodo,
  Play,
  RefreshCw,
  UserPlus,
} from 'lucide-react';

import { Button } from '@/components/common/Button';
import { Checkbox } from '@/components/common/Checkbox';
import { EmptyState } from '@/components/common/EmptyState';
import { EventTypeBadge } from '@/components/common/EventTypeBadge';
import { Field, FieldLabel } from '@/components/common/Field';
import { Skeleton } from '@/components/common/Skeleton';
import { StatusBadge, type BadgeStatus } from '@/components/common/StatusBadge';
import { augTypeLabel, type AugType } from '@/features/augment/augTypeLabel';
import { formatDateTime } from '@/features/review/formatDateTime';
import {
  sortDirectionOf,
  type BoardSortColumn,
  type BoardSortEntry,
} from '@/features/task/boardSort';
import { TASK_STATUS_LABEL, type RowStatus } from '@/features/task/statusLabels';
import type { Task } from '@/features/task/types';
import {
  UNASSIGN_BLOCKED_REASON,
  isUnassignBlocked,
} from '@/features/task/unassignEligibility';
import { isMarkingBlocked, type Video } from '@/features/video/types';
import { cn } from '@/lib/cn';
import { KRDS_FOCUS } from '@/lib/focusRing';

// 작업 목록 표(SCREEN-012).
//
// ★검수자 열을 두지 않는다 — 검수는 배정 없이 전체 대기열에서 집어가므로 「이 영상의 검수자」가
// 존재하지 않는다. 「지금 누가 검수 중인가」는 검수 목록의 점유 표시가 보여준다.
//
// [@design SCREEN-012] [@design ADR-067] [@design API-073] [@design UI-084]

/** 작업목록 한 행의 화면 모델 (영상 + optional 배정). */
export interface TaskRow {
  id: string;
  video: Video;
  task: Task | undefined;
  rowStatus: RowStatus;
  videoName: string;
  /** 증강/해상도 파생 데이터 여부 (뱃지 노출 판정). */
  augmented: boolean;
  /** 증강 종류 코드 — null 이면 뱃지에 '증강'만 표시. */
  augType: AugType | null;
}

// AssignmentStatus → BadgeStatus 매핑 (UNASSIGNED는 PENDING으로)
const STATUS_BADGE_MAP: Record<RowStatus, BadgeStatus> = {
  UNASSIGNED: 'PENDING',
  PENDING: 'PENDING',
  IN_PROGRESS: 'IN_PROGRESS',
  REVIEW_PENDING: 'REVIEW_PENDING',
  COMPLETED: 'COMPLETED',
  REJECTED: 'REJECTED',
};

// `whitespace-nowrap` 필수 — 헤더 라벨은 전부 짧은 고정 문구인데 한글은 단어 경계가 없어
// 폭이 좁아지면 글자 단위로 끊긴다("영상 ID" → 2줄). 아래 TABLE_MIN_WIDTH 와 한 세트다.
//
// 좌우 여백은 `px-3`(12px) 이다 — 아래 TABLE_MIN_WIDTH 산정의 전제이므로 헤더/본문 셀이 항상
// 같은 값을 써야 한다. 한쪽만 키우면 계산이 어긋나 실제 표가 최소폭을 넘는다.
//
// 글자색 하한은 `gray-600` 이다 — 헤더 배경이 secondary-50(#EEF2F7)이라 gray-500 은
// 그 위에서 4.01:1 로 AA(4.5:1) 미달이다(gray-600 은 5.60:1).
//
// 크기·굵기는 표 헤더 전용 step `text-table-header`(14px/600) 하나가 정한다.
//  - 구 구현의 `text-label` 은 값이 같지만(14px/1.4/600) 표 헤더의 토큰이 아니라, 표마다
//    다른 step 을 쓰는 것처럼 읽혔다.
//  - `font-semibold` 는 step 이 이미 emit 하는 600 과 중복이라 뺀다 — 굵기를 두 곳에서
//    지정하면 한쪽만 고쳐져 화면마다 굵기가 갈린다(실제로 500/600/700 세 갈래가 났다).
// ⚠ 이 클래스는 반드시 **`<th>` 에 직접** 건다 — `<tr>`/`<thead>` 에만 걸면 상속값이 브라우저
//   UA 기본 `th { font-weight: bold }`(700)에 져서 600 이 적용되지 않는다.
const TH_CLASS =
  'whitespace-nowrap px-3 py-3 text-left text-table-header uppercase tracking-wide text-gray-600';

/** 본문 셀 좌우 여백 — TH_CLASS 의 `px-3` 과 반드시 같은 값. */
const TD_PAD = 'px-3 py-3';

/**
 * 표 최소 폭 — 래퍼의 `overflow-x-auto` 가 **실제로 발동하게** 만드는 값이다.
 *
 * min-width 가 없으면 `table-layout: auto` + `w-full` 이 표를 부모 폭에 억지로 맞추고,
 * 한글은 단어 경계가 없어 셀이 글자 단위로 뭉개진다(1280px 실측: 배지 15×74px, 행 높이 111px).
 *
 * 산정 근거 — 컬럼별 최소 필요 폭(콘텐츠 + `px-3` 좌우 24px) 합:
 * - REVIEWER 8컬럼: 체크박스 40 + 영상명 164(`min-w-[140px]`+24) + 영상 ID 86 +
 *   촬영일시 ~189(`toLocaleString('ko-KR')` 최장) + 이벤트 ~138 + 상태 ~104 +
 *   작업자 ~92 + 액션 ~176(재배정+이력) ≈ **989** → 여유 포함 1020
 *   (검수자 열 ~102 가 `ADR-067` 로 빠져 구 값 1120 에서 그만큼 줄였다. 폭을 그대로 두면
 *    컬럼이 남는 폭만큼 늘어나 열 간 여백이 벌어진다.)
 * - WORKER 6컬럼(체크박스·촬영일시 없음): ≈ **862** → 여유 포함 900
 *   (⚠ 구 표기 「7컬럼」은 검수자 열이 빠지기 전 값이다 — 두 시각 모두 그 열을 잃었는데
 *    REVIEWER 쪽 숫자만 고쳐져 있었다. 아래 레이아웃 시험이 두 값을 컬럼 수와 함께 고정한다.)
 *
 * 상한 제약 ①: FHD(1920×1080, 표 래퍼 clientWidth 1630)에서 가로 스크롤이 새로 생기면 안 된다.
 * 상한 제약 ②(2026-08-08 추가): **1440×900 에서도 가로 스크롤이 없어야 한다** — 그 폭의 표 래퍼
 * clientWidth 는 1150px 이고, 구 값 1200 은 50px 넘쳐 마지막 액션('이력')이 뷰포트 밖(right=1446)에
 * 있었다. 스크롤로 도달은 됐지만 단서가 없어 사실상 보이지 않는 버튼이었다.
 *   → REVIEWER 값은 **1150 이하**를 유지해야 한다. 컬럼을 더 늘리려면 폭을 키우지 말고
 *     기존 컬럼을 줄이거나 컬럼 구성을 재검토할 것.
 * WORKER 값을 REVIEWER 와 같이 키우면 1280px 에서 스크롤이 필요 없는데도 생기므로
 * (래퍼 990 ≥ 960) 역할별로 나눈다.
 */
const TABLE_MIN_WIDTH = {
  reviewer: 'min-w-[1020px]',
  worker: 'min-w-[900px]',
} as const;

/**
 * 표의 컬럼 한 칸.
 *
 * 헤더 마크업이 세 갈래라 그 갈래를 고르는 조건까지 여기 담는다 — 그래야 이 배열 하나로
 * 헤더를 전부 그릴 수 있고, 「구성」과 「개수」가 갈라지지 않는다.
 */
interface BoardColumn {
  key: string;
  /** 헤더 라벨. 선택 열은 라벨 없는 칸이라 화면에 쓰이지 않는다. */
  label: string;
  /** 검수자 시각에서만 서는 열. */
  reviewerOnly?: true;
  /**
   * 서버 정렬 allowlist 와 1:1 대응하는 열 — **검수자 시각에서만** 정렬 헤더가 된다.
   * WORKER 창구(`/v1/assignments`)는 서버 정렬을 지원하지 않아 정적 헤더로 둔다.
   */
  sortColumn?: BoardSortColumn;
  /** 일괄 선택 체크박스 열 — 라벨도 `scope` 도 없는 좁은 칸이라 헤더 마크업이 다르다. */
  select?: true;
}

/**
 * ★**표의 컬럼 구성은 여기 한 곳이 정한다** — 헤더·로딩 스켈레톤 칸 수·빈 목록 안내의
 * `colSpan` 이 **전부 이 배열에서 파생**된다.
 *
 * 구 구현은 헤더를 JSX 로 직접 쓰고 개수를 `isReviewer ? 8 : 7` 로 **따로 적었다.** 그래서
 * 검수자 열을 걷어냈을 때(`ADR-067`) 검수자 쪽 숫자만 고쳐지고 작업자 쪽은 7 로 남아, 실제
 * 헤더가 6인데 로딩 행에 칸이 하나 더 생기고 빈 상태가 한 열을 더 덮었다. **타입 오류도
 * 시험 실패도 나지 않았다** — 그래서 숫자를 고치는 대신 출처를 하나로 합쳤다.
 *
 * ⚠ 열을 더하거나 빼면 {@link TABLE_MIN_WIDTH} 를 **함께 재산정**한다. 폭만 남으면 열 간
 *   여백이 벌어진다(레이아웃 시험이 폭과 컬럼 수를 한 케이스에서 함께 못박는다).
 *
 * ★검수자 열은 두지 않는다(`ADR-067` · `SCREEN-012`) — 검수는 배정 없이 전체 대기열에서
 *   집어가므로 「이 영상의 검수자」라는 값이 존재하지 않는다. 「지금 누가 검수 중인가」는
 *   검수 목록의 점유 표시가 보여준다.
 */
const BOARD_COLUMNS: readonly BoardColumn[] = [
  { key: 'select', label: '선택', reviewerOnly: true, select: true },
  { key: 'videoName', label: '영상명' },
  { key: 'videoId', label: '영상 ID', sortColumn: 'videoId' },
  { key: 'capturedAt', label: '촬영일시', reviewerOnly: true, sortColumn: 'capturedAt' },
  { key: 'eventType', label: '이벤트' },
  { key: 'status', label: '상태' },
  { key: 'worker', label: '작업자' },
  { key: 'actions', label: '액션' },
];

/** 그 시각에서 실제로 서는 컬럼 — 헤더도 개수도 이 결과 하나만 본다. */
export function visibleBoardColumns(isReviewer: boolean): readonly BoardColumn[] {
  return BOARD_COLUMNS.filter((c) => isReviewer || !c.reviewerOnly);
}

function videoCode(videoId: number): string {
  return `video-${String(videoId).padStart(4, '0')}`;
}

/**
 * 대상 요소가 가로로 넘치는지(=가로 스크롤이 실제로 가능한지) 추적한다.
 *
 * 최소폭을 1440 안에 넣어도 그보다 좁은 창·큰 글꼴에서는 여전히 넘친다. 그때 스크롤 단서가
 * 없으면 마지막 컬럼(액션)이 **있는지조차 알 수 없다** — 실측에서 '이력' 버튼이 뷰포트 밖
 * right=1446 에 있었다. 창 크기·데이터 변화 모두에 반응해야 하므로 ResizeObserver 로 본다.
 */
function useHorizontalOverflow<T extends HTMLElement>(ref: RefObject<T | null>, deps: unknown[]) {
  const [overflowing, setOverflowing] = useState(false);
  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    const measure = () => setOverflowing(el.scrollWidth - el.clientWidth > 1);
    measure();
    // jsdom 등 ResizeObserver 미지원 환경에서는 초기 측정만 하고 조용히 넘어간다.
    if (typeof ResizeObserver === 'undefined') return;
    const ro = new ResizeObserver(measure);
    ro.observe(el);
    return () => ro.disconnect();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [ref, ...deps]);
  return overflowing;
}

interface SortableHeaderProps {
  label: string;
  column: BoardSortColumn;
  sort: readonly BoardSortEntry[];
  onSort: (column: BoardSortColumn) => void;
}

/**
 * 정렬 가능한 컬럼 헤더.
 *
 * 접근성: 클릭 요소는 `button`, 정렬 상태는 색/아이콘이 아니라 `aria-sort` 로 전달한다.
 */
function SortableHeader({ label, column, sort, onSort }: SortableHeaderProps) {
  const direction = sortDirectionOf(sort, column);
  const ariaSort =
    direction === 'asc' ? 'ascending' : direction === 'desc' ? 'descending' : 'none';
  // 정렬 방향 표식은 공용 DataTable·검수 목록과 같은 아이콘을 쓴다(오름=ChevronUp / 내림=ChevronDown
  // / 미정렬=ArrowUpDown). 같은 의미에 표에 따라 다른 글리프가 뜨면 사용자가 매번 다시 읽어야 한다.
  const Icon = direction === 'asc' ? ChevronUp : direction === 'desc' ? ChevronDown : ArrowUpDown;
  return (
    <th scope="col" aria-sort={ariaSort} className={TH_CLASS}>
      <button
        type="button"
        onClick={() => onSort(column)}
        className={cn(
          'inline-flex items-center gap-1 rounded uppercase tracking-wide',
          'hover:text-gray-700',
          KRDS_FOCUS,
        )}
      >
        {label}
        <Icon size={12} aria-hidden />
      </button>
    </th>
  );
}

export interface TaskBoardTableProps {
  rows: TaskRow[];
  isReviewer: boolean;
  isLoading: boolean;
  /** 백그라운드 갱신 중 (이전 결과를 유지하므로 정지 화면이 최신으로 보인다). */
  refreshing: boolean;
  totalElements: number;
  totalPages: number;
  /** 표시용(클램프된) 현재 페이지 인덱스. */
  currentPage: number;
  sort: readonly BoardSortEntry[];
  onSort: (column: BoardSortColumn) => void;
  selectedVideoIds: Set<number>;
  onToggleRow: (videoId: number) => void;
  onToggleAllPaged: () => void;
  /** 목록 조회 실패 등으로 화면 데이터가 최신이 아닐 때 배정 액션을 잠근다. */
  actionsDisabled: boolean;
  onAssign: (row: TaskRow) => void;
  onHistory: (row: TaskRow) => void;
  onOpenLabel: (srcSn: number) => void;
  onOpenMarking: (videoId: number) => void;
  /**
   * 지금 **제외분만** 보고 있는가 — 켜지면 배정·검수로 이어지는 동작을 전부 감춘다
   * (선택 체크박스 · 배정/재배정 · 배정 해제). [@design SCREEN-012] [@design AC-1127]
   *
   * ⚠ **기본값이 종전 동작**이다(꺼짐) — 이 prop 을 모르는 기존 호출부의 화면이 그대로여야 한다.
   *   기본값을 켜짐으로 두면 작업 목록의 배정 동선이 통째로 사라지는데, 그 회귀는 이 표를
   *   직접 렌더하는 시험에서는 드러나지 않는다(그쪽은 제외 축을 보지 않는다).
   */
  excludedOnly?: boolean;
  /**
   * 배정 해제 — 재배정과 **같은 자리**에 놓이되 뜻이 다르다(담당 교체 ↔ 배정 제거).
   * [@design API-259] [@design AC-1123]
   *
   * ⚠ 선택 prop 이지만 **버튼은 넘기지 않아도 그려진다** — 안 그리면 호출부가 배선을 빠뜨렸을 때
   *   버튼이 조용히 사라져 아무도 모른다(그 실패는 화면에서 「원래 없는 기능」과 구분되지 않는다).
   */
  onUnassign?: (row: TaskRow) => void;
}

/**
 * 작업목록 테이블 — 헤더(정렬) + 행 렌더링 전담.
 *
 * TaskListPage 에서 **순수 추출**한 표시 컴포넌트다. 데이터 조회·필터·선택 상태는 전부 페이지가 갖고,
 * 여기서는 받은 것을 그리기만 한다.
 *
 * UI/UX §4-5 정합 — priority/deadline 컬럼은 절대 추가하지 않는다.
 */
export function TaskBoardTable({
  rows,
  isReviewer,
  isLoading,
  refreshing,
  totalElements,
  totalPages,
  currentPage,
  sort,
  onSort,
  selectedVideoIds,
  onToggleRow,
  onToggleAllPaged,
  actionsDisabled,
  onAssign,
  onHistory,
  onOpenLabel,
  onOpenMarking,
  excludedOnly = false,
  onUnassign,
}: TaskBoardTableProps) {
  // 일괄 배정은 미배정 행 전용이다(사양 SCREEN-012 ★) — 이미 작업자가 배정된 행은
  // "전체 선택" 대상 집합에서 제외한다(개별 체크박스는 아래에서 별도로 disabled 처리).
  const pagedVideoIds = rows.filter((r) => !r.task?.workerId).map((r) => r.video.id);
  const allPagedSelected =
    pagedVideoIds.length > 0 && pagedVideoIds.every((id) => selectedVideoIds.has(id));
  const somePagedSelected = pagedVideoIds.some((id) => selectedVideoIds.has(id));
  // ★개수를 적지 않는다 — 헤더를 그리는 바로 그 구성에서 센다(BOARD_COLUMNS 주석 참조).
  //   숫자를 따로 적으면 열이 바뀔 때 한쪽만 고쳐져 로딩 칸 수·빈 상태 colSpan 이 조용히 어긋난다.
  const columns = visibleBoardColumns(isReviewer);
  const columnCount = columns.length;
  const scrollRef = useRef<HTMLDivElement>(null);
  const overflowing = useHorizontalOverflow(scrollRef, [isReviewer, rows.length, isLoading]);

  return (
    <div className="overflow-hidden rounded-lg border border-gray-200 bg-white shadow-sm">
      <div className="flex items-center gap-3 border-b border-gray-100 bg-gray-50 px-4 py-2">
        {/* ★제외분만 보는 동안에는 선택 수단을 두지 않는다 — 일괄 배정 액션바가 함께 사라져
            아무 데도 닿지 않는 조작이 되고, 제외한 것을 배정 대상으로 담는 길처럼 보인다. */}
        {isReviewer && !excludedOnly && pagedVideoIds.length > 0 && (
          <Field orientation="horizontal">
            <Checkbox
              checked={allPagedSelected ? true : somePagedSelected ? 'indeterminate' : false}
              disabled={actionsDisabled}
              onCheckedChange={() => onToggleAllPaged()}
            />
            <FieldLabel className="text-label font-normal text-gray-600">
              현재 페이지 전체 선택
            </FieldLabel>
          </Field>
        )}
        <span className="ml-auto flex items-center gap-2 text-caption text-gray-600">
          {refreshing && (
            <span data-testid="board-refreshing" role="status">
              갱신 중…
            </span>
          )}
          전체 {totalElements}건
          {totalPages > 1 ? ` (${currentPage + 1}/${totalPages} 페이지)` : ''}
        </span>
      </div>
      <div className="relative">
        <div
          ref={scrollRef}
          className="overflow-x-auto"
          // 스크롤이 실제로 생겼을 때만 포커스 가능한 영역으로 만든다 — 마우스 없이 키보드로도
          // 가려진 컬럼에 도달할 수 있어야 한다(스크롤 컨테이너 a11y 표준 패턴).
          // 스크롤이 없을 때 tabIndex 를 주면 아무 효과 없는 탭 정지점만 늘어난다.
          {...(overflowing ? { tabIndex: 0 } : {})}
          role="region"
          aria-label="작업 목록 표"
        >
          <table
            className={cn(
              'w-full text-body-md',
              isReviewer ? TABLE_MIN_WIDTH.reviewer : TABLE_MIN_WIDTH.worker,
            )}
          >
          <thead>
            {/* 헤더 배경은 secondary 스케일 최옅단(DS-001 do_rules) — 페이지 배경과 같은
                회색을 쓰면 열 구조가 먼저 읽히지 않는다. */}
            <tr className="border-b border-gray-200 bg-secondary-50">
              {/* 헤더는 컬럼 구성(BOARD_COLUMNS)에서 그대로 나온다 — 아래 로딩 칸 수·빈 상태
                  colSpan 도 같은 구성을 세므로 셋이 갈라질 수 없다. */}
              {columns.map((col) =>
                col.select ? (
                  <th key={col.key} className="w-10 px-3 py-3" />
                ) : col.sortColumn && isReviewer ? (
                  <SortableHeader
                    key={col.key}
                    label={col.label}
                    column={col.sortColumn}
                    sort={sort}
                    onSort={onSort}
                  />
                ) : (
                  <th key={col.key} scope="col" className={TH_CLASS}>
                    {col.label}
                  </th>
                ),
              )}
            </tr>
          </thead>
          <tbody>
            {isLoading ? (
              Array.from({ length: 5 }).map((_, i) => (
                <tr key={i} className="border-b border-gray-100">
                  {Array.from({ length: columnCount }).map((__, j) => (
                    <td key={j} className={TD_PAD}>
                      <Skeleton height={16} />
                    </td>
                  ))}
                </tr>
              ))
            ) : rows.length === 0 ? (
              <tr>
                <td colSpan={columnCount} className="px-3 py-12">
                  <EmptyState message="배정된 작업이 없습니다" />
                </td>
              </tr>
            ) : (
              rows.map((r) => (
                <tr
                  key={r.id}
                  className="border-b border-gray-100 transition-colors hover:bg-rowHover"
                >
                  {isReviewer && (
                    <td className={TD_PAD}>
                      {/* 일괄 배정은 미배정 행 전용이다 — 이미 작업자가 배정된 행은
                          선택 체크박스를 비활성화한다(사양 SCREEN-012 ★).
                          ★제외분만 보는 목록에서는 체크칸 자체를 두지 않는다. 칸(`<td>`)은 남겨
                            열 수가 헤더·로딩 스켈레톤·빈 상태 colSpan 과 갈라지지 않게 한다. */}
                      {!excludedOnly && (
                        <Checkbox
                          aria-label={`${r.videoName} 선택`}
                          checked={selectedVideoIds.has(r.video.id)}
                          disabled={actionsDisabled || !!r.task?.workerId}
                          onCheckedChange={() => onToggleRow(r.video.id)}
                        />
                      )}
                    </td>
                  )}
                  <td className={TD_PAD}>
                    <p className="min-w-[140px] max-w-[180px] truncate text-body-md font-medium text-gray-800">
                      {r.videoName}
                    </p>
                  </td>
                  {/* 영상 코드는 하이픈에서 끊기면 안 되는 단일 식별자다. */}
                  <td className={`whitespace-nowrap ${TD_PAD}`}>
                    <span className="text-caption text-gray-400">{videoCode(r.video.id)}</span>
                  </td>
                  {isReviewer && (
                    <td className={TD_PAD}>
                      {r.video.capturedAt ? (
                        <span className="whitespace-nowrap text-body-md text-gray-700">
                          {formatDateTime(r.video.capturedAt)}
                        </span>
                      ) : (
                        <span className="text-caption text-gray-400">-</span>
                      )}
                    </td>
                  )}
                  {/* 이벤트·증강 뱃지는 pill 이라 줄바꿈되면 형태가 무너진다
                      ("이상행동(유괴)" 처럼 긴 라벨이 글자 단위로 끊긴다). */}
                  <td className={`whitespace-nowrap ${TD_PAD}`}>
                    <div className="flex flex-col items-start gap-1">
                      {r.video.eventName ? (
                        <EventTypeBadge eventType={r.video.eventName} />
                      ) : (
                        <span className="text-caption text-gray-400">-</span>
                      )}
                      {/* 증강/해상도 파생 데이터 뱃지 (R3). 원본(augmented=false)은 미표시.
                          기술모델명 비노출 — augTypeLabel 로 한글 라벨만 표시. */}
                      {r.augmented && (
                        <span
                          data-testid={`task-aug-badge-${r.video.id}`}
                          aria-label={`증강 데이터: ${augTypeLabel(r.augType)}`}
                          className="inline-flex w-fit items-center rounded bg-info/10 px-2 py-0.5 text-label font-medium text-info-700"
                        >
                          {augTypeLabel(r.augType)}
                        </span>
                      )}
                    </div>
                  </td>
                  {/* 상태 뱃지 — 1280px 에서 "미배정" 이 `미/배/정` 3줄로 쪼개지던 지점. */}
                  <td className={`whitespace-nowrap ${TD_PAD}`}>
                    <StatusBadge
                      status={STATUS_BADGE_MAP[r.rowStatus]}
                      label={TASK_STATUS_LABEL[r.rowStatus]}
                    />
                  </td>
                  {/* 사람 이름·"미배정"/"미등록" 은 짧은 고정 문구라 줄바꿈 이득이 없다. */}
                  <td className={`whitespace-nowrap ${TD_PAD}`}>
                    {r.task?.workerName ? (
                      <span className="text-body-md text-gray-700">{r.task.workerName}</span>
                    ) : (
                      <span className="text-body-md italic text-gray-400">미배정</span>
                    )}
                  </td>
                  {/* 액션 버튼 라벨("배정"/"재배정"/"이력"/"작업"/"마킹")은 줄바꿈되면
                      버튼이 세로로 늘어나 행 높이를 무너뜨린다. */}
                  <td className={`whitespace-nowrap ${TD_PAD}`}>
                    {/* 배정 해제와 그 비활성 사유가 더해져 한 줄에 들어가지 않을 수 있다 —
                        `flex-wrap` 으로 접는다(구 `flex-nowrap` 은 표 최소 폭을 넘겨 밀어냈다). */}
                    <div className="flex flex-wrap items-center gap-1">
                      {/* 배정 / 재배정 — REVIEWER (mock 정합: ghost 텍스트 버튼).
                          COMPLETED(검수 승인 완료) 행은 재배정 불가 — 버튼 자체를 가린다.
                          BE 가드(ASSIGNMENT_ALREADY_COMPLETED)와 짝을 이루는 UI 정합. */}
                      {isReviewer &&
                        !excludedOnly &&
                        !(r.task?.workerId && r.rowStatus === 'COMPLETED') && (
                          <Button
                            variant="ghost"
                            size="sm"
                            disabled={actionsDisabled}
                            title={
                              actionsDisabled
                                ? '목록이 최신 정보가 아니어서 배정할 수 없습니다'
                                : undefined
                            }
                            onClick={() => onAssign(r)}
                          >
                            {r.task?.workerId ? (
                              <RefreshCw size={14} aria-hidden />
                            ) : (
                              <UserPlus size={14} aria-hidden />
                            )}
                            {r.task?.workerId ? '재배정' : '배정'}
                          </Button>
                        )}
                      {/*
                        [@design API-259] [@design AC-1123] 배정 해제 — **재배정과 같은 자리**에 두되
                        뜻이 다르다(담당 교체 ↔ 배정 제거). 배정이 있는 행에만 선다.
                        ★검수 단계에 들어간 배정(검수 대기·검수 중·승인)은 **미리 비활성 + 사유**를
                          함께 보인다 — 눌러서 물리쳐진 뒤에야 아는 동선을 만들지 않는다.
                        ★반려는 그 셋에 들지 않아 **활성**이다(`unassignEligibility` 가 그 판정을 소유한다).
                        ⚠ 사유를 `title` 로만 두지 않는다 — 비활성 버튼은 초점을 받지 못해 보조기술이
                          그 속성에 닿지 못한다.
                        ⚠ 제외분만 보는 목록에서는 두지 않는다(배정 동선 전체와 함께 사라진다).
                      */}
                      {isReviewer && !excludedOnly && r.task?.workerId && (
                        <>
                          <Button
                            variant="ghost"
                            size="sm"
                            disabled={actionsDisabled || isUnassignBlocked(r.rowStatus)}
                            onClick={() => onUnassign?.(r)}
                            aria-label={`${r.videoName} 배정 해제`}
                            data-testid={`task-unassign-${r.video.id}`}
                          >
                            배정 해제
                          </Button>
                          {isUnassignBlocked(r.rowStatus) && (
                            <span
                              className="text-caption text-gray-600"
                              data-testid={`task-unassign-blocked-${r.video.id}`}
                            >
                              {UNASSIGN_BLOCKED_REASON}
                            </span>
                          )}
                        </>
                      )}
                      {/* 이력 — task가 있을 때만 (REVIEWER) */}
                      {isReviewer && r.task && (
                        <Button variant="ghost" size="sm" onClick={() => onHistory(r)}>
                          <History size={14} aria-hidden />
                          이력
                        </Button>
                      )}
                      {/* WORKER: 본인 배정 작업 시작 / 마킹 + 이력 보기 (BE Service 레이어에서 IDOR 방어).
                          - 프레임 존재(firstSrcSn) → "작업" 버튼 (라벨링 화면으로 navigate)
                          - 프레임 미존재 → "마킹" 버튼 (마킹 화면으로 navigate) */}
                      {!isReviewer && r.task && (
                        <>
                          {r.task.firstSrcSn ? (
                            <Button
                              size="sm"
                              variant="ghost"
                              onClick={() => onOpenLabel(r.task!.firstSrcSn as number)}
                              aria-label={`작업 시작 ${r.videoName}`}
                            >
                              <Play size={14} aria-hidden /> 작업
                            </Button>
                          ) : isMarkingBlocked(r.video) ? (
                            // AC4 — 비식별 미완료 영상은 마킹 진입 차단(버튼 비활성 + 사유 안내).
                            // 색상만이 아닌 텍스트/aria 라벨로 사유 전달(component.md 접근성).
                            <Button
                              size="sm"
                              variant="ghost"
                              disabled
                              title="비식별 완료 후 마킹 가능"
                              aria-label={`마킹 불가 (비식별 완료 후 가능) ${r.videoName}`}
                            >
                              <ListTodo size={14} aria-hidden /> 마킹
                            </Button>
                          ) : (
                            <Button
                              size="sm"
                              variant="ghost"
                              onClick={() => onOpenMarking(r.task!.videoId)}
                              aria-label={`마킹 시작 ${r.videoName}`}
                            >
                              <ListTodo size={14} aria-hidden /> 마킹
                            </Button>
                          )}
                          <Button
                            size="sm"
                            variant="ghost"
                            onClick={() => onHistory(r)}
                            aria-label="배정 이력 보기"
                          >
                            <History size={14} aria-hidden /> 이력
                          </Button>
                        </>
                      )}
                    </div>
                  </td>
                </tr>
              ))
            )}
          </tbody>
          </table>
        </div>
        {/* 스크롤 단서 — 넘칠 때만. 오른쪽 끝 그라데이션은 "여기서 잘렸다"를 보여주고(장식이라
            aria-hidden + pointer-events-none), 아래 문구가 색/그림자에 의존하지 않는 텍스트
            단서를 준다(색만으로 정보 전달 금지). 구 동작은 단서가 전혀 없어 화면 밖 버튼을
            사용자가 발견하지 못했다. */}
        {overflowing && (
          <>
            <div
              aria-hidden
              className="pointer-events-none absolute inset-y-0 right-0 w-10 bg-gradient-to-l from-white to-transparent"
            />
            <p
              data-testid="task-board-scroll-hint"
              className="border-t border-gray-100 bg-gray-50 px-4 py-1.5 text-caption text-gray-600"
            >
              표가 화면보다 넓습니다 — 좌우로 스크롤하면 나머지 항목을 볼 수 있습니다.
            </p>
          </>
        )}
      </div>
    </div>
  );
}

export default TaskBoardTable;
