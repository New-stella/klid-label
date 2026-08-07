import { ConfirmDialog } from '@/components/common/ConfirmDialog';
import { busyRejectedMessage } from '@/features/label/hooks/useBusyTask';
import { isEditBlockedNow, useLabelStore } from '@/stores/useLabelStore';
import { useUiStore } from '@/stores/useUiStore';

import { useRollback } from '../hooks/useRollback';

interface RollbackConfirmModalProps {
  open: boolean;
  commitSha: string;
  shortHash: string;
  /**
   * 롤백 대상 **프레임**(LS_DATA_SRC.SRC_SN).
   *
   * ⚠ 구 prop 명은 `videoId` 였는데 호출부가 실제로 넘기던 값은 `srcSn` 이었다 — 이름이 영상
   *   PK 를 가리켜 읽는 사람을 오도했다. 사양(UI-069)도 `srcSn` 이다.
   */
  srcSn: number;
  onClose: () => void;
  onSuccess: () => void;
}

/**
 * 롤백 확인 모달 (SCR-HIST-002).
 *
 * danger 변형 ConfirmDialog 재사용 — 확인 시 useRollback mutation 트리거.
 * 성공 시 onSuccess 호출 → 호출부(HistoryPanel)가 LABEL_KEYS 재조회 트리거.
 *
 * ★확정 롤백 시맨틱 = **대상 스냅샷 행을 재활성**한다(새 버전 행을 적층하지 않는다). 구 확인 문구
 * "현재 변경사항은 새 커밋으로 보존됩니다" 는 **폐기된 구 동작**을 설명해 사용자에게 서버가 하지
 * 않는 일을 기대하게 만들었다 — 되돌려 넣지 말 것.
 *
 * 보안: REVIEWER 권한 + commit SHA 검증은 BE에서 수행. UI 노출 차단은 HistoryPanel 책임.
 */
export function RollbackConfirmModal({
  open,
  commitSha,
  shortHash,
  srcSn,
  onClose,
  onSuccess,
}: RollbackConfirmModalProps) {
  const mutation = useRollback(srcSn);

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
    mutation.mutate({ commitSha, srcSn }, {
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
      description={`선택한 버전 ${shortHash}의 라벨로 되돌립니다. 이 버전을 다시 활성화하는 것이며 새 버전이 추가되지는 않습니다. 저장하지 않은 현재 변경사항은 사라집니다.`}
      confirmLabel="롤백"
      variant="danger"
      loading={mutation.isPending}
      onConfirm={handleConfirm}
      onCancel={onClose}
    />
  );
}
