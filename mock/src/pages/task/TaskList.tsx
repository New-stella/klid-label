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
  Users,
} from 'lucide-react';
import { Button } from '../../components/ui/Button';
import { Badge } from '../../components/ui/Badge';
import { Table } from '../../components/ui/Table';
import { StatCard } from '../../components/ui/StatCard';
import { Pagination } from '../../components/ui/Pagination';
import { ProgressBar } from '../../components/ui/ProgressBar';
import { AssignModal } from '../../components/task/AssignModal';
import { AssignmentHistory } from '../../components/task/AssignmentHistory';
import { EventTypeBadge } from '../../components/batch/EventTypeBadge';
import { TaskFilters, DEFAULT_TASK_FILTERS } from './TaskFilters';
import type { TaskFilterValues } from './TaskFilters';
import { useFetch } from '../../api/queries';
import type {
  TaskDto,
  UserDto,
  Page,
  VideoDto,
  BulkAssignResponse,
} from '../../api/types';
import { useSessionStore } from '../../store/sessionStore';
import { ROLE_LABEL } from '../../types/role';
import { useToast } from '../../components/common/Toast';
import type { ColumnDef } from '../../components/ui/Table';

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

type TaskStatus = TaskDto['status'];

/**
 * 행 상태 — task가 있으면 task.status, 없으면 'UNASSIGNED'(미배정).
 * 처리는 완료되었지만 작업 자체가 아직 만들어지지 않은 영상을 표현한다.
 */
type RowStatus = TaskStatus | 'UNASSIGNED';

const STATUS_LABEL: Record<RowStatus, string> = {
  UNASSIGNED: '미배정',
  PENDING: '대기',
  IN_PROGRESS: '진행중',
  REVIEW_PENDING: '검수대기',
  COMPLETED: '완료',
  REJECTED: '반려',
};

const STATUS_TONE: Record<RowStatus, 'neutral' | 'info' | 'warning' | 'success' | 'danger'> = {
  UNASSIGNED: 'neutral',
  PENDING: 'neutral',
  IN_PROGRESS: 'info',
  REVIEW_PENDING: 'warning',
  COMPLETED: 'success',
  REJECTED: 'danger',
};

/**
 * 작업목록 행 모델 — 처리 완료된 영상이 base이고 task는 left-join.
 * 같은 행에서 task 미생성 영상도 표현된다.
 */
interface TaskRow {
  id: string; // = video.id (행 식별자)
  video: VideoDto;
  task: TaskDto | undefined;
  rowStatus: RowStatus;
  /** 정렬·검색용 — task 있으면 task.videoName, 없으면 video.cctvName */
  videoName: string;
}

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

const PAGE_SIZE = 20;

/**
 * 처리 완료 영상 판정 — STAGES 배열의 모든 단계가 'DONE'이면 처리 완료로 간주.
 * batchStatus === 'COMPLETED'와 동치이지만 Phase 1의 진행도 표현(STAGES)에 맞춰 stages 기준으로 명시 검사.
 */
function isVideoFullyProcessed(video: VideoDto): boolean {
  if (!video.stages || video.stages.length === 0) return false;
  return video.stages.every((s) => s.status === 'DONE');
}

// ---------------------------------------------------------------------------
// Component
// ---------------------------------------------------------------------------

export function TaskList() {
  const [searchParams, setSearchParams] = useSearchParams();
  const { currentRole, currentUser } = useSessionStore();
  const { showToast } = useToast();

  const [page, setPage] = useState(0);
  const [filters, setFilters] = useState<TaskFilterValues>(() =>
    searchParamsToFilters(searchParams),
  );

  // Modal & history state
  const [assignModalOpen, setAssignModalOpen] = useState(false);
  const [assignMode, setAssignMode] = useState<'assign' | 'reassign' | 'bulk'>('assign');
  const [selectedTask, setSelectedTask] = useState<TaskDto | null>(null);
  const [historyOpen, setHistoryOpen] = useState(false);
  const [historyTask, setHistoryTask] = useState<TaskDto | null>(null);

  // 선택된 영상 ID 집합 (일괄 배정용)
  const [selectedVideoIds, setSelectedVideoIds] = useState<Set<string>>(new Set());

  // Fetch all tasks (we filter on FE for role-based visibility)
  const { data: rawData, isLoading, refetch } = useFetch<Page<TaskDto>>('/tasks', {
    page: 0,
    size: 999, // fetch all, filter in FE
  });

  // Fetch all videos for completion status & eventType lookup
  const { data: videosData } = useFetch<Page<VideoDto>>('/videos', { page: 0, size: 999 });
  const videos = videosData?.content ?? [];

  // 이벤트 유형 옵션 (unique, 정렬)
  const eventTypeOptions = useMemo(() => {
    const set = new Set<string>();
    videos.forEach((v) => {
      if (v.eventType) set.add(v.eventType);
    });
    return Array.from(set).sort();
  }, [videos]);

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
  // Build rows: video-based (left-join task)
  // ---------------------------------------------------------------------------
  // videoId → 매칭 task 매핑 (한 영상당 첫 번째 task를 선택)
  const taskByVideoId = useMemo(() => {
    const map = new Map<string, TaskDto>();
    for (const t of localTasks) {
      if (!map.has(t.videoId)) map.set(t.videoId, t);
    }
    return map;
  }, [localTasks]);

  // 처리 완료된 영상 전체를 base 행으로 — task가 없으면 미배정 행으로 표시
  const processedVideos = useMemo(
    () => videos.filter(isVideoFullyProcessed),
    [videos],
  );

  const allRows = useMemo<TaskRow[]>(() => {
    return processedVideos.map((v) => {
      const task = taskByVideoId.get(v.id);
      return {
        id: v.id,
        video: v,
        task,
        rowStatus: task ? task.status : 'UNASSIGNED',
        videoName: task?.videoName ?? v.cctvName,
      };
    });
  }, [processedVideos, taskByVideoId]);

  const visibleRows = useMemo<TaskRow[]>(() => {
    let result = [...allRows];

    // Role-based visibility
    if (currentRole === 'WORKER') {
      // WORKER는 본인이 배정된 task만
      result = result.filter((r) => r.task?.assigneeId === currentUser.id);
    } else if (currentRole === 'REVIEWER') {
      // REVIEWER는 본인이 검수자인 task + 미배정 영상 + assignee 없는 task
      result = result.filter(
        (r) => !r.task || r.task.reviewerId === currentUser.id || !r.task.assigneeId,
      );
    }

    // Text search (영상명 / 작업자명)
    if (filters.q) {
      const q = filters.q.toLowerCase();
      result = result.filter(
        (r) =>
          r.videoName.toLowerCase().includes(q) ||
          (r.task?.assigneeName ?? '').toLowerCase().includes(q),
      );
    }

    // Status filter (UNASSIGNED 포함)
    if (filters.status) {
      result = result.filter((r) => r.rowStatus === filters.status);
    }

    // Assignee filter (REVIEWER only)
    if (filters.assigneeId) {
      result = result.filter((r) => r.task?.assigneeId === filters.assigneeId);
    }

    // EventType filter — 영상의 이벤트 유형 기준
    if (filters.eventType) {
      result = result.filter((r) => r.video.eventType === filters.eventType);
    }

    return result;
  }, [allRows, currentRole, currentUser.id, filters]);

  // KPI stats (on visible rows)
  const kpi = useMemo(() => {
    const total = visibleRows.length;
    const inProgress = visibleRows.filter((r) => r.rowStatus === 'IN_PROGRESS').length;
    const reviewPending = visibleRows.filter((r) => r.rowStatus === 'REVIEW_PENDING').length;
    const rejected = visibleRows.filter((r) => r.rowStatus === 'REJECTED').length;
    return { total, inProgress, reviewPending, rejected };
  }, [visibleRows]);

  // Paginate
  const totalPages = Math.max(1, Math.ceil(visibleRows.length / PAGE_SIZE));
  const pagedRows = useMemo(
    () => visibleRows.slice(page * PAGE_SIZE, (page + 1) * PAGE_SIZE),
    [visibleRows, page],
  );

  // 현재 보이는 페이지의 영상ID 집합 (전체 선택 토글에 사용)
  const pagedVideoIds = useMemo(() => pagedRows.map((r) => r.video.id), [pagedRows]);
  const allPagedSelected =
    pagedVideoIds.length > 0 && pagedVideoIds.every((vid) => selectedVideoIds.has(vid));
  const somePagedSelected = pagedVideoIds.some((vid) => selectedVideoIds.has(vid));

  // 필터 변경 시 선택 초기화
  useEffect(() => {
    setSelectedVideoIds(new Set());
  }, [filters]);

  const handleFilterChange = useCallback((v: TaskFilterValues) => {
    setFilters(v);
  }, []);

  const handleReset = useCallback(() => {
    setFilters(DEFAULT_TASK_FILTERS);
  }, []);

  // task 또는 video 기반으로 단일 배정 모달 열기.
  // task가 없으면 미배정 영상 → bulk 모드로 단일 영상만 전달하여 신규 생성 흐름에 합류.
  const openAssign = useCallback(
    (row: TaskRow, mode: 'assign' | 'reassign') => {
      if (row.task) {
        setSelectedTask(row.task);
        setAssignMode(mode);
        setAssignModalOpen(true);
      } else {
        // 미배정 영상 — 단일 영상에 대한 신규 task 생성. bulk 모드(1건)로 처리.
        setSelectedTask(null);
        setSelectedVideoIds(new Set([row.video.id]));
        setAssignMode('bulk');
        setAssignModalOpen(true);
      }
    },
    [],
  );

  const openHistory = useCallback((task: TaskDto) => {
    setHistoryTask(task);
    setHistoryOpen(true);
  }, []);

  const openBulkAssign = useCallback(() => {
    if (selectedVideoIds.size === 0) return;
    setSelectedTask(null);
    setAssignMode('bulk');
    setAssignModalOpen(true);
  }, [selectedVideoIds.size]);

  const handleAssignSuccess = useCallback((updated: TaskDto) => {
    setLocalTasks((prev) => {
      const exists = prev.some((t) => t.id === updated.id);
      if (exists) return prev.map((t) => (t.id === updated.id ? updated : t));
      // 신규 생성된 task인 경우 append
      return [...prev, updated];
    });
  }, []);

  const handleBulkAssignSuccess = useCallback(
    (result: BulkAssignResponse) => {
      // 응답에 포함된 tasks를 id 기준으로 upsert (신규 task는 append, 기존은 갱신).
      setLocalTasks((prev) => {
        const next = [...prev];
        for (const t of result.tasks) {
          const idx = next.findIndex((x) => x.id === t.id);
          if (idx >= 0) next[idx] = t;
          else next.push(t);
        }
        return next;
      });
      const totalChanged = result.assigned + result.skipped;
      showToast(`${totalChanged}건 일괄 배정 완료`, 'success');
      setSelectedVideoIds(new Set());
    },
    [showToast],
  );

  // 행 체크박스 토글
  const toggleRow = useCallback((videoId: string) => {
    setSelectedVideoIds((prev) => {
      const next = new Set(prev);
      if (next.has(videoId)) next.delete(videoId);
      else next.add(videoId);
      return next;
    });
  }, []);

  // 헤더 전체 선택 토글 (현재 페이지 기준)
  const toggleAllPaged = useCallback(() => {
    setSelectedVideoIds((prev) => {
      const next = new Set(prev);
      if (allPagedSelected) {
        pagedVideoIds.forEach((vid) => next.delete(vid));
      } else {
        pagedVideoIds.forEach((vid) => next.add(vid));
      }
      return next;
    });
  }, [allPagedSelected, pagedVideoIds]);

  // 영상명 매핑 (선택된 영상 표시용) — task에 등록된 이름 우선, 없으면 video.cctvName
  const videoNameById = useMemo(() => {
    const map: Record<string, string> = {};
    videos.forEach((v) => {
      map[v.id] = v.cctvName;
    });
    localTasks.forEach((t) => {
      map[t.videoId] = t.videoName;
    });
    return map;
  }, [localTasks, videos]);

  // ---------------------------------------------------------------------------
  // Table columns
  // ---------------------------------------------------------------------------
  const showAssignBtn = currentRole === 'REVIEWER';
  const showReassignBtn = currentRole === 'REVIEWER';
  const showWorkerFilter = currentRole === 'REVIEWER';
  const showCheckbox = currentRole === 'REVIEWER';

  const columns: ColumnDef<TaskRow>[] = [];

  if (showCheckbox) {
    columns.push({
      key: '_select',
      header: '',
      width: '40px',
      render: (row) => (
        <div onClick={(e) => e.stopPropagation()} className="flex items-center">
          <input
            type="checkbox"
            aria-label={`${row.videoName} 선택`}
            checked={selectedVideoIds.has(row.video.id)}
            onChange={() => toggleRow(row.video.id)}
            className="h-4 w-4 rounded border-gray-300 text-primary-600 focus:ring-primary-500"
          />
        </div>
      ),
    });
  }

  columns.push(
    {
      key: 'videoName',
      header: '영상명',
      render: (row) => (
        <div className="min-w-[160px]">
          <p className="font-medium text-gray-800 text-sm truncate max-w-[200px]">{row.videoName}</p>
          <p className="text-xs text-gray-400">{row.video.id}</p>
        </div>
      ),
    },
    {
      key: 'eventType',
      header: '이벤트',
      render: (row) => {
        const et = row.video.eventType;
        return et ? (
          <EventTypeBadge eventType={et} />
        ) : (
          <span className="text-xs text-gray-400">-</span>
        );
      },
    },
    {
      key: 'status',
      header: '상태',
      render: (row) => (
        <Badge tone={STATUS_TONE[row.rowStatus]} size="sm">
          {STATUS_LABEL[row.rowStatus]}
        </Badge>
      ),
    },
    {
      key: 'assigneeName',
      header: '작업자',
      render: (row) =>
        row.task?.assigneeName ? (
          <span className="text-sm text-gray-700">{row.task.assigneeName}</span>
        ) : (
          <span className="text-sm text-gray-400 italic">미배정</span>
        ),
    },
    {
      key: 'reviewerId',
      header: '검수자',
      render: (row) =>
        row.task?.reviewerId ? (
          <span className="text-sm text-gray-700">
            {reviewerMap[row.task.reviewerId] ?? row.task.reviewerId}
          </span>
        ) : (
          <span className="text-sm text-gray-400 italic">미등록</span>
        ),
    },
    {
      key: 'progress',
      header: '진행률',
      width: '100px',
      render: (row) =>
        row.task ? (
          <div className="flex items-center gap-2 min-w-[80px]">
            <ProgressBar value={row.task.progress} size="sm" className="flex-1" />
            <span className="text-xs text-gray-500 tabular-nums w-8 text-right">
              {row.task.progress}%
            </span>
          </div>
        ) : (
          <span className="text-xs text-gray-400">-</span>
        ),
    },
    {
      key: '_actions',
      header: '액션',
      render: (row) => (
        <div className="flex gap-1 flex-nowrap" onClick={(e) => e.stopPropagation()}>
          {/* 작업▶ — assignee (WORKER) only */}
          {currentRole === 'WORKER' && row.task && (
            <Button
              variant="ghost"
              size="sm"
              leftIcon={Play}
              onClick={() => alert(`라벨링 작업 화면으로 이동: ${row.task?.id}\n(Phase 6에서 구현)`)}
            >
              작업
            </Button>
          )}
          {/* 배정 — REVIEWER: task가 없거나 PENDING 상태일 때 */}
          {showAssignBtn && (!row.task || row.task.status === 'PENDING') && (
            <Button
              variant="secondary"
              size="sm"
              leftIcon={UserPlus}
              onClick={() => openAssign(row, 'assign')}
            >
              배정
            </Button>
          )}
          {/* 재배정 — REVIEWER: 이미 작업자가 있는 task에만 */}
          {showReassignBtn && row.task?.assigneeId && (
            <Button
              variant="secondary"
              size="sm"
              leftIcon={RefreshCw}
              onClick={() => openAssign(row, 'reassign')}
            >
              재배정
            </Button>
          )}
          {/* 이력 — task가 있을 때만 */}
          {row.task && (
            <Button
              variant="ghost"
              size="sm"
              leftIcon={History}
              onClick={() => row.task && openHistory(row.task)}
            >
              이력
            </Button>
          )}
        </div>
      ),
    },
  );

  const selectedCount = selectedVideoIds.size;
  const selectedVideoIdList = useMemo(() => Array.from(selectedVideoIds), [selectedVideoIds]);

  return (
    <div className="space-y-4">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-3">
          <h1 className="text-xl font-bold text-gray-900">작업 목록</h1>
          <Badge tone="info" size="sm">현재 역할: {ROLE_LABEL[currentRole]}</Badge>
          <span className="text-xs text-gray-500">처리 완료된 영상만 표시</span>
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
        eventTypes={eventTypeOptions}
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

      {/* Bulk action bar — 1개 이상 선택 시 노출 */}
      {showCheckbox && selectedCount >= 1 && (
        <div className="bg-primary-50 border border-primary-200 rounded-lg px-4 py-3 flex items-center justify-between">
          <div className="flex items-center gap-3">
            <Badge tone="info" size="sm">{selectedCount}개 선택됨</Badge>
            <button
              type="button"
              onClick={() => setSelectedVideoIds(new Set())}
              className="text-xs text-gray-500 hover:text-gray-700 underline"
            >
              선택 해제
            </button>
          </div>
          <Button variant="primary" size="sm" leftIcon={Users} onClick={openBulkAssign}>
            {selectedCount}개 일괄 배정
          </Button>
        </div>
      )}

      {/* Table */}
      <div className="bg-white border border-gray-200 rounded-lg shadow-sm overflow-hidden">
        <div className="flex items-center px-4 py-2 border-b border-gray-100 bg-gray-50 gap-3">
          {showCheckbox && pagedVideoIds.length > 0 && (
            <label className="flex items-center gap-2 text-xs text-gray-600 cursor-pointer">
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
          <span className="text-xs text-gray-500 ml-auto">
            전체 {visibleRows.length}건
            {totalPages > 1 ? ` (${page + 1}/${totalPages} 페이지)` : ''}
          </span>
        </div>
        <Table<TaskRow>
          columns={columns}
          rows={pagedRows}
          rowKey={(r) => r.id}
          loading={isLoading}
          emptyMessage="처리 완료된 영상이 없습니다."
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
        onBulkSuccess={handleBulkAssignSuccess}
        videoIds={assignMode === 'bulk' ? selectedVideoIdList : []}
        videoNameById={videoNameById}
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
