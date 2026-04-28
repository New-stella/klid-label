import { useState } from 'react';
import { AlertTriangle } from 'lucide-react';
import { Modal } from '../ui/Modal';
import { Button } from '../ui/Button';

interface RollbackModalProps {
  open: boolean;
  hash: string;
  onClose: () => void;
  onConfirm: (reason: string) => Promise<void>;
}

const MAX_REASON_LEN = 300;

export function RollbackModal({ open, hash, onClose, onConfirm }: RollbackModalProps) {
  const [reason, setReason] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  const hash7 = hash.substring(0, 7);

  const handleClose = () => {
    if (loading) return;
    setReason('');
    setError('');
    onClose();
  };

  const handleSubmit = async () => {
    const trimmed = reason.trim();
    if (!trimmed) {
      setError('롤백 사유를 입력해주세요.');
      return;
    }
    setError('');
    setLoading(true);
    try {
      await onConfirm(trimmed);
      setReason('');
      onClose();
    } catch {
      setError('롤백 요청에 실패했습니다. 다시 시도해주세요.');
    } finally {
      setLoading(false);
    }
  };

  return (
    <Modal
      open={open}
      onClose={handleClose}
      title="버전 롤백"
      size="md"
      footer={
        <>
          <Button variant="secondary" size="sm" onClick={handleClose} disabled={loading}>
            취소
          </Button>
          <Button
            variant="danger"
            size="sm"
            onClick={handleSubmit}
            loading={loading}
            disabled={!reason.trim()}
          >
            롤백 실행
          </Button>
        </>
      }
    >
      <div className="space-y-4">
        <p className="text-sm text-gray-700">
          커밋{' '}
          <code className="bg-gray-100 rounded px-1.5 py-0.5 text-xs font-mono text-gray-800">
            {hash7}
          </code>
          으로 롤백합니다. 현재 버전은 새 커밋으로 기록됩니다.
        </p>

        <div className="flex items-start gap-2 bg-amber-50 border border-amber-200 rounded-lg px-3 py-2.5">
          <AlertTriangle size={16} className="text-amber-500 shrink-0 mt-0.5" />
          <p className="text-xs text-amber-700">
            ⚠️ 롤백 후에도 이전 버전은 히스토리에 남아있습니다.
          </p>
        </div>

        <div>
          <label
            htmlFor="rollback-reason"
            className="block text-sm font-medium text-gray-700 mb-1"
          >
            롤백 사유 <span className="text-red-500">*</span>
          </label>
          <textarea
            id="rollback-reason"
            value={reason}
            onChange={(e) => {
              if (e.target.value.length <= MAX_REASON_LEN) setReason(e.target.value);
            }}
            placeholder="롤백 사유를 입력해주세요..."
            rows={4}
            className={[
              'w-full text-sm border rounded-lg px-3 py-2 focus:outline-none focus:ring-2 focus:ring-primary-500 resize-none',
              error ? 'border-red-400' : 'border-gray-300',
            ].join(' ')}
          />
          <div className="flex items-center justify-between mt-1">
            {error ? (
              <p className="text-xs text-red-500">{error}</p>
            ) : (
              <span />
            )}
            <span className="text-xs text-gray-400 ml-auto">
              {reason.length} / {MAX_REASON_LEN}
            </span>
          </div>
        </div>
      </div>
    </Modal>
  );
}

export default RollbackModal;
