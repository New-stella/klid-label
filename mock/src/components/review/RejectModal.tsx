import { useState } from 'react';
import { Modal } from '../ui/Modal';
import { Button } from '../ui/Button';
import type { ReviewIssue } from '../../types/review';

interface Props {
  open: boolean;
  onClose: () => void;
  onConfirm: (reason: string, issues: ReviewIssue[]) => void;
  issues: ReviewIssue[];
  workerName: string;
  isLoading?: boolean;
}

const MAX_REASON = 500;

export function RejectModal({ open, onClose, onConfirm, issues, workerName: _workerName, isLoading = false }: Props) {
  const [reason, setReason] = useState('');

  const handleConfirm = () => {
    if (!reason.trim()) return;
    onConfirm(reason.trim(), issues);
  };

  const handleClose = () => {
    setReason('');
    onClose();
  };

  return (
    <Modal
      open={open}
      onClose={handleClose}
      title="작업 반려"
      size="md"
      footer={
        <>
          <Button variant="secondary" onClick={handleClose} disabled={isLoading}>
            취소
          </Button>
          <Button
            variant="danger"
            onClick={handleConfirm}
            disabled={!reason.trim() || isLoading}
            loading={isLoading}
          >
            반려
          </Button>
        </>
      }
    >
      <div className="space-y-4">
        {/* Issue summary */}
        <div className="bg-orange-50 border border-orange-200 rounded-lg px-3 py-2 text-sm text-orange-700">
          현재 <strong>{issues.length}개</strong> 이슈 마킹됨
        </div>

        {/* Reason */}
        <div>
          <div className="flex items-center justify-between mb-1">
            <label className="text-sm font-medium text-gray-700">
              반려 사유 <span className="text-red-500">*</span>
            </label>
            <span className="text-xs text-gray-400">{reason.length}/{MAX_REASON}</span>
          </div>
          <textarea
            value={reason}
            onChange={(e) => setReason(e.target.value.slice(0, MAX_REASON))}
            rows={4}
            placeholder="반려 사유를 구체적으로 입력하세요"
            className="w-full rounded-lg border border-gray-300 px-3 py-2 text-sm text-gray-900 focus:outline-none focus:ring-2 focus:ring-red-500 resize-none"
          />
          {!reason.trim() && (
            <p className="text-xs text-red-500 mt-1">반려 사유를 입력하세요</p>
          )}
        </div>
      </div>
    </Modal>
  );
}
