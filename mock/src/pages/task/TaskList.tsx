import { useState, useCallback, useEffect, useMemo } from 'react';
import { useSearchParams } from 'react-router-dom';
import {
  ListTodo,
  Play,
  Flame,
  ArrowDown,
  UserPlus,
  RefreshCw,
  History,
} from 'lucide-react';
import { Button } from '../../components/ui/Button';
import { Badge } from '../../components/ui/Badge';
import { Table } from '../../components/ui/Table';
import { StatCard } from '../../components/ui/StatCard';
import { Pagination } from '../../components/ui/Pagination';
import { ProgressBar } from '../../components/ui/ProgressBar';
import { AssignModal } from '../../components/task/AssignModal';
import { AssignmentHistory } from '../../components/task/AssignmentHistory';
import { TaskFilters, DEFAULT_TASK_FILTERS } from './TaskFilters';
import type { TaskFilterValues } from './TaskFilters';
import { useFetch } from '../../api/queries';
import type { TaskDto, UserDto, Page } from '../../api/types';
import { useSessionStore } from '../../store/sessionStore';
import { ROLE_LABEL } from '../../types/role';
import type { ColumnDef } from '../../components/ui/Table';

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

type TaskStatus = TaskDto['status'];

const STATUS_LABEL: Record<TaskStatus, string> = {
  PENDING: '대기',
  IN_PROGRESS: '진행중',
  REVIEW_PENDING: '검수대기',
  COMPLETED: '완료',
  REJECTED: '반려',
};

const STATUS_TONE: Record<TaskStatus, 'neutral' | 'info' | 'warning' | 'success' | 'danger'> = {
  PENDING: 'neutral',
  IN_PROGRESS: 'info',
  REVIEW_PENDING: 'warning',
  COMPLETED: 'success',
  REJECTED: 'danger',
};

function searchParamsToFilters(sp: URLSearchParams): TaskFilterValues {
  return {
    q: sp.get('q') ?? '',
    status: sp.get('status') ?? '',
    assigneeId: sp.get('assigneeId') ?? '',
  };
}

function filtersToSearchParams(f: TaskFilterValues): Record<string, string> {
  const out: Record<string, string> = {};
  if (f.q) out['q'] = f.q;
  if (f.status) out['status'] = f.status;
  if (f.assigneeId) out['assigneeId'] = f.assigneeId;
  return out;
}

const PAGE_SIZE = 20;

// ---------------------------------------------------------------------------
// Component
// ---------------------------------------------------------------------------

export function TaskList() {
  const [searchParams, setSearchParams] = useSearchParams();
  const { currentRole, currentUser } = useSessionStore();

  const [page, setPage] = useState(0);
  const [filters, setFilters] = useState<TaskFilterValues>(() =>
    searchParamsToFilters(searchParams),
  );

  // Modal & history state
  const [assignModalOpen, setAssignModalOpen] = useState(false);
  const [assignMode, setAssignMode] = useState<'assign' | 'reassign'>('assign');
  const [selectedTask, setSelectedTask] = useState<TaskDto | null>(null);
  const [historyOpen, setHistoryOpen] = useState(false);
  const [historyTask, setHistoryTask] = useState<TaskDto | null>(null);

  // Fetch all tasks (we filter on FE for role-based visibility)
  const { data: rawData, isLoading, refetch } = useFetch<Page<TaskDto>>('/tasks', {
    page: 0,
    size: 999, // fetch all, filter in FE
  });

  // Fetch workers for filter dropdown
  const { data: workersData } = useFetch<Page<UserDto>>('/users', { role: 'WORKER', size: 50 });
  const workers = workersData?.content ?? [];

  // Fetch reviewers for reviewer name lookup
  const { data: reviewersData } = useFetch<Page<UserDto>>('/users', { role: 'REVIEWER', size: 50 });
  const reviewers = reviewersData?.content ?? [];
  const reviewerMap = useMemo(
    () => Object.fromEntries(reviewers.map((r) => [r.id, r.name])),
    [reviewers],
  );

  // Local task state to reflect optimistic updates after assign
  const [localTasks, setLocalTasks] = useState<TaskDto[]>([]);

  useEffect(() => {
    if (rawData?.content) {
      setLocalTasks(rawData.content);
    }
  }, [rawData]);

  // Sync filters → URL
  useEffect(() => {
    const params = filtersToSearchParams(filters);
    setSearchParams(params, { replace: true });
    setPage(0);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [filters]);

  // ---------------------------------------------------------------------------
  // Role-based visibility + filter
  // ---------------------------------------------------------------------------
  const visibleTasks = useMemo<TaskDto[]>(() => {
    let result = [...localTasks];

    // Role-based visibility
    if (currentRole === 'WORKER') {
      result = result.filter((t) => t.assigneeId === currentUser.id);
    } else if (currentRole === 'REVIEWER') {
      // REVIEWER sees tasks they review + unassigned tasks
      result = result.filter(
        (t) => t.reviewerId === currentUser.id || !t.assigneeId,
      );
    }

    // Text search (영상명 / 작업자명)
    if (filters.q) {
      const q = filters.q.toLowerCase();
      result = result.filter(
        (t) =>
          t.videoName.toLowerCase().includes(q) ||
          (t.assigneeName ?? '').toLowerCase().includes(q),
      );
    }

    // Status filter
    if (filters.status) {
      result = result.filter((t) => t.status === filters.status);
    }

    // Assignee filter (REVIEWER only)
    if (filters.assigneeId) {
      result = result.filter((t) => t.assigneeId === filters.assigneeId);
    }

    return result;
  }, [localTasks, currentRole, currentUser.id, filters]);

  // KPI stats (on visible tasks)
  const kpi = useMemo(() => {
    const total = visibleTasks.length;
    const inProgress = visibleTasks.filter((t) => t.status === 'IN_PROGRESS').length;
    const reviewPending = visibleTasks.filter((t) => t.status === 'REVIEW_PENDING').length;
    const rejected = visibleTasks.filter((t) => t.status === 'REJECTED').length;
    return { total, inProgress, reviewPending, rejected };
  }, [visibleTasks]);

  // Paginate
  const totalPages = Math.max(1, Math.ceil(visibleTasks.length / PAGE_SIZE));
  const pagedTasks = useMemo(
    () => visibleTasks.slice(page * PAGE_SIZE, (page + 1) * PAGE_SIZE),
    [visibleTasks, page],
  );

  const handleFilterChange = useCallback((v: TaskFilterValues) => {
    setFilters(v);
  }, []);

  const handleReset = useCallback(() => {
    setFilters(DEFAULT_TASK_FILTERS);
  }, []);

  const openAssign = useCallback((task: TaskDto, mode: 'assign' | 'reassign') => {
    setSelectedTask(task);
    setAssignMode(mode);
    setAssignModalOpen(true);
  }, []);

  const openHistory = useCallback((task: TaskDto) => {
    setHistoryTask(task);
    setHistoryOpen(true);
  }, []);

  const handleAssignSuccess = useCallback((updated: TaskDto) => {
    setLocalTasks((prev) => prev.map((t) => (t.id === updated.id ? updated : t)));
  }, []);

  // ---------------------------------------------------------------------------
  // Table columns
  // ---------------------------------------------------------------------------
  const showAssignBtn = currentRole === 'REVIEWER';
  const showReassignBtn = currentRole === 'REVIEWER';
  const showWorkerFilter = currentRole === 'REVIEWER';

  const columns: ColumnDef<TaskDto>[] = [
    {
      key: 'videoName',
      header: '영상명',
      render: (row) => (
        <div className="min-w-[160px]">
          <p className="font-medium text-gray-800 text-sm truncate max-w-[200px]">{row.videoName}</p>
          <p className="text-xs text-gray-400">{row.videoId}</p>
        </div>
      ),
    },
    {
      key: 'status',
      header: '상태',
      render: (row) => (
        <Badge tone={STATUS_TONE[row.status]} size="sm">
          {STATUS_LABEL[row.status]}
        </Badge>
      ),
    },
    {
      key: 'assigneeName',
      header: '작업자',
      render: (row) => (
        <span className="text-sm text-gray-700">
          {row.assigneeName ?? <span className="text-gray-400 italic">미배정</span>}
        </span>
      ),
    },
    {
      key: 'reviewerId',
      header: '검수자',
      render: (row) => (
        <span className="text-sm text-gray-500">
          {row.reviewerId ? (
            <span className="text-gray-700">{reviewerMap[row.reviewerId] ?? row.reviewerId}</span>
          ) : (
            <span className="text-gray-400 italic">-</span>
          )}
        </span>
      ),
    },
    {
      key: 'progress',
      header: '진행률',
      width: '100px',
      render: (row) => (
        <div className="flex items-center gap-2 min-w-[80px]">
          <ProgressBar value={row.progress} size="sm" className="flex-1" />
          <span className="text-xs text-gray-500 tabular-nums w-8 text-right">{row.progress}%</span>
        </div>
      ),
    },
    {
      key: '_actions',
      header: '액션',
      render: (row) => (
        <div className="flex gap-1 flex-nowrap" onClick={(e) => e.stopPropagation()}>
          {/* 작업▶ — assignee (WORKER) only */}
          {currentRole === 'WORKER' && (
            <Button
              variant="ghost"
              size="sm"
              leftIcon={Play}
              onClick={() => alert(`라벨링 작업 화면으로 이동: ${row.id}\n(Phase 6에서 구현)`)}
            >
              작업
            </Button>
          )}
          {/* 배정 — REVIEWER */}
          {showAssignBtn && row.status === 'PENDING' && (
            <Button
              variant="secondary"
              size="sm"
              leftIcon={UserPlus}
              onClick={() => openAssign(row, 'assign')}
            >
              배정
            </Button>
          )}
          {/* 재배정 — REVIEWER */}
          {showReassignBtn && row.assigneeId && (
            <Button
              variant="secondary"
              size="sm"
              leftIcon={RefreshCw}
              onClick={() => openAssign(row, 'reassign')}
            >
              재배정
            </Button>
          )}
          {/* 이력 */}
          <Button
            variant="ghost"
            size="sm"
            leftIcon={History}
            onClick={() => openHistory(row)}
          >
            이력
          </Button>
        </div>
      ),
    },
  ];

  return (
    <div className="space-y-4">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-3">
          <h1 className="text-xl font-bold text-gray-900">작업 목록</h1>
          <Badge tone="info" size="sm">현재 역할: {ROLE_LABEL[currentRole]}</Badge>
        </div>
        <Button variant="secondary" size="sm" leftIcon={RefreshCw} onClick={() => refetch()}>
          새로고침
        </Button>
      </div>

      {/* Filters */}
      <TaskFilters
        values={filters}
        onChange={handleFilterChange}
        onReset={handleReset}
        showAssigneeSelect={showWorkerFilter}
        workers={workers}
      />

      {/* KPI cards */}
      <div className="grid grid-cols-2 gap-4 sm:grid-cols-4">
        <StatCard
          label="전체 작업"
          value={kpi.total}
          icon={ListTodo}
          tone="primary"
        />
        <StatCard
          label="진행중"
          value={kpi.inProgress}
          icon={Play}
          tone="success"
        />
        <StatCard
          label="검수대기"
          value={kpi.reviewPending}
          icon={Flame}
          tone="warning"
        />
        <StatCard
          label="반려"
          value={kpi.rejected}
          icon={ArrowDown}
          tone="danger"
        />
      </div>

      {/* Table */}
      <div className="bg-white border border-gray-200 rounded-lg shadow-sm overflow-hidden">
        <div className="flex items-center px-4 py-2 border-b border-gray-100 bg-gray-50">
          <span className="text-xs text-gray-500">
            전체 {visibleTasks.length}건
            {totalPages > 1 ? ` (${page + 1}/${totalPages} 페이지)` : ''}
          </span>
        </div>
        <Table<TaskDto>
          columns={columns}
          rows={pagedTasks}
          rowKey={(r) => r.id}
          loading={isLoading}
          emptyMessage="해당하는 작업이 없습니다."
        />
      </div>

      {/* Pagination */}
      <Pagination
        page={page}
        totalPages={totalPages}
        onChange={(p) => setPage(p)}
      />

      {/* Assign Modal */}
      <AssignModal
        open={assignModalOpen}
        onClose={() => setAssignModalOpen(false)}
        task={selectedTask}
        mode={assignMode}
        onSuccess={handleAssignSuccess}
      />

      {/* Assignment History side panel */}
      <AssignmentHistory
        task={historyTask}
        open={historyOpen}
        onClose={() => setHistoryOpen(false)}
      />
    </div>
  );
}

export default TaskList;
