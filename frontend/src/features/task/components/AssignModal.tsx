import { useEffect, useState } from 'react';

import { Button } from '@/components/common/Button';
import { Modal } from '@/components/common/Modal';
import { Skeleton } from '@/components/common/Skeleton';
import { useUsers } from '@/features/user/hooks/useUsers';
import { useWorkers } from '@/features/user/hooks/useWorkers';
import { Role } from '@/lib/api/types';
import { useAuthStore } from '@/stores/useAuthStore';
import { useUiStore } from '@/stores/useUiStore';

import { useAssignTask } from '../hooks/useAssignTask';
import { useReassignTask } from '../hooks/useReassignTask';
import type { Task } from '../types';

export interface AssignModalProps {
  open: boolean;
  onClose: () => void;
  /** single/reassign 모드의 task. 미배정 영상의 단건 신규 배정 시에는 null. bulk 모드에서도 null. */
  task: Task | null;
  mode: 'assign' | 'reassign' | 'bulk';
  /** single 모드 성공 콜백 */
  onSuccess?: () => void;
  /** bulk 모드 성공 콜백 */
  onBulkSuccess?: (videoIds: number[]) => void;
  /** bulk 모드 대상 영상 ID 목록 */
  videoIds?: number[];
  /** bulk 미리보기용 영상명 맵 */
  videoNameById?: Record<number, string>;
  /** 단건 신규 배정(미배정 영상) 모드용 영상 PK — task 가 없을 때 사용 */
  videoId?: number;
  /** 단건 신규 배정 영상 표시명 */
  videoName?: string;
}

/**
 * BE 가 ApiResponse.message 로 내려준 사람 친화적 에러 메시지를 안전하게 추출한다.
 * - axios 에러 형태(`err.response.data.message`)만 신뢰하고 그 외에는 fallback 사용.
 * - 토스트에 표시되므로 JSX 자동 이스케이프로 XSS 위험은 없음. BE 측 메시지가
 *   민감 정보/내부 경로를 포함하지 않는다는 전제 (GlobalExceptionHandler 가 보장).
 */
function extractBeMessage(err: unknown, fallback: string): string {
  if (typeof err === 'object' && err !== null && 'response' in err) {
    const resp = (err as { response?: { data?: unknown } }).response;
    const data = resp?.data;
    if (typeof data === 'object' && data !== null && 'message' in data) {
      const m = (data as { message?: unknown }).message;
      if (typeof m === 'string' && m.trim() !== '') return m;
    }
  }
  return fallback;
}

/**
 * SCR-TASK-002 작업 배정 모달.
 * - assign / reassign / bulk 모드 지원
 * - 작업자 select, 검수자 select(REVIEWER 한정) / readonly(WORKER)
 */
export function AssignModal({
  open,
  onClose,
  task,
  mode,
  onSuccess,
  onBulkSuccess,
  videoIds = [],
  videoNameById = {},
  videoId,
  videoName,
}: AssignModalProps) {
  const claims = useAuthStore((s) => s.claims);
  const pushToast = useUiStore((s) => s.pushToast);
  const role = claims?.role;
  const canChangeReviewer = role === Role.REVIEWER;

  // 모달이 열려 있고 REVIEWER 일 때만 호출 — /users 와 /users/workers 는 REVIEWER 전용 API.
  // (WORKER 화면에서도 모달이 마운트되어 있어 무조건 호출되면 403 이 발생하므로 enabled 로 막는다.)
  const shouldFetchUsers = open && canChangeReviewer;
  const { data: workers, isLoading: workersLoading } = useWorkers({
    enabled: shouldFetchUsers,
  });
  const { data: reviewersPage } = useUsers(
    { role: Role.REVIEWER, size: 50 },
    { enabled: shouldFetchUsers },
  );
  const reviewers = reviewersPage?.content ?? [];

  const [workerId, setWorkerId] = useState<number | ''>('');
  const [reviewerId, setReviewerId] = useState<number | ''>('');
  const [errors, setErrors] = useState<Record<string, string>>({});

  const isBulk = mode === 'bulk';
  const isReassign = mode === 'reassign';

  // 모달 열릴 때 초기값 설정
  useEffect(() => {
    if (!open) return;
    const defaultReviewer =
      canChangeReviewer && claims?.sub ? Number(claims.sub) : '';
    if (isBulk) {
      setWorkerId(workers?.[0]?.id ?? '');
      setReviewerId(defaultReviewer);
    } else if (task) {
      setWorkerId(task.workerId ?? '');
      setReviewerId(task.reviewerId ?? defaultReviewer);
    } else {
      // 미배정 영상 단건 신규 배정 — 작업자는 미선택, 검수자는 로그인 사용자
      setWorkerId('');
      setReviewerId(defaultReviewer);
    }
    setErrors({});
  }, [open, task, isBulk, canChangeReviewer, claims?.sub, workers]);

  const handleAssignSuccess = () => {
    const count = isBulk ? videoIds.length : 1;
    pushToast({
      variant: 'success',
      message: isReassign
        ? '재배정 완료'
        : count > 1
          ? `${count}건 일괄 배정 완료`
          : '배정 완료',
    });
    if (isBulk) {
      onBulkSuccess?.(videoIds);
    } else {
      onSuccess?.();
    }
    onClose();
  };

  const handleAssignError = (err: unknown) => {
    const fallback = isReassign ? '재배정에 실패했습니다' : '배정에 실패했습니다';
    pushToast({
      variant: 'error',
      message: extractBeMessage(err, fallback),
    });
  };

  const { mutate: mutateAssign, isPending: assignPending } = useAssignTask({
    onSuccess: handleAssignSuccess,
    onError: handleAssignError,
  });

  const { mutate: mutateReassign, isPending: reassignPending } = useReassignTask({
    onSuccess: handleAssignSuccess,
    onError: handleAssignError,
  });

  const isPending = assignPending || reassignPending;

  const handleSave = () => {
    if (!workerId) {
      setErrors({ workerId: '작업자를 선택해주세요.' });
      return;
    }
    // 재배정 모드: PATCH /assignments/{id} 호출 (workerId 만 변경 가능 — reviewer 변경 미지원)
    if (isReassign && task) {
      mutateReassign({ id: task.id, body: { workerId: Number(workerId) } });
      return;
    }
    const targetVideoIds = isBulk
      ? videoIds
      : task
        ? [task.videoId]
        : videoId
          ? [videoId]
          : [];
    if (targetVideoIds.length === 0) return;
    // BE 계약: { workerId, rawDataIds, reviewerId? }
    mutateAssign({
      workerId: Number(workerId),
      rawDataIds: targetVideoIds,
      ...(reviewerId ? { reviewerId: Number(reviewerId) } : {}),
    });
  };

  const title = isBulk
    ? `${videoIds.length}개 영상 일괄 배정`
    : isReassign
      ? '작업 재배정'
      : '작업 배정';

  const previewIds = videoIds.slice(0, 3);
  const remaining = Math.max(0, videoIds.length - 3);

  if (!open) return null;
  // 단건 모드는 task 또는 videoId 중 하나는 반드시 있어야 한다 (미배정 영상의 신규 배정 케이스).
  if (!isBulk && !task && !videoId) return null;
  if (isBulk && videoIds.length === 0) return null;

  return (
    <Modal
      open={open}
      onClose={onClose}
      title={title}
      footer={
        <>
          <Button variant="outline" onClick={onClose} disabled={isPending}>
            취소
          </Button>
          <Button
            variant="primary"
            onClick={handleSave}
            disabled={
              !workerId ||
              isPending ||
              (isReassign && task != null && Number(workerId) === task.workerId)
            }
            loading={isPending}
          >
            {isBulk ? `${videoIds.length}건 일괄 배정` : '저장'}
          </Button>
        </>
      }
    >
      <div className="space-y-5">
        {/* 영상 정보 */}
        {isBulk ? (
          <div className="bg-gray-50 rounded-lg px-4 py-3 space-y-2">
            <p className="text-xs font-semibold text-gray-500 uppercase tracking-wide">
              대상 영상 ({videoIds.length}건)
            </p>
            <div className="flex flex-wrap gap-1.5">
              {previewIds.map((vid) => (
                <span
                  key={vid}
                  className="inline-flex items-center rounded-full bg-blue-100 px-2 py-0.5 text-xs font-medium text-blue-700"
                >
                  {videoNameById[vid] ?? `#${vid}`}
                </span>
              ))}
              {remaining > 0 && (
                <span className="inline-flex items-center rounded-full bg-gray-100 px-2 py-0.5 text-xs font-medium text-gray-600">
                  외 {remaining}건
                </span>
              )}
            </div>
            <p className="text-xs text-gray-500">
              선택된 모든 영상에 동일한 작업자가 배정됩니다.
            </p>
          </div>
        ) : task ? (
          <div className="bg-gray-50 rounded-lg px-4 py-3 space-y-1.5">
            <p className="text-xs font-semibold text-gray-500 uppercase tracking-wide">
              영상 정보
            </p>
            <span className="font-medium text-gray-800 text-sm">
              {task.cctvName}
            </span>
          </div>
        ) : videoId ? (
          <div className="bg-gray-50 rounded-lg px-4 py-3 space-y-1.5">
            <p className="text-xs font-semibold text-gray-500 uppercase tracking-wide">
              영상 정보
            </p>
            <span className="font-medium text-gray-800 text-sm">
              {videoName ?? `#${videoId}`}
            </span>
          </div>
        ) : null}

        {/* 작업자 select */}
        <div className="space-y-1">
          <label
            htmlFor="assign-worker"
            className="text-sm font-medium text-gray-700"
          >
            작업자 <span className="text-red-500">*</span>
          </label>
          {workersLoading ? (
            <Skeleton height={36} />
          ) : (
            <select
              id="assign-worker"
              value={workerId}
              onChange={(e) => {
                setWorkerId(e.target.value ? Number(e.target.value) : '');
                setErrors({});
              }}
              disabled={isPending}
              className="w-full py-2 px-3 text-sm border border-gray-300 rounded-md focus:outline-none focus:ring-2 focus:ring-primary-500 disabled:bg-gray-50 disabled:text-gray-400"
            >
              <option value="">작업자 선택</option>
              {(workers ?? []).map((w) => (
                <option key={w.id} value={w.id}>
                  {w.name}
                  {!w.active ? ' (비활성)' : ''}
                  {isReassign && task && w.id === task.workerId ? ' (현재)' : ''}
                </option>
              ))}
            </select>
          )}
          {errors['workerId'] && (
            <p className="text-xs text-red-500">{errors['workerId']}</p>
          )}
          {isReassign && task && workerId !== '' && Number(workerId) === task.workerId && (
            <p className="text-xs text-amber-600">
              현재 배정된 작업자와 동일합니다. 다른 작업자를 선택해주세요.
            </p>
          )}
        </div>

        {/* 검수자 select */}
        <div className="space-y-1">
          <label
            htmlFor="assign-reviewer"
            className="text-sm font-medium text-gray-700"
          >
            검수자
          </label>
          {canChangeReviewer ? (
            <select
              id="assign-reviewer"
              value={reviewerId}
              onChange={(e) =>
                setReviewerId(e.target.value ? Number(e.target.value) : '')
              }
              disabled={isPending}
              className="w-full py-2 px-3 text-sm border border-gray-300 rounded-md focus:outline-none focus:ring-2 focus:ring-primary-500 disabled:bg-gray-50 disabled:text-gray-400"
            >
              <option value="">검수자 선택 (선택)</option>
              {reviewers.map((r) => (
                <option key={r.id} value={r.id}>
                  {r.name}
                </option>
              ))}
            </select>
          ) : (
            <input
              id="assign-reviewer"
              type="text"
              readOnly
              value={claims?.name ?? '현재 사용자'}
              className="w-full py-2 px-3 text-sm border border-gray-200 rounded-md bg-gray-50 text-gray-500"
            />
          )}
        </div>
      </div>
    </Modal>
  );
}
