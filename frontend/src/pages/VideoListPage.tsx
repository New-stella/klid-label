import { useMemo, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { ChevronLeft, ChevronRight, RefreshCw, Sparkles, Users } from 'lucide-react';
import { useQueryClient } from '@tanstack/react-query';

import { Button } from '@/components/common/Button';
import { EmptyState } from '@/components/common/EmptyState';
import { ErrorState } from '@/components/common/ErrorState';
import { EventTypeBadge } from '@/components/common/EventTypeBadge';
import { Skeleton } from '@/components/common/Skeleton';
import { StageBadge } from '@/components/common/StageBadge';
import { StatusBadge } from '@/components/common/StatusBadge';
import { cn } from '@/lib/cn';
import { AssignModal } from '@/features/task/components/AssignModal';
import type { Task } from '@/features/task/types';
import { MarkingModal } from '@/features/marking/components/MarkingModal';
import { canMark } from '@/features/marking/markingEligibility';
import { VideoFilters } from '@/features/video/components/VideoFilters';
import { useVideos } from '@/features/video/hooks/useVideos';
import {
  parseVideoListParams,
  videoListParamsToSearchParams,
} from '@/features/video/parseVideoListParams';
import type { Video, VideoListParams } from '@/features/video/types';
import { Role } from '@/lib/api/types';
import { ASSIGNMENT_KEYS, VIDEO_KEYS } from '@/lib/queryKeys';
import { useAuthStore } from '@/stores/useAuthStore';

function formatDuration(seconds: number | undefined): string {
  if (!seconds) return '-';
  if (seconds >= 3600) {
    const h = Math.floor(seconds / 3600);
    const m = Math.floor((seconds % 3600) / 60);
    return `${h}시간 ${m}분`;
  }
  const m = Math.floor(seconds / 60);
  const s = seconds % 60;
  return `${m}분 ${s}초`;
}

/**
 * SCR-VIDEO-001 영상 처리 현황 (mock 정합).
 *
 * 컬럼: checkbox / CCTV명 / 이벤트 / 녹화일 / 길이 / 처리단계 / 배정자 / 액션
 *
 * [req: R1] 개인정보 유무 컬럼 제거 — 관제서버가 개인정보 유무를 실제로 보내지 않고
 * (인입 원장 3필드 전부 NULL), 화면이 보던 privacyTypeCd 는 적재 시 고정되는 레거시 컬럼이다.
 * 응답 필드 매핑은 BE 계약 유지를 위해 그대로 두고 표시만 제거한다.
 */
export function VideoListPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const params = useMemo(() => parseVideoListParams(searchParams), [searchParams]);
  const { data, isLoading, isFetching, error, refetch } = useVideos(params);
  // 페이지/필터 전환 재조회(keepPreviousData) 중에는 이전 목록을 살짝 흐리게 해 갱신 중임을 알린다.
  // 초기 로딩(isLoading)은 스켈레톤이 담당하므로 제외한다.
  const refetching = isFetching && !isLoading;
  const [selected, setSelected] = useState<Set<number>>(new Set());

  // 작업자 배정 — REVIEWER 전용 UX 게이팅(실제 권한 강제는 BE @PreAuthorize).
  const claims = useAuthStore((s) => s.claims);
  const role = claims?.role ?? Role.WORKER;
  const isReviewer = role === Role.REVIEWER;

  // AssignModal 상태 (재배정 / 일괄 배정 재사용 — TaskListPage 정합).
  // 단건 신규 배정('assign')은 마킹 진입(MarkingModal 내부 AssignModal)으로 이관됨.
  const [assignModalOpen, setAssignModalOpen] = useState(false);
  const [assignMode, setAssignMode] = useState<'reassign' | 'bulk'>('reassign');
  // 기존 배정 영상 재배정용 — AssignModal 이 읽는 task 형태로 매핑해 전달한다.
  const [selectedTask, setSelectedTask] = useState<Task | null>(null);

  // 미배정 + 마킹 진입 가능 영상 — 마킹 진입 팝업(MarkingModal) 대상.
  const [markingTarget, setMarkingTarget] = useState<{
    rawSn: number;
    name: string;
  } | null>(null);

  const updateParams = (next: VideoListParams) => {
    const sp = videoListParamsToSearchParams({ ...params, ...next });
    setSearchParams(sp, { replace: false });
    setSelected(new Set());
  };

  const rows = data?.content ?? [];
  const allChecked = rows.length > 0 && rows.every((r) => selected.has(r.id));
  const toggleAll = () => {
    if (allChecked) setSelected(new Set());
    else setSelected(new Set(rows.map((r) => r.id)));
  };
  const toggleRow = (id: number) => {
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  };

  const handleRefresh = () => {
    refetch();
    queryClient.invalidateQueries({ queryKey: ['videos'] });
  };

  const videoNameById = useMemo(() => {
    const map: Record<number, string> = {};
    rows.forEach((r) => {
      map[r.id] = r.cctvName;
    });
    return map;
  }, [rows]);

  // 기존 배정 영상 — 재배정 모드. AssignModal 이 읽는 task 필드
  // (id=assignmentId / videoId / cctvName / workerId / workerName / status / assignedAt)로 매핑.
  const openReassign = (v: Video) => {
    if (!isReviewer || v.assignmentId == null || v.workerId == null) return;
    const task: Task = {
      id: v.assignmentId,
      videoId: v.id,
      cctvName: v.cctvName,
      workerId: v.workerId,
      workerName: v.workerName ?? '',
      status: v.assignStatus ?? 'IN_PROGRESS',
      assignedAt: v.assignedAt ?? '',
    };
    setSelectedTask(task);
    setAssignMode('reassign');
    setAssignModalOpen(true);
  };

  const openBulkAssign = () => {
    if (!isReviewer) return;
    if (selected.size === 0) return;
    setAssignMode('bulk');
    setAssignModalOpen(true);
  };

  // 배정/재배정 성공 후 선택 해제. 영상 목록 캐시는 useAssignTask/useReassignTask 가
  // VIDEO_KEYS 무효화로 자동 갱신하므로 행에 배정자명이 즉시 반영된다(R1).
  const handleAssignDone = () => {
    setAssignModalOpen(false);
    setSelectedTask(null);
    setSelected(new Set());
    // TaskListPage onSuccess 정합 — 배정/재배정 성공 시 작업 목록(/assignments)
    // 캐시까지 무효화한다. 영상 목록(VIDEO_KEYS)은 mutation hook 이 갱신한다.
    queryClient.invalidateQueries({ queryKey: ['assignments'] });
  };

  // 마킹 트리거(자동) 또는 수동 배정 성공 후 — 영상/작업 목록 캐시 무효화 + 팝업 종료.
  const handleMarked = () => {
    queryClient.invalidateQueries({ queryKey: VIDEO_KEYS.all });
    queryClient.invalidateQueries({ queryKey: ASSIGNMENT_KEYS.all });
    setMarkingTarget(null);
  };

  const totalPages = Math.max(
    1,
    data ? Math.ceil(data.totalElements / (params.size ?? 20)) : 1,
  );
  const currentPage = data?.number ?? params.page ?? 0;

  return (
    <div className="space-y-4">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-title-lg font-bold text-gray-900">영상 처리 현황</h1>
          <p className="text-caption text-gray-500 mt-0.5">
            관제서버에서 인계받은 영상의 배치 처리 상태와 단계를 확인합니다.
          </p>
        </div>
        <Button variant="secondary" size="sm" onClick={handleRefresh}>
          <RefreshCw size={14} aria-hidden />
          새로고침
        </Button>
      </div>

      {/* Filters */}
      <VideoFilters initial={params} onApply={updateParams} />

      {error && <ErrorState title="영상 목록을 불러올 수 없습니다" />}

      {/* Bulk action bar — REVIEWER 전용. WORKER 에겐 액션 바 자체를 노출하지 않는다. */}
      {isReviewer && selected.size > 0 && (
        <div className="flex items-center justify-between gap-3 bg-primary-50 border border-primary-200 rounded-lg px-4 py-2.5 text-body-md">
          <span className="font-medium text-primary-700">선택 {selected.size}건</span>
          <Button
            variant="primary"
            size="sm"
            onClick={openBulkAssign}
            aria-label={`${selected.size}개 영상 작업자 일괄 배정`}
          >
            <Users size={14} aria-hidden />
            {selected.size}개 일괄 배정
          </Button>
        </div>
      )}

      {/* Table */}
      <div className="bg-white border border-gray-200 rounded-lg shadow-sm overflow-hidden">
        <div className="flex items-center px-4 py-2 border-b border-gray-100 bg-gray-50">
          <input
            type="checkbox"
            checked={allChecked}
            onChange={toggleAll}
            className="w-4 h-4 accent-primary-600"
            aria-label="전체 선택"
          />
          <span className="ml-2 text-caption text-gray-500">
            전체 {data?.totalElements ?? 0}건
            {data ? ` (${currentPage + 1}/${totalPages} 페이지)` : ''}
          </span>
        </div>

        <div
          className={`overflow-x-auto transition-opacity ${refetching ? 'opacity-60' : 'opacity-100'}`}
          aria-busy={refetching || undefined}
          data-fetching={refetching ? 'true' : undefined}
        >
          <table className="w-full text-body-md">
            <thead>
              <tr className="border-b border-gray-200 bg-gray-50">
                <th
                  className="text-left text-table-header font-semibold text-gray-500 uppercase tracking-wide px-4 py-3"
                  style={{ width: '40px' }}
                >
                  {''}
                </th>
                <th className="text-left text-table-header font-semibold text-gray-500 uppercase tracking-wide px-4 py-3">
                  CCTV명
                </th>
                <th className="text-left text-table-header font-semibold text-gray-500 uppercase tracking-wide px-4 py-3">
                  이벤트
                </th>
                <th className="text-left text-table-header font-semibold text-gray-500 uppercase tracking-wide px-4 py-3">
                  녹화일
                </th>
                <th className="text-left text-table-header font-semibold text-gray-500 uppercase tracking-wide px-4 py-3">
                  길이
                </th>
                <th
                  className="text-left text-table-header font-semibold text-gray-500 uppercase tracking-wide px-4 py-3"
                  style={{ width: '120px' }}
                >
                  처리 단계
                </th>
                <th className="text-left text-table-header font-semibold text-gray-500 uppercase tracking-wide px-4 py-3">
                  배정자
                </th>
                <th className="text-left text-table-header font-semibold text-gray-500 uppercase tracking-wide px-4 py-3">
                  액션
                </th>
              </tr>
            </thead>
            <tbody>
              {isLoading ? (
                Array.from({ length: 5 }).map((_, i) => (
                  <tr key={i} className="border-b border-gray-100">
                    {/* 스켈레톤 칸수는 헤더 칸수(8)와 일치해야 한다 — [req: R1] 컬럼 제거 시 함께 갱신. */}
                    {Array.from({ length: 8 }).map((__, j) => (
                      <td key={j} className="px-4 py-3">
                        <Skeleton height={16} />
                      </td>
                    ))}
                  </tr>
                ))
              ) : rows.length === 0 ? (
                <tr>
                  {/* colSpan 은 헤더 칸수(8)와 일치해야 한다 — [req: R1] 컬럼 제거 시 함께 갱신. */}
                  <td colSpan={8} className="px-3 py-12">
                    <EmptyState message="해당하는 영상이 없습니다." />
                  </td>
                </tr>
              ) : (
                rows.map((v) => (
                  <tr
                    key={v.id}
                    className={cn(
                      'border-b border-gray-100 transition-colors hover:bg-primary-50 cursor-pointer',
                      selected.has(v.id) && 'bg-primary-50',
                    )}
                    onClick={() => navigate(`/video/${v.id}`)}
                  >
                    <td
                      className="px-4 py-3"
                      onClick={(e) => e.stopPropagation()}
                    >
                      <input
                        type="checkbox"
                        checked={selected.has(v.id)}
                        onChange={() => toggleRow(v.id)}
                        className="w-4 h-4 accent-primary-600"
                        aria-label={`${v.cctvName} 선택`}
                      />
                    </td>
                    <td className="px-4 py-3">
                      <span className="font-medium text-gray-800 text-label">{v.cctvName}</span>
                    </td>
                    <td className="px-4 py-3">
                      <EventTypeBadge eventType={v.eventTypeCd ?? v.eventName ?? ''} />
                    </td>
                    <td className="px-4 py-3">
                      <span className="text-caption text-gray-500">
                        {v.capturedAt ? v.capturedAt.slice(0, 10) : '-'}
                      </span>
                    </td>
                    <td className="px-4 py-3">
                      <span className="text-caption">{formatDuration(v.durationSec)}</span>
                    </td>
                    <td className="px-4 py-3">
                      {/* Phase 3 — 비식별 진행중/실패는 dataSttsCd 기반 배지보다 우선 표시(AC3-FE). */}
                      {v.deidentStatus === 'IN_PROGRESS' ? (
                        <StatusBadge status="DEIDENT_IN_PROGRESS" />
                      ) : v.deidentStatus === 'FAILED' ? (
                        <StatusBadge status="DEIDENT_FAILED" />
                      ) : v.status === 'COMPLETED' ? (
                        <StageBadge stage="COMPLETED" status="COMPLETED" />
                      ) : v.status === 'FAILED' ? (
                        <StageBadge stage="FAILED" status="FAILED" />
                      ) : (
                        <StatusBadge status={v.status} />
                      )}
                    </td>
                    {/* 배정자 — 역할 무관 표시(TaskListPage 정합). 액션 버튼만 REVIEWER 전용. */}
                    <td className="px-4 py-3">
                      {v.workerName ? (
                        <span className="text-body-md text-gray-700">
                          {v.workerName}
                        </span>
                      ) : (
                        <span className="text-body-md italic text-gray-400">
                          미배정
                        </span>
                      )}
                    </td>
                    <td
                      className="px-4 py-3"
                      onClick={(e) => e.stopPropagation()}
                    >
                      <div className="flex gap-1">
                        {/* 배정된 영상 — 재배정(기존 유지). 검수 승인 완료 행은 재배정 불가.
                            미배정 + 마킹 진입 가능 영상 — 신규 "마킹" 버튼(MarkingModal 오픈).
                            그 외(미배정 && !canMark, 검수완료 등)는 액션 버튼 미노출. */}
                        {isReviewer &&
                          v.workerId != null &&
                          v.assignStatus !== 'COMPLETED' && (
                            <Button
                              variant="ghost"
                              size="sm"
                              onClick={(e) => {
                                e.stopPropagation();
                                openReassign(v);
                              }}
                              aria-label={`${v.cctvName} 작업자 재배정`}
                            >
                              <RefreshCw size={12} aria-hidden />
                              재배정
                            </Button>
                          )}
                        {isReviewer && v.workerId == null && canMark(v) && (
                          <Button
                            variant="ghost"
                            size="sm"
                            onClick={(e) => {
                              e.stopPropagation();
                              setMarkingTarget({ rawSn: v.id, name: v.cctvName });
                            }}
                            aria-label={`${v.cctvName} 마킹`}
                          >
                            <Sparkles size={12} aria-hidden />
                            마킹
                          </Button>
                        )}
                        <Button
                          variant="ghost"
                          size="sm"
                          onClick={(e) => {
                            e.stopPropagation();
                            navigate(`/video/${v.id}`);
                          }}
                        >
                          상세
                          <ChevronRight size={12} aria-hidden />
                        </Button>
                      </div>
                    </td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>
      </div>

      {/* Pagination */}
      {data && totalPages > 1 && (
        <div className="flex items-center justify-center gap-1 mt-4">
          <button
            type="button"
            onClick={() => updateParams({ page: currentPage - 1 })}
            disabled={currentPage === 0}
            aria-label="이전 페이지"
            className="inline-flex items-center justify-center w-8 h-8 rounded-md text-gray-500 hover:bg-gray-100 disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
          >
            <ChevronLeft size={16} aria-hidden />
          </button>
          {Array.from({ length: Math.min(totalPages, 7) }).map((_, i) => {
            const p = i;
            return (
              <button
                key={p}
                type="button"
                onClick={() => updateParams({ page: p })}
                aria-current={p === currentPage ? 'page' : undefined}
                className={cn(
                  'inline-flex items-center justify-center w-8 h-8 rounded-md text-body-md font-medium transition-colors',
                  p === currentPage
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
            onClick={() => updateParams({ page: currentPage + 1 })}
            disabled={currentPage >= totalPages - 1}
            aria-label="다음 페이지"
            className="inline-flex items-center justify-center w-8 h-8 rounded-md text-gray-500 hover:bg-gray-100 disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
          >
            <ChevronRight size={16} aria-hidden />
          </button>
        </div>
      )}

      {/* 작업자 배정 모달 (REVIEWER 전용 — 재배정 / 일괄 재사용, TaskListPage 정합) */}
      {isReviewer && (
        <AssignModal
          open={assignModalOpen}
          onClose={() => setAssignModalOpen(false)}
          task={selectedTask}
          mode={assignMode}
          onSuccess={handleAssignDone}
          onBulkSuccess={handleAssignDone}
          videoIds={assignMode === 'bulk' ? Array.from(selected) : []}
          videoNameById={videoNameById}
        />
      )}

      {/* 마킹 진입 팝업 (REVIEWER 전용 — 미배정 + 마킹 가능 영상) */}
      {isReviewer && markingTarget && (
        <MarkingModal
          open
          rawSn={markingTarget.rawSn}
          videoName={markingTarget.name}
          onClose={() => setMarkingTarget(null)}
          onMarked={handleMarked}
        />
      )}
    </div>
  );
}
