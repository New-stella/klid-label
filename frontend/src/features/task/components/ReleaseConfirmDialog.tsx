// 배정 해제 확인 창. [@design SCREEN-012] [@design API-259] [@design AC-1123]
//
// ★**기존 배정 모달의 네 번째 모드로 합치지 않는다.** 재배정은 「담당을 바꾼다」이고 해제는
//   「배정을 없앤다」라 뜻이 다르며, 그 차이를 <b>화면 구조로도</b> 드러낸다. 한 창으로 합치면
//   두 행위가 같은 것으로 보이고, 작업자 선택 칸이 있는 창에서 「선택하지 않음」이 곧 해제인 것처럼
//   읽히는 자리가 생긴다.
//
// ★**사유를 받지 않는다** — 되돌리기 쉽고(다시 배정) 작업 결과가 보존된다. 제외·복원·해제 셋이
//   「감추는 쪽만 사유를 남긴다」는 한 원칙으로 모인다.
//
// ⚠ 검수 단계에 들어간 배정(검수 대기·검수 중·승인)은 목록에서 버튼이 이미 비활성이라 이 창에
//   **도달하지 않는다**(`unassignEligibility`). 그래서 여기서 그 상태를 다시 판정하지 않는다 —
//   판정을 두 곳에 두면 한쪽만 바뀐다.

import { Button } from '@/components/common/Button';
import { Modal } from '@/components/common/Modal';

export interface ReleaseConfirmDialogProps {
  open: boolean;
  /** 해제 대상 영상 이름. */
  videoName: string;
  /** 지금 배정된 작업자 이름 — 무엇을 푸는지 사람 단위로 보인다. */
  workerName: string;
  loading?: boolean;
  onClose(): void;
  onConfirm(): void;
}

export function ReleaseConfirmDialog({
  open,
  videoName,
  workerName,
  loading,
  onClose,
  onConfirm,
}: ReleaseConfirmDialogProps) {
  const handleClose = () => {
    if (loading) return;
    onClose();
  };

  return (
    <Modal
      open={open}
      onClose={handleClose}
      title="배정을 해제할까요?"
      size="sm"
      footer={
        <>
          <Button variant="outline" size="sm" onClick={handleClose} disabled={loading}>
            취소
          </Button>
          <Button
            variant="danger"
            size="sm"
            onClick={onConfirm}
            disabled={loading}
            loading={loading}
            data-testid="release-confirm"
          >
            배정 해제
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-3" data-testid="release-confirm-dialog">
        <div className="rounded-lg bg-gray-50 px-4 py-3">
          <p className="text-label font-semibold uppercase tracking-wide text-gray-600">
            대상 영상 / 현재 작업자
          </p>
          <p className="mt-1 text-body-md text-gray-800">{videoName}</p>
          <p className="text-body-md text-gray-700">{workerName}</p>
        </div>
        {/* 해제가 **배정만** 푼다는 것을 확인 단계에서 알린다 — 라벨까지 사라진다고 오해하면
            눌러야 할 사람이 누르지 못한다. 되돌리는 방법도 함께 말한다(해제 취소 창구는 없다). */}
        <p className="text-body-md text-gray-700" data-testid="release-keeps-labels-notice">
          해제해도 라벨과 그 이력은 남습니다. 되돌리려면 다시 배정하세요.
        </p>
      </div>
    </Modal>
  );
}

export default ReleaseConfirmDialog;
