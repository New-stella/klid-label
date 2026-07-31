import { ConfirmDialog } from '@/components/common/ConfirmDialog';
import { busyRejectedMessage } from '@/features/label/hooks/useBusyTask';
import { isEditBlockedNow, useLabelStore } from '@/stores/useLabelStore';
import { useUiStore } from '@/stores/useUiStore';

import { useRollback } from '../hooks/useRollback';

interface RollbackConfirmModalProps {
  open: boolean;
  commitSha: string;
  shortHash: string;
  videoId: number;
  onClose: () => void;
  onSuccess: () => void;
}

/**
 * 롤백 확인 모달 (SCR-HIST-002).
 *
 * danger 변형 ConfirmDialog 재사용 — 확인 시 useRollback mutation 트리거.
 * 성공 시 onSuccess 호출 → HistoryPage가 LABEL_KEYS 재조회 트리거.
 *
 * 보안: REVIEWER 권한 + commit SHA 검증은 BE에서 수행. UI 노출 차단은 HistoryPage 책임.
 */
export function RollbackConfirmModal({
  open,
  commitSha,
  shortHash,
  videoId,
  onClose,
  onSuccess,
}: RollbackConfirmModalProps) {
  const mutation = useRollback(videoId);

  function handleConfirm() {
    // 이중 방어 — 모달이 열린 뒤에 장시간 작업이 시작될 수 있다. 롤백은 서버측 라벨 재작성이라
    // 저장 PUT in-flight 와 교차 실행되면 최종본이 결정되지 않는다(fail-closed: 막는 쪽).
    if (isEditBlockedNow()) {
      useUiStore.getState().pushToast({
        variant: 'warning',
        message: busyRejectedMessage(useLabelStore.getState().busy?.kind ?? null),
      });
      onClose();
      return;
    }
    mutation.mutate({ commitSha, srcSn: videoId }, {
      onSuccess: () => {
        onSuccess();
        onClose();
      },
    });
  }

  return (
    <ConfirmDialog
      open={open}
      title="이 버전으로 롤백하시겠습니까?"
      description={`커밋 ${shortHash}의 라벨로 되돌립니다. 현재 변경사항은 새 커밋으로 보존됩니다.`}
      confirmLabel="롤백"
      variant="danger"
      loading={mutation.isPending}
      onConfirm={handleConfirm}
      onCancel={onClose}
    />
  );
}
