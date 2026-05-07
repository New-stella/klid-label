import { useState } from 'react';

import { Button } from '@/components/common/Button';
import { EmptyState } from '@/components/common/EmptyState';
import { Modal } from '@/components/common/Modal';
import { Radio } from '@/components/common/Radio';
import { Spinner } from '@/components/common/Spinner';
import { useWorkers } from '@/features/user/hooks/useWorkers';
import type { Video } from '@/features/video/types';
import { useUiStore } from '@/stores/useUiStore';

import { useAssignTask } from '../hooks/useAssignTask';

export interface AssignModalProps {
  video: Video;
  onClose(): void;
  onSuccess?(): void;
}

/**
 * SCR-TASK-002 작업 배정 모달.
 *
 * UI/UX §4-5 정합 — **작업자 라디오 선택만** 제공한다.
 * 우선순위·기한·메모 등 추가 입력은 절대 추가하지 않는다 (회귀 방지).
 *
 * 보안:
 * - workerId는 number 타입 검증 (Worker.id) → IDOR/Injection 방어
 * - 사용자 입력은 라디오 value (정수 ID)로만 전달
 */
export function AssignModal({ video, onClose, onSuccess }: AssignModalProps) {
  const [workerId, setWorkerId] = useState<number | null>(null);
  const { data: workers, isLoading } = useWorkers();
  const pushToast = useUiStore((s) => s.pushToast);

  const { mutate, isPending } = useAssignTask({
    onSuccess: () => {
      pushToast({ variant: 'success', message: '배정 완료' });
      onClose();
      onSuccess?.();
    },
    onError: () => {
      pushToast({ variant: 'error', message: '배정에 실패했습니다' });
    },
  });

  const handleSubmit = () => {
    if (typeof workerId !== 'number') return;
    mutate({ videoIds: [video.id], workerId });
  };

  return (
    <Modal
      open
      onClose={onClose}
      title="작업 배정"
      description={`영상 "${video.cctvName}"을(를) 배정할 작업자를 선택하세요.`}
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
            배정
          </Button>
        </>
      }
    >
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
