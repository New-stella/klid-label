import { Modal } from '../ui/Modal';
import { Button } from '../ui/Button';

interface Props {
  open: boolean;
  onClose: () => void;
  onConfirm: () => void;
  isLoading?: boolean;
}

export function ApproveConfirm({ open, onClose, onConfirm, isLoading = false }: Props) {
  return (
    <Modal
      open={open}
      onClose={onClose}
      title="검수 승인"
      size="sm"
      footer={
        <>
          <Button variant="secondary" onClick={onClose} disabled={isLoading}>
            취소
          </Button>
          <Button
            variant="primary"
            onClick={onConfirm}
            loading={isLoading}
          >
            승인
          </Button>
        </>
      }
    >
      <p className="text-sm text-gray-700 leading-relaxed">
        이 작업을 승인하시겠습니까?<br />
        승인 후 데이터마트 등록 대상이 됩니다.
      </p>
    </Modal>
  );
}
