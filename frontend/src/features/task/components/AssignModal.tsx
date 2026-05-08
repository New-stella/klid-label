import { useEffect, useState } from 'react';

import { Button } from '@/components/common/Button';
import { EmptyState } from '@/components/common/EmptyState';
import { Modal } from '@/components/common/Modal';
import { Radio } from '@/components/common/Radio';
import { Spinner } from '@/components/common/Spinner';
import { useWorkers } from '@/features/user/hooks/useWorkers';
import type { Video } from '@/features/video/types';
import { useAuthStore } from '@/stores/useAuthStore';
import { useUiStore } from '@/stores/useUiStore';

import { useAssignTask } from '../hooks/useAssignTask';

export interface AssignModalProps {
  /** Single 모드의 대상 영상 (필수). bulk 모드에서도 첫 번째 영상으로 활용. */
  video: Video;
  /**
   * V1.x — bulk 일괄 배정 모드. 1건 이상이면 multi 표시.
   * 미지정 시 single 모드.
   */
  videos?: Video[];
  onClose(): void;
  onSuccess?(): void;
}

/**
 * SCR-TASK-002 작업 배정 모달.
 *
 * UI/UX §4-5 정합 — **작업자 라디오 선택만** 제공한다.
 * 우선순위·기한·메모 등 추가 입력은 절대 추가하지 않는다 (회귀 방지).
 *
 * V1.x 변경:
 * - 다중 영상(bulk) 모드 지원 — videos prop이 있으면 일괄 배정 흐름
 * - 작업자 기본값 = 현재 사용자 (인증된 경우만, 테스트 환경 호환)
 *
 * 보안:
 * - workerId는 number 타입 검증 (Worker.id) → IDOR/Injection 방어
 * - 사용자 입력은 라디오 value (정수 ID)로만 전달
 */
export function AssignModal({
  video,
  videos,
  onClose,
  onSuccess,
}: AssignModalProps) {
  const claims = useAuthStore((s) => s.claims);
  const { data: workers, isLoading } = useWorkers();
  const pushToast = useUiStore((s) => s.pushToast);

  const isBulk = Array.isArray(videos) && videos.length > 0;
  const targetVideos = isBulk ? videos! : [video];

  const [workerId, setWorkerId] = useState<number | null>(null);

  // 인증된 세션에서 현재 사용자가 worker 목록에 있으면 자동 선택 (mock §4-5 V1.x).
  // 비인증/테스트 환경에서는 미선택 유지.
  useEffect(() => {
    if (workerId !== null) return;
    if (!workers || workers.length === 0) return;
    const me = claims?.sub ? Number(claims.sub) : NaN;
    if (Number.isFinite(me) && workers.some((w) => w.id === me)) {
      setWorkerId(me);
    }
  }, [workers, claims?.sub, workerId]);

  const { mutate, isPending } = useAssignTask({
    onSuccess: () => {
      const count = targetVideos.length;
      pushToast({
        variant: 'success',
        message: count > 1 ? `${count}건 일괄 배정 완료` : '배정 완료',
      });
      onClose();
      onSuccess?.();
    },
    onError: () => {
      pushToast({ variant: 'error', message: '배정에 실패했습니다' });
    },
  });

  const handleSubmit = () => {
    if (typeof workerId !== 'number') return;
    mutate({
      videoIds: targetVideos.map((v) => v.id),
      workerId,
    });
  };

  const previewVideos = targetVideos.slice(0, 3);
  const remaining = Math.max(0, targetVideos.length - previewVideos.length);

  const title = isBulk
    ? `${targetVideos.length}개 영상 일괄 배정`
    : '작업 배정';

  const description = isBulk
    ? `선택한 ${targetVideos.length}개 영상에 작업자를 배정합니다.`
    : `영상 "${video.cctvName}"을(를) 배정할 작업자를 선택하세요.`;

  return (
    <Modal
      open
      onClose={onClose}
      title={title}
      description={description}
      footer={
        <>
          <Button variant="outline" onClick={onClose} disabled={isPending}>
            취소
          </Button>
          <Button
            variant="primary"
            onClick={handleSubmit}
            disabled={typeof workerId !== 'number' || isPending}
            loading={isPending}
          >
            {isBulk ? `${targetVideos.length}건 일괄 배정` : '배정'}
          </Button>
        </>
      }
    >
      {/* Bulk 모드 — 영상 미리보기 칩 */}
      {isBulk && (
        <div
          className="mb-4 rounded-lg bg-gray-50 px-3 py-3"
          data-testid="bulk-target-list"
        >
          <p className="mb-2 text-xs font-semibold uppercase tracking-wide text-gray-500">
            대상 영상 ({targetVideos.length}건)
          </p>
          <div className="flex flex-wrap gap-1.5">
            {previewVideos.map((v) => (
              <span
                key={v.id}
                className="inline-flex items-center rounded-full bg-blue-100 px-2 py-0.5 text-xs font-medium text-blue-700"
              >
                {v.cctvName}
              </span>
            ))}
            {remaining > 0 && (
              <span className="inline-flex items-center rounded-full bg-gray-100 px-2 py-0.5 text-xs font-medium text-gray-600">
                외 {remaining}건
              </span>
            )}
          </div>
          <p className="mt-2 text-xs text-gray-500">
            선택된 모든 영상에 동일한 작업자가 배정됩니다.
          </p>
        </div>
      )}

      {isLoading ? (
        <div className="flex justify-center py-6">
          <Spinner label="작업자 목록 로딩" />
        </div>
      ) : !workers || workers.length === 0 ? (
        <EmptyState message="배정 가능한 작업자가 없습니다" />
      ) : (
        <div
          role="radiogroup"
          aria-label="작업자 선택"
          className="flex max-h-72 flex-col gap-2 overflow-y-auto"
        >
          {workers.map((w) => (
            <Radio
              key={w.id}
              name="assign-worker"
              value={String(w.id)}
              label={w.name}
              checked={workerId === w.id}
              onChange={() => setWorkerId(w.id)}
            />
          ))}
        </div>
      )}
    </Modal>
  );
}
