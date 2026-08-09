import { useCallback, useEffect, useMemo, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { RefreshCw, Users } from 'lucide-react';
import { useQueryClient } from '@tanstack/react-query';

import { Button } from '@/components/common/Button';
import { ErrorState } from '@/components/common/ErrorState';
import { Pagination } from '@/components/common/Pagination';
import {
  DEFAULT_TASK_FILTERS,
  asAssignmentWorkStatusParam,
  asWorkStatusParam,
  buildAssignmentEventTypeParams,
  buildAssignmentParams,
  buildBoardEventTypeParams,
  buildBoardParams,
  buildBoardSummaryParams,
  filtersToSearchParams,
  searchParamsToFilters,
  searchParamsToSort,
  type TaskFilterValues,
} from '@/features/task/boardParams';
import {
  DEFAULT_BOARD_SORT,
  toggleBoardSort,
  type BoardSortColumn,
  type BoardSortEntry,
} from '@/features/task/boardSort';
import { AssignModal } from '@/features/task/components/AssignModal';
import { HistoryDrawer } from '@/features/task/components/HistoryDrawer';
import {
  TaskBoardTable,
  type TaskRow,
} from '@/features/task/components/TaskBoardTable';
import { TaskBoardKpiCards } from '@/features/task/components/TaskBoardKpiCards';
import { TaskWorkerKpiCards } from '@/features/task/components/TaskWorkerKpiCards';
import { TaskFilters } from '@/features/task/components/TaskFilters';
import { useAssignmentEventTypes } from '@/features/task/hooks/useAssignmentEventTypes';
import { useTaskBoard } from '@/features/task/hooks/useTaskBoard';
import { useTaskBoardEventTypes } from '@/features/task/hooks/useTaskBoardEventTypes';
import { useTaskBoardSummary } from '@/features/task/hooks/useTaskBoardSummary';
import { useTasks } from '@/features/task/hooks/useTasks';
import type {
  AssignmentStatus,
  Task,
  TaskBoardItem,
  TaskListParams,
  WorkStatusParam,
} from '@/features/task/types';
import { useUsers } from '@/features/user/hooks/useUsers';
import { type BadgeStatus } from '@/components/common/StatusBadge';
import { type Video } from '@/features/video/types';
import { Role } from '@/lib/api/types';
import { useAuthStore } from '@/stores/useAuthStore';

const PAGE_SIZE = 20;

/**
 * 헤더 부제 — 역할별로 다른 안내 문구(사양 SCREEN-012 '역할별 부제').
 *
 * REVIEWER 문구는 사양이 그대로 인용한 문자열이다. WORKER 문구는 사양이 '본인 배정 작업 안내'
 * 라고만 규정하고 정확한 문자열을 주지 않아, 사양 본문("본인에게 할당된(WORKER) … 작업 목록")과
 * REVIEWER 문구의 어투에 맞춰 작성했다 — 확정 문자열이 정해지면 여기만 고치면 된다.
 */
const HEADER_SUBTITLE = {
  REVIEWER: '처리 완료된 영상만 표시',
  WORKER: '본인에게 배정된 작업만 표시',
} as const;

/**
 * SCR-TASK-001 작업 목록 (mock 정합 V1.x).
 *
 * 레이아웃:
 *   - 헤더: 제목 + 청록 역할 뱃지 + 부제 + 새로고침
 *   - 검색폼 (영상명/작업자명 + 이벤트 + 상태 + (REVIEWER) 작업자)
 *   - KPI 카드 — REVIEWER 5카드(서버 집계 + 클릭 필터) / WORKER 4카드(본인 배정분 집계)
 *   - 다중 선택 일괄 배정 액션바
 *   - 테이블(TaskBoardTable): (REVIEWER) 체크박스 + 영상명 + 영상 ID + 촬영일시 + 이벤트 + 상태 …
 *
 * ★ 필터·KPI 축 — **두 역할 모두 서버 필터**다(클라이언트 재필터 없음):
 * - REVIEWER: 검색어/상태/이벤트/작업자를 `/v1/tasks/board` 에 위임. 배치 상태 축은 **COMPLETED
 *   고정**이며(URL 로도 못 바꾼다) 헤더 부제와 한 몸이다. KPI 는 `/v1/tasks/board/summary` 의
 *   **전체 기준** 집계이고 카드 클릭은 `workStatus` 축만 바꾼다.
 * - WORKER: 검색어/상태/이벤트를 `/v1/assignments` 에 위임(Phase 4). 조회 범위는 서버 인가가
 *   본인 배정으로 고정한다.
 * - 화면에서 행을 다시 거르지 않는다 — 현재 페이지 20건 안에서 거르면 목록·"전체 N건"·페이지 수·
 *   드롭다운이 서로 다른 집합을 말하게 되고 뒷페이지 항목은 검색해도 나오지 않는다.
 * - 이벤트유형 드롭다운도 역할별 옵션 API(전체 기준)를 쓴다 — 현재 페이지에서 수집하지 않는다.
 *
 * UI/UX §4-5 정합 — priority/deadline 컬럼은 절대 추가하지 않는다.
 */
export function TaskListPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const claims = useAuthStore((s) => s.claims);
  const role = claims?.role ?? Role.WORKER;
  const isReviewer = role === Role.REVIEWER;
  const headerSubtitle = isReviewer
    ? HEADER_SUBTITLE.REVIEWER
    : HEADER_SUBTITLE.WORKER;

  const [filters, setFilters] = useState<TaskFilterValues>(() => {
    const parsed = searchParamsToFilters(searchParams);
    // 워크플로 값 집합은 역할마다 다르다(REVIEWER 축엔 `IN_PROGRESS` 가, WORKER 축엔 `UNASSIGNED` 가
    // 없다). URL 은 두 역할이 공유하므로 반대 축 값이 들어올 수 있는데, 그대로 두면 상태 select 는
    // 빈칸인데 목록은 전체가 나오는 어긋난 화면이 된다 — 이 화면에서 고를 수 없는 값은 버린다.
    const selectable = isReviewer
      ? asWorkStatusParam(parsed.workStatus)
      : asAssignmentWorkStatusParam(parsed.workStatus);
    return selectable ? parsed : { ...parsed, workStatus: '' };
  });
  const [sort, setSort] = useState<BoardSortEntry[]>(() =>
    searchParamsToSort(searchParams),
  );
  const [page, setPage] = useState(0);

  // Modal state
  const [assignModalOpen, setAssignModalOpen] = useState(false);
  const [assignMode, setAssignMode] = useState<'assign' | 'reassign' | 'bulk'>(
    'assign',
  );
  const [selectedTask, setSelectedTask] = useState<Task | null>(null);
  // 미배정 영상 단건 신규 배정용 (task=null 일 때 사용)
  const [selectedVideoForAssign, setSelectedVideoForAssign] = useState<{
    id: number;
    name: string;
  } | null>(null);
  const [selectedVideoIds, setSelectedVideoIds] = useState<Set<number>>(
    new Set(),
  );
  // 이력 Drawer
  const [historyDrawerOpen, setHistoryDrawerOpen] = useState(false);
  const [historyAssignmentId, setHistoryAssignmentId] = useState<number | null>(
    null,
  );
  const [historyVideoName, setHistoryVideoName] = useState<string | undefined>(
    undefined,
  );

  // 데이터 fetch — 필터·페이징을 그대로 BE로 위임.
  // - WORKER 시각: BE 페이징+필터(/assignments) 사용
  // - REVIEWER 시각: /v1/tasks/board BE 단일 엔드포인트 사용 — useVideos 의존 제거
  const taskParams = useMemo<TaskListParams>(
    () => buildAssignmentParams(filters, { page, size: PAGE_SIZE }),
    [filters, page],
  );

  const {
    data: tasksPage,
    isLoading: tasksLoading,
    error: tasksError,
    refetch: refetchTasks,
  } = useTasks(taskParams, { enabled: !isReviewer });

  // REVIEWER 통합 작업 목록 — BE /v1/tasks/board 페이징 + 서버 필터/정렬.
  const boardParams = useMemo(
    () => buildBoardParams(filters, { page, size: PAGE_SIZE, sort }),
    [filters, page, sort],
  );
  const {
    data: boardPage,
    isLoading: boardLoading,
    isFetching: boardFetching,
    error: boardError,
    refetch: refetchBoard,
  } = useTaskBoard(boardParams, { enabled: isReviewer });

  // KPI 집계 — 목록과 **독립 쿼리**. workStatus 는 제외된다(카드 자체가 그 선택지).
  const summaryParams = useMemo(
    () => buildBoardSummaryParams({ ...filters, workStatus: '' }),
    [filters],
  );
  const {
    data: summary,
    isLoading: summaryLoading,
    isError: summaryIsError,
    refetch: refetchSummary,
  } = useTaskBoardSummary(summaryParams, { enabled: isReviewer });

  // 이벤트유형 옵션 — 역할별 엔드포인트, 둘 다 **현재 페이지가 아니라 전체 기준**이다.
  // 어느 쪽도 다른 필터 축을 보내지 않는다(옵션이 좁아지면 고른 값으로 되돌아갈 수 없다).
  const eventTypeParams = useMemo(() => buildBoardEventTypeParams(), []);
  const { items: boardEventTypes, truncated: boardEventTypesTruncated } =
    useTaskBoardEventTypes(eventTypeParams, { enabled: isReviewer });

  const assignmentEventTypeParams = useMemo(
    () => buildAssignmentEventTypeParams(),
    [],
  );
  const {
    items: assignmentEventTypes,
    truncated: assignmentEventTypesTruncated,
  } = useAssignmentEventTypes(assignmentEventTypeParams, {
    enabled: !isReviewer,
  });

  const isLoading = isReviewer ? boardLoading : tasksLoading;
  // ★ 페이지 레벨 에러는 **목록 쿼리만** 결정한다 — KPI/옵션 실패가 정상 목록을 가리면 안 된다.
  const error = isReviewer ? boardError : tasksError;
  const hasListError = Boolean(error);
  const refetch = useCallback(() => {
    if (isReviewer) {
      refetchBoard();
      refetchSummary();
    } else {
      refetchTasks();
    }
  }, [isReviewer, refetchBoard, refetchSummary, refetchTasks]);

  // /users 는 REVIEWER 전용 (BE @PreAuthorize). WORKER 화면에서는 호출 자체를 막아 403 스팸을 방지한다.
  const { data: workersPage } = useUsers(
    { role: Role.WORKER, size: 100 },
    { enabled: isReviewer },
  );
  const { data: reviewersPage } = useUsers(
    { role: Role.REVIEWER, size: 100 },
    { enabled: isReviewer },
  );

  // useMemo 로 감싸 참조를 안정화한다 — 아래 파생 useMemo 들의 deps 가 매 렌더 바뀌지 않게.
  const tasks = useMemo(() => tasksPage?.content ?? [], [tasksPage]);
  const boardItems = useMemo<TaskBoardItem[]>(
    () => boardPage?.content ?? [],
    [boardPage],
  );
  const workers = useMemo(
    () =>
      (workersPage?.content ?? []).map((u) => ({
        id: u.id,
        name: u.name,
        active: u.active,
      })),
    [workersPage],
  );

  // 검수자 ID → 이름 매핑 (TaskBoardItem.reviewerName 이 비어있을 때 보조 폴백)
  const reviewerMap = useMemo(() => {
    const map: Record<number, string> = {};
    (reviewersPage?.content ?? []).forEach((r) => {
      map[r.id] = r.name;
    });
    return map;
  }, [reviewersPage]);

  // 이벤트 유형 옵션 — 역할별 서버 조회 결과(현재 페이지에 없는 코드도 고를 수 있다).
  // - REVIEWER: /v1/tasks/board/event-types
  // - WORKER  : /v1/assignments/event-types (본인 배정 전체 기준, 서버가 인가로 범위 고정)
  const eventTypeOptions = isReviewer ? boardEventTypes : assignmentEventTypes;
  // 상한 절단 여부도 그대로 넘긴다 — 버리면 "그 유형 영상이 없다" 는 조용한 오인이 된다.
  const eventTypesTruncated = isReviewer
    ? boardEventTypesTruncated
    : assignmentEventTypesTruncated;

  // base 계산.
  // - WORKER: 본인에게 배정된 task만 표시 (미배정 영상 left-join 금지 — IDOR/노이즈 방지)
  // - REVIEWER: BE /v1/tasks/board 가 처리 완료 영상 + left-join LABELER 배정을 enrich 한 결과를 그대로 매핑.
  const allRows = useMemo<TaskRow[]>(() => {
    if (!isReviewer) {
      // WORKER 시각은 BE TaskResponse 의 eventName/eventTypeCd 만 사용한다.
      return tasks.map((t) => ({
        id: String(t.videoId),
        video: {
          id: t.videoId,
          cctvName: t.cctvName,
          vmsClipId: '',
          eventName: t.eventName ?? '',
          eventTypeCd: t.eventTypeCd ?? '',
          localGov: '',
          frameCount: 0,
          status: 'COMPLETED' as BadgeStatus,
          capturedAt: t.assignedAt,
          // 마킹 진입 차단(AC4) 판정에 필요 — BE 미전송 시 undefined → 차단 안 함(BE 백스톱).
          deIdntfYn: t.deIdntfYn,
          deidentStatus: t.deidentStatus as Video['deidentStatus'],
        },
        task: t,
        rowStatus: t.status,
        videoName: t.cctvName,
        augmented: t.augmented ?? false,
        augType: t.augType ?? null,
      }));
    }
    // REVIEWER — BE /v1/tasks/board 응답을 TaskRow 로 변환.
    return boardItems.map((it) => {
      const cctvName = it.cctvName ?? '';
      const hasAssignment = it.assignmentId != null && it.workerId != null;
      const task: Task | undefined = hasAssignment
        ? {
            id: it.assignmentId as number,
            videoId: it.videoId,
            cctvName,
            workerId: it.workerId as number,
            workerName: it.workerName ?? '',
            reviewerId: it.reviewerId ?? undefined,
            reviewerName: it.reviewerName ?? undefined,
            status: (it.status === 'UNASSIGNED'
              ? 'PENDING'
              : it.status) as AssignmentStatus,
            assignedAt: it.assignedAt ?? '',
            firstSrcSn: it.firstSrcSn ?? undefined,
            eventName: it.eventName ?? undefined,
            eventTypeCd: it.eventTypeCd ?? undefined,
          }
        : undefined;
      const video: Video = {
        id: it.videoId,
        cctvName,
        vmsClipId: '',
        eventName: it.eventName ?? '',
        eventTypeCd: it.eventTypeCd ?? '',
        localGov: '',
        frameCount: it.frameCount ?? 0,
        status: 'COMPLETED' as BadgeStatus,
        capturedAt: it.capturedAt ?? '',
        deIdntfYn: it.deIdntfYn ?? undefined,
        deidentStatus: (it.deidentStatus ?? undefined) as Video['deidentStatus'],
      };
      return {
        id: String(it.videoId),
        video,
        task,
        rowStatus: it.status,
        videoName: cctvName,
        augmented: it.augmented ?? false,
        augType: it.augType ?? null,
      };
    });
  }, [isReviewer, boardItems, tasks]);

  /**
   * 화면에 그릴 행 = **서버가 이미 거른 결과** 그대로다.
   *
   * 여기서 다시 거르면 현재 페이지 20건 안에서만 걸러져 목록·총건수·KPI 가 서로 다른 값을 말하고,
   * 뒷페이지 항목은 검색해도 나오지 않는다(클라이언트 재필터 금지 — 역할 무관).
   */
  const pagedRows = allRows;

  // 페이징 — 두 역할 모두 BE totalPages 사용 (필터가 서버에 적용된 결과).
  const listPage = isReviewer ? boardPage : tasksPage;
  const totalPages = Math.max(1, listPage?.totalPages ?? 1);
  const safePage = Math.min(page, totalPages - 1);
  // 헤더의 "전체 N건" 은 **필터 결과 전체**를 세야 한다 — 현재 페이지 행 수가 아니다.
  const totalElements = listPage?.totalElements ?? allRows.length;

  // 필터·정렬 변경 시 URL 동기화. 페이지 리셋은 **상태를 바꾼 핸들러가 함께** 처리한다 —
  // effect 로 미루면 직전 페이지 번호로 목록 요청이 한 번 더 나간다.
  useEffect(() => {
    setSearchParams(filtersToSearchParams(filters, sort), { replace: true });
  }, [filters, sort, setSearchParams]);

  // 총 페이지 수가 줄어 현재 페이지가 범위를 벗어나면 되돌린다.
  // 그대로 두면 목록은 비고 페이지네이션 UI 도 사라져 복구 경로가 없다.
  useEffect(() => {
    if (!isLoading && page > totalPages - 1) {
      setPage(Math.max(0, totalPages - 1));
    }
  }, [isLoading, page, totalPages]);

  // 일괄 배정은 미배정 행 전용이다(사양 SCREEN-012 ★) — 이미 작업자가 배정된 행은
  // "현재 페이지 전체 선택"·개별 체크박스 대상에서 제외한다.
  const pagedVideoIds = useMemo(
    () => pagedRows.filter((r) => !r.task?.workerId).map((r) => r.video.id),
    [pagedRows],
  );

  // 화면에 없는 선택은 버린다 — 새로고침으로 행이 갈리면 보이지 않는 영상이 일괄 배정에 섞인다.
  useEffect(() => {
    setSelectedVideoIds((prev) => {
      if (prev.size === 0) return prev;
      const visible = new Set(pagedVideoIds);
      const next = new Set([...prev].filter((id) => visible.has(id)));
      return next.size === prev.size ? prev : next;
    });
  }, [pagedVideoIds]);

  const clearSelection = useCallback(() => setSelectedVideoIds(new Set()), []);

  /** 필터 제출 — 페이지·선택 초기화를 **같은 이벤트에서** 처리한다(요청 1회). */
  const handleFiltersChange = useCallback(
    (next: TaskFilterValues) => {
      setFilters(next);
      setPage(0);
      clearSelection();
    },
    [clearSelection],
  );

  const handleFiltersReset = useCallback(() => {
    // 모듈 상수를 그대로 넘기면 참조가 같아 React 가 갱신을 건너뛴다(두 번째 초기화가 no-op).
    handleFiltersChange({ ...DEFAULT_TASK_FILTERS });
    // 정렬도 기본값으로 되돌린다 — 초기화가 필터만 지우면 URL 의 `sort`·헤더 aria-sort·요청
    // 파라미터가 그대로 남는다. 특히 `regDt` 는 컬럼 헤더가 없어 토글로 되돌릴 수 없으므로
    // 초기화가 유일한 복구 경로다(기본 정렬은 URL 에 기록되지 않아 파라미터도 함께 사라진다).
    setSort([...DEFAULT_BOARD_SORT]);
  }, [handleFiltersChange]);

  /** KPI 카드 클릭 — 워크플로 축만 바꾼다(배치 축은 고정). 같은 카드 재클릭은 해제. */
  const handleKpiSelect = useCallback(
    (workStatus: WorkStatusParam | undefined) => {
      setFilters((prev) => ({ ...prev, workStatus: workStatus ?? '' }));
      setPage(0);
      clearSelection();
    },
    [clearSelection],
  );

  const handleSort = useCallback(
    (column: BoardSortColumn) => {
      setSort((prev) => toggleBoardSort(prev, column));
      setPage(0);
      clearSelection();
    },
    [clearSelection],
  );

  const handlePageChange = useCallback(
    (next: number) => {
      setPage(next);
      clearSelection();
    },
    [clearSelection],
  );

  // 다중 선택 토글
  const toggleRow = useCallback((videoId: number) => {
    setSelectedVideoIds((prev) => {
      const next = new Set(prev);
      if (next.has(videoId)) next.delete(videoId);
      else next.add(videoId);
      return next;
    });
  }, []);

  const toggleAllPaged = useCallback(() => {
    setSelectedVideoIds((prev) => {
      const next = new Set(prev);
      const allSelected =
        pagedVideoIds.length > 0 && pagedVideoIds.every((id) => prev.has(id));
      if (allSelected) {
        pagedVideoIds.forEach((id) => next.delete(id));
      } else {
        pagedVideoIds.forEach((id) => next.add(id));
      }
      return next;
    });
  }, [pagedVideoIds]);

  const handleRefresh = () => {
    refetch();
    queryClient.invalidateQueries({ queryKey: ['assignments'] });
  };

  const openBulkAssign = () => {
    if (selectedVideoIds.size === 0 || hasListError) return;
    setSelectedTask(null);
    setAssignMode('bulk');
    setAssignModalOpen(true);
  };

  const handleAssignRow = useCallback((row: TaskRow) => {
    if (row.task?.workerId) {
      // 기존 배정 — 재배정 모드
      setSelectedTask(row.task);
      setSelectedVideoForAssign(null);
      setAssignMode('reassign');
    } else {
      // 미배정 영상 — 단건 신규 배정 모드 (videoId 전달)
      setSelectedTask(null);
      setSelectedVideoForAssign({ id: row.video.id, name: row.videoName });
      setAssignMode('assign');
    }
    setAssignModalOpen(true);
  }, []);

  const handleHistoryRow = useCallback((row: TaskRow) => {
    if (!row.task) return;
    setHistoryAssignmentId(row.task.id);
    setHistoryVideoName(row.videoName);
    setHistoryDrawerOpen(true);
  }, []);

  const videoNameById = useMemo(() => {
    const map: Record<number, string> = {};
    boardItems.forEach((it) => {
      if (it.cctvName) map[it.videoId] = it.cctvName;
    });
    tasks.forEach((t) => {
      map[t.videoId] = t.cctvName;
    });
    return map;
  }, [boardItems, tasks]);

  // WORKER KPI 4카드 — 서버 집계 API 가 REVIEWER 전용(403)이라 현재 페이지 행을 센다(표시 전용,
  // 구 동작 그대로). 카드 클릭 필터 연동이 없어 숫자를 필터의 근거로 쓰지 않는다.
  const workerRowStatuses = useMemo(
    () => pagedRows.map((r) => r.rowStatus),
    [pagedRows],
  );

  /** 배정/일괄배정 성공 — 모달을 닫고 선택을 비운 뒤 목록을 다시 읽는다. */
  const handleAssignSuccess = () => {
    setAssignModalOpen(false);
    setSelectedVideoForAssign(null);
    clearSelection();
    refetch();
    queryClient.invalidateQueries({ queryKey: ['assignments'] });
  };

  return (
    <div className="space-y-4">
      {/* Header */}
      <div className="flex items-center justify-between">
        {/* ★역할 구분에는 별도 배지를 두지 않는다 — 부제 문구가 그 역할을 한다(사양 SCREEN-012).
            구 '현재 역할: 검수자/작업자' 배지는 폐기됐고, 대신 부제가 역할별로 분기된다.
            구 구현은 배지가 역할을 말하고 부제는 REVIEWER 기준 문구로 고정돼 있어,
            WORKER 에게 "처리 완료된 영상만 표시" 라는 사실이 아닌 안내가 떴다. */}
        <div className="flex items-center gap-3">
          <h1 className="text-title-lg font-bold text-gray-900">작업 목록</h1>
          <span className="text-caption text-gray-600">{headerSubtitle}</span>
        </div>
        <Button variant="secondary" size="sm" onClick={handleRefresh}>
          <RefreshCw size={14} aria-hidden />
          새로고침
        </Button>
      </div>

      {/* 검색 폼 */}
      <TaskFilters
        values={filters}
        onChange={handleFiltersChange}
        onReset={handleFiltersReset}
        showAssigneeSelect={isReviewer}
        workers={workers}
        eventTypes={eventTypeOptions}
        eventTypesTruncated={eventTypesTruncated}
      />

      {hasListError && (
        <ErrorState
          title="작업 목록을 불러올 수 없습니다"
          // 배정은 REVIEWER 전용 기능이다 — WORKER 에게 "배정 기능" 안내를 하면
          // 존재하지 않는 기능을 찾게 된다.
          message={
            isReviewer
              ? '아래 목록은 최신 정보가 아닙니다. 배정 기능은 새로고침 후 사용할 수 있습니다.'
              : '아래 목록은 최신 정보가 아닙니다. 새로고침 후 다시 확인해 주세요.'
          }
          onRetry={handleRefresh}
        />
      )}

      {/* KPI 카드 — REVIEWER 5카드(서버 집계) / WORKER 4카드(본인 배정분 집계) */}
      {isReviewer ? (
        <TaskBoardKpiCards
          summary={summary}
          isLoading={summaryLoading}
          isError={summaryIsError}
          selected={filters.workStatus}
          onSelect={handleKpiSelect}
        />
      ) : (
        <TaskWorkerKpiCards rowStatuses={workerRowStatuses} />
      )}

      {/* 일괄 배정 액션바 (REVIEWER만, 1건 이상 선택 시) */}
      {isReviewer && selectedVideoIds.size > 0 && (
        <div
          data-testid="bulk-assign-bar"
          className="flex items-center justify-between gap-3 rounded-lg border border-primary-200 bg-primary-50 px-4 py-3"
        >
          <div className="flex items-center gap-3">
            <span className="inline-flex items-center px-2 py-0.5 rounded-full text-label font-semibold bg-info/10 text-info-700">
              {selectedVideoIds.size}개 선택됨
            </span>
            <button
              type="button"
              onClick={clearSelection}
              className="text-caption text-gray-600 underline hover:text-gray-700"
            >
              선택 해제
            </button>
          </div>
          <Button
            variant="primary"
            size="sm"
            onClick={openBulkAssign}
            disabled={hasListError}
            title={
              hasListError
                ? '목록이 최신 정보가 아니어서 배정할 수 없습니다'
                : undefined
            }
          >
            <Users size={14} aria-hidden />
            {selectedVideoIds.size}개 일괄 배정
          </Button>
        </div>
      )}

      <TaskBoardTable
        rows={pagedRows}
        isReviewer={isReviewer}
        isLoading={isLoading}
        refreshing={isReviewer && boardFetching && !boardLoading}
        totalElements={totalElements}
        totalPages={totalPages}
        currentPage={safePage}
        sort={sort}
        onSort={handleSort}
        selectedVideoIds={selectedVideoIds}
        onToggleRow={toggleRow}
        onToggleAllPaged={toggleAllPaged}
        actionsDisabled={hasListError}
        reviewerMap={reviewerMap}
        onAssign={handleAssignRow}
        onHistory={handleHistoryRow}
        onOpenLabel={(srcSn) => navigate(`/label/${srcSn}`)}
        onOpenMarking={(videoId) => navigate(`/marking/${videoId}`)}
      />

      {/*
        페이지네이션 — 공용 컨트롤을 그대로 쓴다(UI-008).
        이 화면이 갖고 있던 번호 목록은 항상 앞쪽 7칸만 그려, 페이지가 8개를 넘으면 뒤 페이지로
        가는 번호가 아예 없었다. 공용 컨트롤은 양끝 + 현재 앞뒤 1칸을 남기고 접는다.
        총 건수 표기는 표(TaskBoardTable) 머리글이 계속 소유한다.
      */}
      {totalPages > 1 && (
        <Pagination
          page={safePage}
          totalPages={totalPages}
          onChange={handlePageChange}
          className="mt-4"
        />
      )}

      {/* Assign Modal (assign / reassign / bulk) */}
      <AssignModal
        open={assignModalOpen}
        onClose={() => setAssignModalOpen(false)}
        task={selectedTask}
        mode={assignMode}
        onSuccess={handleAssignSuccess}
        onBulkSuccess={handleAssignSuccess}
        videoIds={assignMode === 'bulk' ? Array.from(selectedVideoIds) : []}
        videoNameById={videoNameById}
        videoId={
          assignMode === 'assign' && !selectedTask
            ? selectedVideoForAssign?.id
            : undefined
        }
        videoName={
          assignMode === 'assign' && !selectedTask
            ? selectedVideoForAssign?.name
            : undefined
        }
      />

      {/* 배정 이력 Drawer (우측 슬라이드) */}
      <HistoryDrawer
        open={historyDrawerOpen}
        onClose={() => setHistoryDrawerOpen(false)}
        assignmentId={historyAssignmentId}
        videoName={historyVideoName}
      />
    </div>
  );
}
