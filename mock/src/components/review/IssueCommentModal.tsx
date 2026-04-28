import { useState, useEffect } from 'react';
import { Modal } from '../ui/Modal';
import { Button } from '../ui/Button';

interface Props {
  open: boolean;
  frameNo: number;
  onClose: () => void;
  onConfirm: (comment: string) => void;
}

export function IssueCommentModal({ open, frameNo, onClose, onConfirm }: Props) {
  const [comment, setComment] = useState('');

  // Reset on open
  useEffect(() => {
    if (open) setComment('');
  }, [open]);

  const handleConfirm = () => {
    if (!comment.trim()) return;
    onConfirm(comment.trim());
    setComment('');
  };

  return (
    <Modal
      open={open}
      onClose={onClose}
      title="이슈 추가"
      size="sm"
      footer={
        <>
          <Button variant="secondary" onClick={onClose}>취소</Button>
          <Button variant="primary" onClick={handleConfirm} disabled={!comment.trim()}>
            저장
          </Button>
        </>
      }
    >
      <div className="space-y-3">
        <div className="text-xs text-gray-500">
          프레임 <strong>{frameNo}</strong>
        </div>
        <div>
          <label className="text-sm font-medium text-gray-700 block mb-1">
            이슈 코멘트 <span className="text-red-500">*</span>
          </label>
          <textarea
            autoFocus
            value={comment}
            onChange={(e) => setComment(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); handleConfirm(); }
            }}
            rows={3}
            placeholder="이슈 내용을 입력하세요"
            className="w-full rounded-lg border border-gray-300 px-3 py-2 text-sm text-gray-900 focus:outline-none focus:ring-2 focus:ring-blue-500 resize-none"
          />
        </div>
      </div>
    </Modal>
  );
}
