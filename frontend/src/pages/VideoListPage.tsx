import { useMemo, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { ChevronRight, RefreshCw, Sparkles, Users } from 'lucide-react';
import { useQueryClient } from '@tanstack/react-query';

import { Button } from '@/components/common/Button';
import { EmptyState } from '@/components/common/EmptyState';
import { ErrorState } from '@/components/common/ErrorState';
import { EventTypeBadge } from '@/components/common/EventTypeBadge';
import { Pagination } from '@/components/common/Pagination';
import { Skeleton } from '@/components/common/Skeleton';
import { StageBadge } from '@/components/common/StageBadge';
import { StatusBadge } from '@/components/common/StatusBadge';
import { cn } from '@/lib/cn';
import { AssignModal } from '@/features/task/components/AssignModal';
import type { Task } from '@/features/task/types';
import { MarkingModal } from '@/features/marking/components/MarkingModal';
import { canMark } from '@/features/marking/markingEligibility';
import { BulkRetryResultModal } from '@/features/video/components/BulkRetryResultModal';
import { VideoFilters } from '@/features/video/components/VideoFilters';
import { useBulkRetryBatch } from '@/features/video/hooks/useBatchRecovery';
import { useVideos } from '@/features/video/hooks/useVideos';
import {
  parseVideoListParams,
  videoListParamsToSearchParams,
} from '@/features/video/parseVideoListParams';
import {
  BULK_RETRY_MAX,
  type BatchBulkRetryResult,
  type Video,
  type VideoListParams,
} from '@/features/video/types';
import { ApiError } from '@/lib/api/errors';
import { useUiStore } from '@/stores/useUiStore';
import { Role } from '@/lib/api/types';
import { ASSIGNMENT_KEYS, VIDEO_KEYS } from '@/lib/queryKeys';
import { useAuthStore } from '@/stores/useAuthStore';

/**
 * 표 헤더 셀 클래스 — 8개 `<th>` 가 이 한 값을 공유한다.
 *
 * 글자색 하한은 `gray-600` 이다 — 헤더 배경이 secondary-50(#EEF2F7)이라 gray-500 은
 * 그 위에서 4.01:1 로 AA(4.5:1) 미달이다(gray-600 은 5.60:1).
 * 크기는 표 헤더 전용 step(`text-table-header`, 14px/600) — 본문 셀의 읽는 데이터가
 * `text-body-md`(17px) 인 것과 다른 축이다(헤더는 열 라벨이지 읽는 본문이 아니다).
 *
 * ⚠ 굵기 클래스(`font-semibold` 등)를 함께 두지 않는다 — `text-table-header` step 이
 * 이미 `font-weight: 600` 을 emit 하므로 중복이고, 두 곳에서 지정하면 한쪽만 고쳐져
 * 화면마다 굵기가 갈린다(실제로 500/600/700 세 갈래가 났다).
 * ⚠ 이 클래스는 반드시 **`<th>` 에 직접** 건다 — `<tr>`/`<thead>` 에만 걸면 상속값이
 * 브라우저 UA 기본 `th { font-weight: bold }`(700)에 져서 600 이 적용되지 않는다.
 */
const TH_CLASS =
  'text-left text-table-header text-gray-600 uppercase tracking-wide px-4 py-3';

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

  // 일괄 재시작 결과(부분 성공) — 건별 성패·사유를 모달로 보여준다.
  const [bulkRetryResult, setBulkRetryResult] = useState<BatchBulkRetryResult | null>(null);
  const pushToast = useUiStore((s) => s.pushToast);

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

  /**
   * [@design SCREEN-008] [@design API-199] 일괄 재시작 — **부분 성공**을 그대로 다룬다.
   *
   * 성공했다고 선택을 통째로 비우지 않고 **실패분만 선택으로 남긴다** — 배치가 한 번 멈추면
   * 여러 건이 함께 실패하는데, 그중 일부가 "이미 진행 중"으로 밀렸을 때 사용자가 목록에서
   * 그 영상들을 처음부터 다시 고르게 만들지 않기 위해서다.
   */
  const bulkRetry = useBulkRetryBatch({
    onSuccess: (data) => {
      setBulkRetryResult(data);
      setSelected(new Set(data.results.filter((r) => !r.success).map((r) => r.rawSn)));
    },
    onError: (err) => {
      pushToast({
        variant: 'error',
        message:
          err instanceof ApiError && err.userMessage
            ? err.userMessage
            : '일괄 재시작에 실패했습니다. 잠시 후 다시 시도해 주세요.',
      });
    },
  });

  const overBulkRetryLimit = selected.size > BULK_RETRY_MAX;

  const handleBulkRetry = () => {
    if (!isReviewer) return;
    if (selected.size === 0 || overBulkRetryLimit) return;
    bulkRetry.mutate(Array.from(selected));
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

  // 전체 페이지 수는 **서버 응답값을 그대로** 쓴다 — 총 건수와 페이지 크기로 되계산하면
  // 서버의 페이징 규칙을 화면이 한 번 더 갖게 되어 두 값이 어긋날 수 있다.
  const totalPages = Math.max(1, data?.totalPages ?? 1);
  const currentPage = data?.number ?? params.page ?? 0;

  return (
    <div className="space-y-4">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-title-lg font-bold text-gray-900">영상 처리 현황</h1>
          <p className="text-caption text-gray-600 mt-0.5">
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

      {/* 조회 실패 시 재시도 수단을 준다 — `refetch` 가 지역 변수로만 있고 `onRetry` 가 비어 있어
          사용자가 실패 화면에서 빠져나올 방법이 헤더 새로고침뿐이었다(사양 SCREEN-008 '에러=ErrorState(재시도 버튼)'). */}
      {error && (
        <ErrorState title="영상 목록을 불러올 수 없습니다" onRetry={handleRefresh} />
      )}

      {/* Bulk action bar — REVIEWER 전용. WORKER 에겐 액션 바 자체를 노출하지 않는다. */}
      {isReviewer && selected.size > 0 && (
        <div className="flex flex-col gap-1.5 bg-primary-50 border border-primary-200 rounded-lg px-4 py-2.5 text-body-md">
          <div className="flex items-center justify-between gap-3">
            <span className="font-medium text-primary-700">선택 {selected.size}건</span>
            <div className="flex items-center gap-2">
              {/* [@design SCREEN-008] [@design API-199] 일괄 재시작 — 배치가 한 번 멈추면 여러 건이
                  함께 실패하므로 상세 화면을 건건이 여는 대신 목록에서 처리한다. */}
              <Button
                variant="secondary"
                size="sm"
                onClick={handleBulkRetry}
                disabled={overBulkRetryLimit || bulkRetry.isPending}
                loading={bulkRetry.isPending}
                aria-label={`${selected.size}개 영상 배치 일괄 재시작`}
              >
                <RefreshCw size={14} aria-hidden />
                {selected.size}개 일괄 재시작
              </Button>
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
          </div>
          {/* 상한은 **미리** 알린다 — 보내고 400 을 받은 뒤에야 알게 되는 동선을 피한다. */}
          {overBulkRetryLimit && (
            <p className="text-caption text-danger" data-testid="bulk-retry-limit-notice">
              일괄 재시작은 한 번에 최대 {BULK_RETRY_MAX}건까지 가능합니다. 선택을 줄여 주세요.
            </p>
          )}
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
          <span className="ml-2 text-caption text-gray-600">
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
              {/* 헤더 배경은 secondary 스케일 최옅단(DS-001 do_rules) — 페이지 배경과 같은
                  회색을 쓰면 열 구조가 먼저 읽히지 않는다. */}
              <tr className="border-b border-gray-200 bg-secondary-50">
                <th className={TH_CLASS} style={{ width: '40px' }}>
                  {''}
                </th>
                <th className={TH_CLASS}>CCTV명</th>
                <th className={TH_CLASS}>이벤트</th>
                <th className={TH_CLASS}>녹화일</th>
                <th className={TH_CLASS}>길이</th>
                <th className={TH_CLASS} style={{ width: '120px' }}>
                  처리 단계
                </th>
                <th className={TH_CLASS}>배정자</th>
                <th className={TH_CLASS}>액션</th>
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
                      // hover 표면은 rowHover 토큰(DS-001 do_rules 2) — 선택 상태(bg-primary-50)는
                      // hover 와 다른 축이라 그대로 둔다.
                      'border-b border-gray-100 transition-colors hover:bg-rowHover cursor-pointer',
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
                      <span className="font-medium text-gray-800 text-body-md">{v.cctvName}</span>
                    </td>
                    <td className="px-4 py-3">
                      <EventTypeBadge eventType={v.eventTypeCd ?? v.eventName ?? ''} />
                    </td>
                    <td className="px-4 py-3">
                      <span className="text-body-md text-gray-600">
                        {v.capturedAt ? v.capturedAt.slice(0, 10) : '-'}
                      </span>
                    </td>
                    <td className="px-4 py-3">
                      <span className="text-body-md">{formatDuration(v.durationSec)}</span>
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
                            미배정 + 마킹 진입 가능 영상 — "마킹 설정" 버튼(MarkingModal 오픈).
                            그 외(미배정 && !canMark, 검수완료 등)는 액션 버튼 미노출.
                            ★라벨은 사양 SCREEN-008 의 '마킹 설정'이다 — 이 버튼은 마킹을 바로
                            실행하지 않고 자동/수동 방식을 고르는 팝업을 연다. 작업목록(SCREEN-012)의
                            '마킹'은 마킹 화면으로 바로 이동하는 다른 버튼이라 문구가 다르다. */}
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
                            aria-label={`${v.cctvName} 마킹 설정`}
                          >
                            <Sparkles size={12} aria-hidden />
                            마킹 설정
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

      {/*
        페이지네이션 — 공용 컨트롤을 그대로 쓴다(UI-008).
        이 화면이 갖고 있던 번호 목록은 항상 앞쪽 7칸만 그려, 페이지가 8개를 넘으면 뒤 페이지로
        가는 번호가 아예 없었다(다음 버튼을 반복해 누르는 길만 남았다). 공용 컨트롤은 양끝 +
        현재 앞뒤 1칸을 남기고 접으므로 어느 위치에서도 마지막 페이지로 한 번에 갈 수 있다.
        총 건수 표기는 이 컨트롤이 갖지 않으며 표 머리글이 계속 소유한다.
      */}
      {data && totalPages > 1 && (
        <Pagination
          page={currentPage}
          totalPages={totalPages}
          onChange={(p) => updateParams({ page: p })}
          className="mt-4"
        />
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

      {/* 일괄 재시작 결과 (REVIEWER 전용) — 성공·실패 건수 + 실패분 사유. */}
      <BulkRetryResultModal
        open={bulkRetryResult !== null}
        result={bulkRetryResult}
        videoNameById={videoNameById}
        onClose={() => setBulkRetryResult(null)}
      />

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
