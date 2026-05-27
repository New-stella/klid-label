import { useCallback, useEffect, useMemo, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import {
  ArrowDown,
  ChevronLeft,
  ChevronRight,
  Flame,
  History,
  ListTodo,
  Play,
  RefreshCw,
  UserPlus,
  Users,
} from 'lucide-react';
import { useQueryClient } from '@tanstack/react-query';

import { Button } from '@/components/common/Button';
import { EmptyState } from '@/components/common/EmptyState';
import { ErrorState } from '@/components/common/ErrorState';
import { EventTypeBadge } from '@/components/common/EventTypeBadge';
import { KpiCard } from '@/components/common/KpiCard';
import { Skeleton } from '@/components/common/Skeleton';
import { StatusBadge, type BadgeStatus } from '@/components/common/StatusBadge';
import { AssignModal } from '@/features/task/components/AssignModal';
import { HistoryDrawer } from '@/features/task/components/HistoryDrawer';
import {
  DEFAULT_TASK_FILTERS,
  TaskFilters,
  type TaskFilterValues,
} from '@/features/task/components/TaskFilters';
import { useTaskBoard } from '@/features/task/hooks/useTaskBoard';
import { useTasks } from '@/features/task/hooks/useTasks';
import type {
  AssignmentStatus,
  Task,
  TaskBoardItem,
  TaskListParams,
} from '@/features/task/types';
import { useUsers } from '@/features/user/hooks/useUsers';
import type { Video } from '@/features/video/types';
import { cn } from '@/lib/cn';
import { Role } from '@/lib/api/types';
import { useAuthStore } from '@/stores/useAuthStore';

const ROLE_LABEL: Record<string, string> = {
  REVIEWER: '검수자',
  WORKER: '작업자',
};

type RowStatus = AssignmentStatus | 'UNASSIGNED';

const STATUS_LABEL: Record<RowStatus, string> = {
  UNASSIGNED: '미배정',
  PENDING: '대기',
  IN_PROGRESS: '진행중',
  REVIEW_PENDING: '검수대기',
  COMPLETED: '완료',
  REJECTED: '반려',
};

// AssignmentStatus → BadgeStatus 매핑 (UNASSIGNED는 PENDING으로)
const STATUS_BADGE_MAP: Record<RowStatus, BadgeStatus> = {
  UNASSIGNED: 'PENDING',
  PENDING: 'PENDING',
  IN_PROGRESS: 'IN_PROGRESS',
  REVIEW_PENDING: 'REVIEW_PENDING',
  COMPLETED: 'COMPLETED',
  REJECTED: 'REJECTED',
};

interface TaskRow {
  id: string;
  video: Video;
  task: Task | undefined;
  rowStatus: RowStatus;
  videoName: string;
}

const PAGE_SIZE = 20;

function searchParamsToFilters(sp: URLSearchParams): TaskFilterValues {
  return {
    q: sp.get('q') ?? '',
    status: sp.get('status') ?? '',
    assigneeId: sp.get('assigneeId') ?? '',
    eventType: sp.get('eventType') ?? '',
  };
}

function filtersToSearchParams(f: TaskFilterValues): Record<string, string> {
  const out: Record<string, string> = {};
  if (f.q) out['q'] = f.q;
  if (f.status) out['status'] = f.status;
  if (f.assigneeId) out['assigneeId'] = f.assigneeId;
  if (f.eventType) out['eventType'] = f.eventType;
  return out;
}

/**
 * SCR-TASK-001 작업 목록 (mock 정합 V1.x).
 *
 * 레이아웃:
 *   - 헤더: 제목 + 청록 역할 뱃지 + 부제 + 새로고침
 *   - 검색폼 (영상명/작업자명 + 이벤트 + 상태 + (REVIEWER) 작업자)
 *   - KPI 4카드
 *   - 다중 선택 일괄 배정 액션바
 *   - 테이블: (REVIEWER) 체크박스 + 영상명 + 이벤트 + 상태 + 작업자 + 검수자 + 액션
 *
 * V1.x 후속 반영:
 * - 처리 완료 영상이지만 task가 없는 경우도 노출 (left-join)
 * - 검수자 미등록 표시
 * - 이벤트 컬럼에 EventTypeBadge
 * - 다중 선택 → AssignModal bulk 모드
 * - 작업자 기본값 = 현재 사용자
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

  const [filters, setFilters] = useState<TaskFilterValues>(() =>
    searchParamsToFilters(searchParams),
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

  // 데이터 fetch — workerId 필터(URL)는 그대로 BE로 위임.
  // - WORKER 시각: BE 페이징(/assignments) 사용 (Phase 2 완료)
  // - REVIEWER 시각: Phase 3 에서 /v1/tasks/board BE 단일 엔드포인트 사용 — useVideos 의존 제거
  const taskParams = useMemo<TaskListParams>(() => {
    const workerIdParam = filters.assigneeId;
    return {
      page,
      size: PAGE_SIZE,
      workerId:
        workerIdParam && Number.isFinite(Number(workerIdParam))
          ? Number(workerIdParam)
          : undefined,
    };
  }, [filters.assigneeId, page]);

  const {
    data: tasksPage,
    isLoading: tasksLoading,
    error: tasksError,
    refetch: refetchTasks,
  } = useTasks(taskParams, { enabled: !isReviewer });

  // REVIEWER 통합 작업 목록 — BE /v1/tasks/board 페이징.
  // 처리 완료 영상 + (left-join) LABELER/REVIEWER 배정을 BE 가 enrich 한 결과를 그대로 사용한다.
  const boardParams = useMemo(
    () => ({ status: 'COMPLETED', page, size: PAGE_SIZE }),
    [page],
  );
  const {
    data: boardPage,
    isLoading: boardLoading,
    error: boardError,
    refetch: refetchBoard,
  } = useTaskBoard(boardParams, { enabled: isReviewer });

  const isLoading = isReviewer ? boardLoading : tasksLoading;
  const error = isReviewer ? boardError : tasksError;
  const refetch = useCallback(() => {
    if (isReviewer) refetchBoard();
    else refetchTasks();
  }, [isReviewer, refetchBoard, refetchTasks]);

  // /users 는 REVIEWER 전용 (BE @PreAuthorize). WORKER 화면에서는 호출 자체를 막아 403 스팸을 방지한다.
  const { data: workersPage } = useUsers(
    { role: Role.WORKER, size: 100 },
    { enabled: isReviewer },
  );
  const { data: reviewersPage } = useUsers(
    { role: Role.REVIEWER, size: 100 },
    { enabled: isReviewer },
  );

  const tasks = tasksPage?.content ?? [];
  const boardItems: TaskBoardItem[] = boardPage?.content ?? [];
  const workers = workersPage?.content
    ? workersPage.content.map((u) => ({
        id: u.id,
        name: u.name,
        active: u.active,
      }))
    : [];

  // 검수자 ID → 이름 매핑 (TaskBoardItem.reviewerName 이 비어있을 때 보조 폴백)
  const reviewerMap = useMemo(() => {
    const map: Record<number, string> = {};
    (reviewersPage?.content ?? []).forEach((r) => {
      map[r.id] = r.name;
    });
    return map;
  }, [reviewersPage]);

  // 이벤트 유형 옵션 (REVIEWER: board items / WORKER: tasks 의 eventName 에서 unique 수집).
  const eventTypeOptions = useMemo(() => {
    const set = new Set<string>();
    if (isReviewer) {
      boardItems.forEach((it) => {
        if (it.eventName) set.add(it.eventName);
      });
    } else {
      tasks.forEach((t) => {
        if (t.eventName) set.add(t.eventName);
      });
    }
    return Array.from(set).sort();
  }, [isReviewer, boardItems, tasks]);

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
        },
        task: t,
        rowStatus: t.status,
        videoName: t.cctvName,
      }));
    }
    // REVIEWER — BE /v1/tasks/board 응답을 TaskRow 로 변환.
    return boardItems.map((it) => {
      const cctvName = it.cctvName ?? '';
      const hasAssignment =
        it.assignmentId != null && it.workerId != null;
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
      };
      return {
        id: String(it.videoId),
        video,
        task,
        rowStatus: it.status,
        videoName: cctvName,
      };
    });
  }, [isReviewer, boardItems, tasks]);

  const visibleRows = useMemo<TaskRow[]>(() => {
    let result = [...allRows];

    if (filters.q) {
      const q = filters.q.toLowerCase();
      result = result.filter(
        (r) =>
          r.videoName.toLowerCase().includes(q) ||
          (r.task?.workerName ?? '').toLowerCase().includes(q),
      );
    }

    if (filters.status) {
      result = result.filter((r) => r.rowStatus === filters.status);
    }

    if (filters.assigneeId) {
      const aid = Number(filters.assigneeId);
      result = result.filter((r) => r.task?.workerId === aid);
    }

    if (filters.eventType) {
      result = result.filter((r) => r.video.eventName === filters.eventType);
    }

    return result;
  }, [allRows, filters]);

  // KPI
  const kpi = useMemo(() => {
    const total = visibleRows.length;
    const inProgress = visibleRows.filter((r) => r.rowStatus === 'IN_PROGRESS')
      .length;
    const reviewPending = visibleRows.filter((r) => r.rowStatus === 'REVIEW_PENDING')
      .length;
    const rejected = visibleRows.filter((r) => r.rowStatus === 'REJECTED')
      .length;
    return { total, inProgress, reviewPending, rejected };
  }, [visibleRows]);

  // 페이징
  // - WORKER:    BE /assignments       totalPages 사용
  // - REVIEWER:  BE /v1/tasks/board     totalPages 사용
  // 클라이언트 슬라이싱은 사용하지 않는다. 클라이언트 필터(q/status/eventType/assigneeId) 는 현재 페이지 결과에만 적용된다.
  const totalPages = Math.max(
    1,
    (isReviewer ? boardPage?.totalPages : tasksPage?.totalPages) ?? 1,
  );
  const safePage = Math.min(page, totalPages - 1);
  const pagedRows = visibleRows;

  // 필터 변경 시 URL 동기화 + 페이지 리셋 + 선택 해제
  useEffect(() => {
    const params = filtersToSearchParams(filters);
    setSearchParams(params, { replace: true });
    setPage(0);
    setSelectedVideoIds(new Set());
  }, [filters, setSearchParams]);

  // 다중 선택 토글
  const toggleRow = useCallback((videoId: number) => {
    setSelectedVideoIds((prev) => {
      const next = new Set(prev);
      if (next.has(videoId)) next.delete(videoId);
      else next.add(videoId);
      return next;
    });
  }, []);

  const pagedVideoIds = useMemo(
    () => pagedRows.map((r) => r.video.id),
    [pagedRows],
  );
  const allPagedSelected =
    pagedVideoIds.length > 0 &&
    pagedVideoIds.every((id) => selectedVideoIds.has(id));
  const somePagedSelected = pagedVideoIds.some((id) =>
    selectedVideoIds.has(id),
  );

  const toggleAllPaged = () => {
    setSelectedVideoIds((prev) => {
      const next = new Set(prev);
      if (allPagedSelected) {
        pagedVideoIds.forEach((id) => next.delete(id));
      } else {
        pagedVideoIds.forEach((id) => next.add(id));
      }
      return next;
    });
  };

  const handleRefresh = () => {
    refetch();
    queryClient.invalidateQueries({ queryKey: ['assignments'] });
  };

  const openBulkAssign = () => {
    if (selectedVideoIds.size === 0) return;
    setSelectedTask(null);
    setAssignMode('bulk');
    setAssignModalOpen(true);
  };

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

  return (
    <div className="space-y-4">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-3">
          <h1 className="text-xl font-bold text-gray-900">작업 목록</h1>
          <span className="inline-flex items-center px-2 py-0.5 rounded-full text-xs font-semibold bg-cyan-100 text-cyan-700">
            현재 역할: {ROLE_LABEL[role] ?? role}
          </span>
          <span className="text-xs text-gray-500">
            처리 완료된 영상만 표시
          </span>
        </div>
        <Button variant="secondary" size="sm" onClick={handleRefresh}>
          <RefreshCw size={14} aria-hidden />
          새로고침
        </Button>
      </div>

      {/* 검색 폼 */}
      <TaskFilters
        values={filters}
        onChange={setFilters}
        onReset={() => setFilters(DEFAULT_TASK_FILTERS)}
        showAssigneeSelect={isReviewer}
        workers={workers}
        eventTypes={eventTypeOptions}
      />

      {error && <ErrorState title="작업 목록을 불러올 수 없습니다" />}

      {/* KPI 4카드 */}
      <div className="grid grid-cols-4 gap-4">
        <KpiCard
          label="전체 작업"
          value={kpi.total}
          icon={<ListTodo size={22} className="text-primary-600" aria-hidden />}
          iconBgClassName="bg-primary-50"
        />
        <KpiCard
          label="진행중"
          value={kpi.inProgress}
          icon={<Play size={22} className="text-green-600" aria-hidden />}
          iconBgClassName="bg-green-50"
        />
        <KpiCard
          label="검수대기"
          value={kpi.reviewPending}
          icon={<Flame size={22} className="text-yellow-600" aria-hidden />}
          iconBgClassName="bg-yellow-50"
        />
        <KpiCard
          label="반려"
          value={kpi.rejected}
          icon={<ArrowDown size={22} className="text-red-600" aria-hidden />}
          iconBgClassName="bg-red-50"
        />
      </div>

      {/* 일괄 배정 액션바 (REVIEWER만, 1건 이상 선택 시) */}
      {isReviewer && selectedVideoIds.size > 0 && (
        <div
          data-testid="bulk-assign-bar"
          className="flex items-center justify-between gap-3 rounded-lg border border-primary-200 bg-primary-50 px-4 py-3"
        >
          <div className="flex items-center gap-3">
            <span className="inline-flex items-center px-2 py-0.5 rounded-full text-xs font-semibold bg-blue-100 text-blue-700">
              {selectedVideoIds.size}개 선택됨
            </span>
            <button
              type="button"
              onClick={() => setSelectedVideoIds(new Set())}
              className="text-xs text-gray-500 underline hover:text-gray-700"
            >
              선택 해제
            </button>
          </div>
          <Button variant="primary" size="sm" onClick={openBulkAssign}>
            <Users size={14} aria-hidden />
            {selectedVideoIds.size}개 일괄 배정
          </Button>
        </div>
      )}

      {/* Table */}
      <div className="overflow-hidden rounded-lg border border-gray-200 bg-white shadow-sm">
        <div className="flex items-center gap-3 border-b border-gray-100 bg-gray-50 px-4 py-2">
          {isReviewer && pagedVideoIds.length > 0 && (
            <label className="flex cursor-pointer items-center gap-2 text-xs text-gray-600">
              <input
                type="checkbox"
                aria-label="현재 페이지 전체 선택"
                checked={allPagedSelected}
                ref={(el) => {
                  if (el) el.indeterminate = !allPagedSelected && somePagedSelected;
                }}
                onChange={toggleAllPaged}
                className="h-4 w-4 rounded border-gray-300 text-primary-600 focus:ring-primary-500"
              />
              현재 페이지 전체 선택
            </label>
          )}
          <span className="ml-auto text-xs text-gray-500">
            전체 {visibleRows.length}건
            {totalPages > 1 ? ` (${safePage + 1}/${totalPages} 페이지)` : ''}
          </span>
        </div>
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-gray-200 bg-gray-50">
                {isReviewer && (
                  <th className="w-10 px-4 py-3"></th>
                )}
                <th className="px-4 py-3 text-left text-xs font-semibold uppercase tracking-wide text-gray-500">
                  영상명
                </th>
                <th className="px-4 py-3 text-left text-xs font-semibold uppercase tracking-wide text-gray-500">
                  이벤트
                </th>
                <th className="px-4 py-3 text-left text-xs font-semibold uppercase tracking-wide text-gray-500">
                  상태
                </th>
                <th className="px-4 py-3 text-left text-xs font-semibold uppercase tracking-wide text-gray-500">
                  작업자
                </th>
                <th className="px-4 py-3 text-left text-xs font-semibold uppercase tracking-wide text-gray-500">
                  검수자
                </th>
                <th className="px-4 py-3 text-left text-xs font-semibold uppercase tracking-wide text-gray-500">
                  액션
                </th>
              </tr>
            </thead>
            <tbody>
              {isLoading ? (
                Array.from({ length: 5 }).map((_, i) => (
                  <tr key={i} className="border-b border-gray-100">
                    {Array.from({ length: isReviewer ? 7 : 6 }).map((__, j) => (
                      <td key={j} className="px-4 py-3">
                        <Skeleton height={16} />
                      </td>
                    ))}
                  </tr>
                ))
              ) : pagedRows.length === 0 ? (
                <tr>
                  <td
                    colSpan={isReviewer ? 7 : 6}
                    className="px-3 py-12"
                  >
                    <EmptyState message="배정된 작업이 없습니다" />
                  </td>
                </tr>
              ) : (
                pagedRows.map((r) => {
                  const checked = selectedVideoIds.has(r.video.id);
                  return (
                    <tr
                      key={r.id}
                      className={cn(
                        'border-b border-gray-100 transition-colors hover:bg-gray-50',
                      )}
                    >
                      {isReviewer && (
                        <td className="px-4 py-3">
                          <input
                            type="checkbox"
                            aria-label={`${r.videoName} 선택`}
                            checked={checked}
                            onChange={() => toggleRow(r.video.id)}
                            className="h-4 w-4 rounded border-gray-300 text-primary-600 focus:ring-primary-500"
                          />
                        </td>
                      )}
                      <td className="px-4 py-3">
                        <div className="min-w-[160px]">
                          <p className="truncate max-w-[200px] text-sm font-medium text-gray-800">
                            {r.videoName}
                          </p>
                          <p className="text-xs text-gray-400">{`video-${String(r.video.id).padStart(4, '0')}`}</p>
                        </div>
                      </td>
                      <td className="px-4 py-3">
                        {r.video.eventName ? (
                          <EventTypeBadge eventType={r.video.eventName} />
                        ) : (
                          <span className="text-xs text-gray-400">-</span>
                        )}
                      </td>
                      <td className="px-4 py-3">
                        <StatusBadge
                          status={STATUS_BADGE_MAP[r.rowStatus]}
                          label={STATUS_LABEL[r.rowStatus]}
                        />
                      </td>
                      <td className="px-4 py-3">
                        {r.task?.workerName ? (
                          <span className="text-sm text-gray-700">
                            {r.task.workerName}
                          </span>
                        ) : (
                          <span className="text-sm italic text-gray-400">
                            미배정
                          </span>
                        )}
                      </td>
                      <td className="px-4 py-3">
                        {r.task?.reviewerName ? (
                          <span className="text-sm text-gray-700">
                            {r.task.reviewerName}
                          </span>
                        ) : r.task?.reviewerId ? (
                          <span className="text-sm text-gray-700">
                            {reviewerMap[r.task.reviewerId] ??
                              `user #${r.task.reviewerId}`}
                          </span>
                        ) : (
                          <span className="text-sm italic text-gray-400">
                            미등록
                          </span>
                        )}
                      </td>
                      <td className="px-4 py-3">
                        <div
                          className="flex flex-nowrap gap-1"
                          onClick={(e) => e.stopPropagation()}
                        >
                          {/* 배정 / 재배정 — REVIEWER (mock 정합: ghost 텍스트 버튼).
                              COMPLETED(검수 승인 완료) 행은 재배정 불가 — 버튼 자체를 가린다.
                              BE 가드(ASSIGNMENT_ALREADY_COMPLETED)와 짝을 이루는 UI 정합. */}
                          {isReviewer && !(r.task?.workerId && r.rowStatus === 'COMPLETED') && (
                            <Button
                              variant="ghost"
                              size="sm"
                              onClick={() => {
                                if (r.task?.workerId) {
                                  // 기존 배정 — 재배정 모드
                                  setSelectedTask(r.task);
                                  setSelectedVideoForAssign(null);
                                  setAssignMode('reassign');
                                } else {
                                  // 미배정 영상 — 단건 신규 배정 모드 (videoId 전달)
                                  setSelectedTask(null);
                                  setSelectedVideoForAssign({
                                    id: r.video.id,
                                    name: r.videoName,
                                  });
                                  setAssignMode('assign');
                                }
                                setAssignModalOpen(true);
                              }}
                            >
                              {r.task?.workerId ? (
                                <RefreshCw size={14} aria-hidden />
                              ) : (
                                <UserPlus size={14} aria-hidden />
                              )}
                              {r.task?.workerId ? '재배정' : '배정'}
                            </Button>
                          )}
                          {/* 이력 — task가 있을 때만 (REVIEWER) */}
                          {isReviewer && r.task && (
                            <Button
                              variant="ghost"
                              size="sm"
                              onClick={() => {
                                setHistoryAssignmentId(r.task!.id);
                                setHistoryVideoName(r.videoName);
                                setHistoryDrawerOpen(true);
                              }}
                            >
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
                                  onClick={(e) => {
                                    e.stopPropagation();
                                    navigate(`/label/${r.task!.firstSrcSn}`);
                                  }}
                                  aria-label={`작업 시작 ${r.videoName}`}
                                >
                                  <Play size={14} aria-hidden /> 작업
                                </Button>
                              ) : (
                                <Button
                                  size="sm"
                                  variant="ghost"
                                  onClick={(e) => {
                                    e.stopPropagation();
                                    navigate(`/marking/${r.task!.videoId}`);
                                  }}
                                  aria-label={`마킹 시작 ${r.videoName}`}
                                >
                                  <ListTodo size={14} aria-hidden /> 마킹
                                </Button>
                              )}
                              <Button
                                size="sm"
                                variant="ghost"
                                onClick={(e) => {
                                  e.stopPropagation();
                                  setHistoryAssignmentId(r.task!.id);
                                  setHistoryVideoName(r.videoName);
                                  setHistoryDrawerOpen(true);
                                }}
                                aria-label="배정 이력 보기"
                              >
                                <History size={14} aria-hidden /> 이력
                              </Button>
                            </>
                          )}
                        </div>
                      </td>
                    </tr>
                  );
                })
              )}
            </tbody>
          </table>
        </div>
      </div>

      {/* Pagination */}
      {totalPages > 1 && (
        <div className="mt-4 flex items-center justify-center gap-1">
          <button
            type="button"
            onClick={() => setPage(safePage - 1)}
            disabled={safePage === 0}
            aria-label="이전 페이지"
            className="inline-flex h-8 w-8 items-center justify-center rounded-md text-gray-500 transition-colors hover:bg-gray-100 disabled:cursor-not-allowed disabled:opacity-40"
          >
            <ChevronLeft size={16} aria-hidden />
          </button>
          {Array.from({ length: Math.min(totalPages, 7) }).map((_, i) => {
            const p = i;
            return (
              <button
                key={p}
                type="button"
                onClick={() => setPage(p)}
                aria-current={p === safePage ? 'page' : undefined}
                className={cn(
                  'inline-flex h-8 w-8 items-center justify-center rounded-md text-sm font-medium transition-colors',
                  p === safePage
                    ? 'bg-primary-600 text-white'
                    : 'text-gray-600 hover:bg-gray-100',
                )}
              >
                {p + 1}
              </button>
            );
          })}
          <button
            type="button"
            onClick={() => setPage(safePage + 1)}
            disabled={safePage >= totalPages - 1}
            aria-label="다음 페이지"
            className="inline-flex h-8 w-8 items-center justify-center rounded-md text-gray-500 transition-colors hover:bg-gray-100 disabled:cursor-not-allowed disabled:opacity-40"
          >
            <ChevronRight size={16} aria-hidden />
          </button>
        </div>
      )}

      {/* Assign Modal (assign / reassign / bulk) */}
      <AssignModal
        open={assignModalOpen}
        onClose={() => setAssignModalOpen(false)}
        task={selectedTask}
        mode={assignMode}
        onSuccess={() => {
          setAssignModalOpen(false);
          setSelectedVideoForAssign(null);
          setSelectedVideoIds(new Set());
          refetch();
          queryClient.invalidateQueries({ queryKey: ['assignments'] });
        }}
        onBulkSuccess={() => {
          setAssignModalOpen(false);
          setSelectedVideoForAssign(null);
          setSelectedVideoIds(new Set());
          refetch();
          queryClient.invalidateQueries({ queryKey: ['assignments'] });
        }}
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
