import { useMemo, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { ChevronRight, RefreshCw, RotateCw, SkipForward, Sparkles, Users } from 'lucide-react';
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
import { BulkSkipReasonModal } from '@/features/video/components/BulkSkipReasonModal';
import { VideoFilters } from '@/features/video/components/VideoFilters';
import { VlmSkipDefaultBanner } from '@/features/video/components/VlmSkipDefaultBanner';
import { exceedsBulkRetryLimit } from '@/features/video/api';
import {
  useBulkRerunBatchStage,
  useBulkRetryBatch,
  useBulkSkipBatchStage,
} from '@/features/video/hooks/useBatchRecovery';
import { useVlmSkipDefault } from '@/features/sysconfig/hooks/useVlmSkipDefault';
import { useVideos } from '@/features/video/hooks/useVideos';
import {
  parseVideoListParams,
  videoListParamsToSearchParams,
} from '@/features/video/parseVideoListParams';
import {
  BULK_RETRY_MAX,
  isBatchFailed,
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
  // 전체 건너뛰기 스위치 상태 — 배너 노출의 단일 근거(판정은 훅이 갖고 화면은 결과만 쓴다).
  const vlmSkipDefault = useVlmSkipDefault({ enabled: isReviewer });

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

  /**
   * 일괄 조작 결과(부분 성공) — 건별 성패·사유를 모달로 보여준다.
   *
   * 세 조작(재시작 / 시계열 건너뛰기 · 재수행)의 응답 스키마가 하나이므로 결과 모달도 하나이며,
   * 무엇의 결과인지는 제목으로만 가른다(조작마다 모달을 새로 만들면 같은 본문이 세 벌이 된다).
   */
  const [bulkResult, setBulkResult] = useState<{
    title: string;
    data: BatchBulkRetryResult;
  } | null>(null);
  // 시계열 일괄 건너뛰기 — 사유를 받아야 하므로 이 조작만 모달을 거친다(재수행은 즉시 실행).
  const [skipReasonOpen, setSkipReasonOpen] = useState(false);
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
  /**
   * 결과 수용 — **접수하지 못한 분만 선택으로 남긴다**(세 조작 공통).
   *
   * 성공했다고 선택을 통째로 비우면, 거부된 건을 목록에서 처음부터 다시 골라야 한다.
   */
  const acceptBulkResult = (title: string) => (data: BatchBulkRetryResult) => {
    setBulkResult({ title, data });
    setSelected(new Set(data.results.filter((r) => !r.success).map((r) => r.rawSn)));
  };

  /** 요청 자체가 실패한 경우(부분 성공이 아니다) — 서버 문구가 있으면 그대로 쓴다. */
  const notifyBulkError = (fallback: string) => (err: unknown) => {
    pushToast({
      variant: 'error',
      message: err instanceof ApiError && err.userMessage ? err.userMessage : fallback,
    });
  };

  const bulkRetry = useBulkRetryBatch({
    onSuccess: acceptBulkResult('일괄 재시작 접수 결과'),
    onError: notifyBulkError('일괄 재시작에 실패했습니다. 잠시 후 다시 시도해 주세요.'),
  });

  /**
   * [@design SCREEN-008] [@design API-212] 시계열 묶음 일괄 건너뛰기 — 사유는 요청당 하나이며
   * 대상 전건에 같은 값으로 남는다.
   */
  const bulkSkip = useBulkSkipBatchStage({
    onSuccess: (data) => {
      setSkipReasonOpen(false);
      acceptBulkResult('시계열 일괄 건너뛰기 결과')(data);
    },
    onError: notifyBulkError('시계열 일괄 건너뛰기에 실패했습니다. 잠시 후 다시 시도해 주세요.'),
  });

  /**
   * [@design API-214] [@design ADR-050] 건너뛴 적이 있는 묶음 **재수행** — 확정된 라벨을 되돌리지
   * 않으므로 파괴적이지 않다(확인 창 없음). 수락 대상이 아닌 건은 건별 사유로 돌아온다.
   *
   * ★ 구 「일괄 건너뛰기 해제」 버튼(API-213 배선)은 **폐기**됐다 — 되살리지 말 것. 재수행이
   *   건너뛴 상태를 직접 수락하고 해제 표식까지 함께 남기므로, 해제와 재수행을 두 번 돌게 하면
   *   회수 동선만 길어지고 중간에 멈춘 영상(해제만 하고 재수행을 안 한 상태)이 생긴다.
   *   서버의 해제 API 자체는 남아 있으나 **이 화면은 부르지 않는다**.
   */
  const bulkRerun = useBulkRerunBatchStage({
    onSuccess: acceptBulkResult('시계열 일괄 재수행 결과'),
    onError: notifyBulkError('시계열 일괄 재수행에 실패했습니다. 잠시 후 다시 시도해 주세요.'),
  });

  // 상한 판정은 API 모듈의 단일 원천을 그대로 쓴다(화면이 같은 비교식을 다시 갖지 않는다).
  const overBulkRetryLimit = exceedsBulkRetryLimit(selected.size);
  const bulkBusy = bulkRetry.isPending || bulkSkip.isPending || bulkRerun.isPending;

  /**
   * 선택분 중 **지금 실패 상태**인 건수 — 일괄 재시작이 실제로 접수될 수 있는 대상 수다.
   *
   * ⚠ 이 값으로 요청을 거르지 않는다. 재시작은 지금도 선택 전건을 보내고 **서버가 건별로 거부**하며,
   * 화면이 미리 거르면 그 계약이 바뀐다. 여기서는 안내 문구에만 쓴다.
   */
  // `rows` 는 매 렌더 새 배열이라 useMemo 로 감싸도 재계산을 막지 못한다(현재 페이지 한 벌 필터라
  // 비용도 무시할 수준이다). 불필요한 의존성 경고만 남으므로 그대로 계산한다.
  const selectedFailedCount = rows.filter((r) => selected.has(r.id) && isBatchFailed(r)).length;

  /** 일괄 조작 공통 가드 — 권한·빈 선택·상한·진행 중. */
  const canRunBulk = isReviewer && selected.size > 0 && !overBulkRetryLimit && !bulkBusy;

  const handleBulkRetry = () => {
    if (!canRunBulk) return;
    bulkRetry.mutate(Array.from(selected));
  };

  const handleBulkSkip = (reason: string) => {
    if (!canRunBulk) return;
    bulkSkip.mutate({ rawSns: Array.from(selected), reason });
  };

  const handleBulkRerun = () => {
    if (!canRunBulk) return;
    bulkRerun.mutate(Array.from(selected));
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

      {/*
        [@design SCREEN-008] [@design ADR-050] 시계열 위탁 **전체 건너뛰기** 상시 배너.
        켜져 있는 동안 들어오는 영상은 전건이 시계열 없이 확정되는데, 그 사실이 어디에도 드러나지
        않으면 아무도 모르는 사이에 학습데이터가 시계열 없이 쌓인다. 꺼져 있으면 두지 않는다.
        ⚠ 설정 조회는 REVIEWER 전용이라 WORKER 에게는 호출조차 하지 않는다(403 누적 방지) —
          그 결과 WORKER 화면에는 배너가 없다. 이 스위치를 끄고 켜는 것도 REVIEWER 의 일이다.
      */}
      {vlmSkipDefault.on && <VlmSkipDefaultBanner reason={vlmSkipDefault.reason} />}

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
            <div className="flex flex-wrap items-center justify-end gap-2">
              {/* [@design SCREEN-008] [@design API-199] 일괄 재시작 — 배치가 한 번 멈추면 여러 건이
                  함께 실패하므로 상세 화면을 건건이 여는 대신 목록에서 처리한다. */}
              <Button
                variant="secondary"
                size="sm"
                onClick={handleBulkRetry}
                disabled={overBulkRetryLimit || bulkBusy}
                loading={bulkRetry.isPending}
                aria-label={`${selected.size}개 영상 배치 일괄 재시작`}
              >
                <RefreshCw size={14} aria-hidden />
                {selected.size}건 일괄 재시작
              </Button>

              {/* 구분 — 대상 범위가 다른 축(재시작=실패분 / 시계열=선택 전건)이라 시각적으로 가른다. */}
              <span className="h-4 w-px bg-primary-200" aria-hidden />

              {/*
                [@design SCREEN-008] [@design API-212] [@design API-214] [@design ADR-050]
                시계열 묶음 일괄 조작 2종. ★ 이 바의 대상은 **시계열 하나**다 — 오토라벨 묶음은
                산출물이 라벨이라 대량으로 건너뛸 수 있게 열지 않았다(서버도 400).
                ★ 구 「건너뛰기 해제」 버튼은 폐기됐다(재수행이 건너뛴 상태를 직접 수락한다).
                ★ 위계를 위해 둘 다 secondary 로 둔다(primary 는 일괄 배정 하나만 유지).
              */}
              {/* ★ 건너뛰기의 **접수 대상**은 그 묶음이 실패한 영상뿐이다 — 정상 영상을 미리 골라
                  건너뛰는 길은 두지 않는다(미연동 구간을 통째로 덮는 몫은 시스템 설정의 전체
                  건너뛰기 스위치가 맡는다). 판정과 거부는 **서버가 건별로** 하고 사유는 결과
                  모달이 보여준다.
                  ★★ 그래서 버튼 노출을 **배치 상태(실패)로 게이팅하지 않는다** — 시계열 위탁 실패는
                    파이프라인을 멈추지 않아 그 영상의 배치 상태가 완료로 남는다. 구 구현은 선택분 중
                    `status === 'FAILED'` 인 건이 있어야만 버튼을 그렸고, 그 결과 「실패 후 판단」
                    입구가 정확히 필요한 그 상황에서 **닫혀 있었다**(ADR-050). 과대 노출은 서버가
                    건별 거부로 보정하지만 과소 노출은 보정되지 않는다 — 요청을 보낼 창구가 없다.
                    실패한 영상을 모으는 수단은 목록의 「작업 묶음 실패」 필터다. */}
              <Button
                variant="secondary"
                size="sm"
                onClick={() => setSkipReasonOpen(true)}
                disabled={overBulkRetryLimit || bulkBusy}
                loading={bulkSkip.isPending}
                aria-label={`${selected.size}개 영상 시계열 일괄 건너뛰기`}
              >
                <SkipForward size={14} aria-hidden />
                {selected.size}건 시계열 건너뛰기
              </Button>
              {/* 재수행은 파괴적이지 않으므로 확인 창을 두지 않는다(확정된 라벨을 건드리지 않는다).
                  ★ 대상은 **건너뛴 적이 있는 묶음**(건너뜀·해제 모두)이라 실패 여부와 무관하게 둔다 —
                    연동이 확정된 뒤의 회수가 이 버튼 하나로 끝난다. */}
              <Button
                variant="secondary"
                size="sm"
                onClick={handleBulkRerun}
                disabled={overBulkRetryLimit || bulkBusy}
                loading={bulkRerun.isPending}
                aria-label={`${selected.size}개 영상 시계열 일괄 재수행`}
              >
                <RotateCw size={14} aria-hidden />
                {selected.size}건 시계열 재수행
              </Button>

              <span className="h-4 w-px bg-primary-200" aria-hidden />

              <Button
                variant="primary"
                size="sm"
                onClick={openBulkAssign}
                aria-label={`${selected.size}개 영상 작업자 일괄 배정`}
              >
                <Users size={14} aria-hidden />
                {selected.size}건 일괄 배정
              </Button>
            </div>
          </div>
          {/*
            두 조작의 **대상 범위가 다르다**는 사실을 미리 알린다 — 재시작은 실패 건만 접수되고
            시계열 일괄 조작은 선택 전건에 적용되며 거부는 건별 사유로 돌아온다. 이 차이를 결과
            모달에서야 알게 되면 사용자는 "왜 일부만 됐나"를 되짚어야 한다.
          */}
          <p className="text-caption text-gray-600" data-testid="bulk-scope-hint">
            선택한 영상 중 배치가 실패한 {selectedFailedCount}건만 재시작 대상입니다. 시계열
            건너뛰기는 시계열 작업이 실패한 영상에, 시계열 재수행은 건너뛴 적이 있는 영상에
            접수됩니다. 이 두 조작은 화면이 대상을 미리 가르지 않고 선택한 {selected.size}건
            전부를 보내 되는 것만 처리하고,{' '}
            {/* ⚠ 이 구절은 문구 가드(`uiWordingGuard`)의 허용 목록에 **줄 단위**로 올라 있다 —
                줄바꿈으로 쪼개면 예외가 풀려 가드가 FAIL 한다. 한 줄로 유지할 것. */}
            거부된 건은 사유와 함께 돌려줍니다. 시계열 작업이 실패한 영상만 모으려면 위 「작업 묶음
            실패」 필터를 쓰세요.
          </p>
          {/*
            [@design SCREEN-008] [@design ADR-050] 스위치가 켜져 있을 때만 덧붙이는 한 줄.
            ★ 왜 필요한가 — 스위치가 켜져 있는 동안 자동으로 건너뜀 표식이 선 영상을 재수행하면
              그 영상에는 **자동 해제 표식**이 남아, 이후 스위치가 켜져 있어도 계속 위탁 대상이 된다
              (「자동 표식은 사람의 결정을 덮지 않는다」의 귀결이며 설계상 의도다). 즉 한 번의 클릭이
              그 구간의 운영 결정을 선택한 건수만큼 뒤집는데, 그 사실이 조작 지점에 없었다.
            ⚠ 스위치 상태는 이 화면이 이미 조회한 값(`vlmSkipDefault`)을 그대로 쓴다 — 이 안내를
              위해 조회를 새로 붙이지 않는다.
          */}
          {vlmSkipDefault.on && (
            <p className="text-caption text-gray-600" data-testid="bulk-rerun-skip-default-note">
              지금은 시계열 위탁 전체 건너뛰기가 켜져 있습니다. 재수행한 영상은 스위치를 그대로 둬도
              이후 시계열 위탁 대상이 됩니다.
            </p>
          )}
          {/* 상한은 **미리** 알린다 — 보내고 400 을 받은 뒤에야 알게 되는 동선을 피한다. */}
          {overBulkRetryLimit && (
            <p className="text-caption text-danger" data-testid="bulk-retry-limit-notice">
              일괄 조작은 한 번에 최대 {BULK_RETRY_MAX}건까지 가능합니다. 선택을 줄여 주세요.
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

      {/* 시계열 일괄 건너뛰기 사유 (REVIEWER 전용) — 사유는 요청당 하나로 전건에 같은 값이 남는다. */}
      {isReviewer && (
        <BulkSkipReasonModal
          open={skipReasonOpen}
          rawSns={Array.from(selected)}
          videoNameById={videoNameById}
          loading={bulkSkip.isPending}
          onClose={() => setSkipReasonOpen(false)}
          onConfirm={handleBulkSkip}
        />
      )}

      {/* 일괄 조작 결과 (REVIEWER 전용) — 접수 건수 + 접수하지 못한 분의 사유. */}
      <BulkRetryResultModal
        open={bulkResult !== null}
        result={bulkResult?.data ?? null}
        title={bulkResult?.title}
        videoNameById={videoNameById}
        onClose={() => setBulkResult(null)}
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
