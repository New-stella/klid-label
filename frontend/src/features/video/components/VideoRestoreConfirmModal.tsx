// 영상 복원 확인 팝업. [@design SCREEN-008] [@design API-261] [@design ADR-069]
//
// ★**사유를 받지 않는다.** 「왜」는 감추는 행위에 필요한 것이고, 되돌리는 쪽은 사유가 없어도
//   사실이 왜곡되지 않는다. 이력에는 누가·언제가 남는다.
//   ⚠ 「제외 팝업과 모양을 맞추자」는 이유로 사유 칸을 붙이지 말 것 — 두 창구의 모양이 다른
//   것이 의도이고, 서버 쪽 복원 요청에는 본문 자체가 없다.

import { Button } from '@/components/common/Button';
import { Modal } from '@/components/common/Modal';

export interface VideoRestoreConfirmModalProps {
  open: boolean;
  /** 대상 영상 이름 — 무엇을 되돌리는지 보인다. */
  videoName: string;
  loading?: boolean;
  onClose(): void;
  onConfirm(): void;
}

export function VideoRestoreConfirmModal({
  open,
  videoName,
  loading,
  onClose,
  onConfirm,
}: VideoRestoreConfirmModalProps) {
  const handleClose = () => {
    if (loading) return;
    onClose();
  };

  return (
    <Modal
      open={open}
      onClose={handleClose}
      title="영상 복원"
      size="sm"
      footer={
        <>
          <Button variant="secondary" size="sm" onClick={handleClose} disabled={loading}>
            취소
          </Button>
          <Button
            variant="primary"
            size="sm"
            onClick={onConfirm}
            disabled={loading}
            loading={loading}
            data-testid="video-restore-confirm"
          >
            복원
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-3" data-testid="video-restore-confirm-modal">
        <div className="rounded-lg bg-gray-50 px-4 py-3">
          <p className="text-label font-semibold uppercase tracking-wide text-gray-600">
            대상 영상
          </p>
          <p className="mt-1 text-body-md text-gray-800">{videoName}</p>
        </div>
        <p className="text-body-md text-gray-700">이 영상을 다시 목록에 표시합니다.</p>
      </div>
    </Modal>
  );
}

export default VideoRestoreConfirmModal;
