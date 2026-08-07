import { useState } from 'react';

import { Field, FieldError, FieldLabel } from '@/components/common/Field';
import { Button } from '@/components/common/Button';
import { Modal } from '@/components/common/Modal';
import { Textarea } from '@/components/common/Textarea';

import { AUGMENT_CANCEL_REASON_MAX_LENGTH } from '../types';

export interface AugmentCancelModalProps {
  open: boolean;
  loading?: boolean;
  /**
   * **아직 종결되지 않은** 위탁 청크 수 = `totalJobCount - terminalJobCount` (0 이면 표시 생략).
   *
   * 진행률 응답의 `totalJobCount`(종결분 포함 총 수)를 그대로 넘기면 안 된다 — BE 취소 대상은
   * 비종결 청크뿐이라, 모달이 "10건" 이라 말한 뒤 취소 응답이 "2건 중" 이라 말하는 자기모순이 된다.
   */
  activeJobCount?: number;
  onClose(): void;
  /** 사유는 선택 — 비어 있으면 undefined 로 전달한다(BE 도 optional) */
  onConfirm(reason?: string): void;
}

/**
 * 증강 요청 취소 확인 모달.
 *
 * 취소는 **되돌릴 수 없다**(취소 후 재요청은 새 증강이며, 이미 종결된 증강은 멱등 200 만 온다).
 * 그래서 버튼 클릭 즉시 전송하지 않고 확인 단계를 둔다 — 거부 사유 모달(`RejectReasonModal`)과
 * 같은 패턴이되, 사유는 **선택**이다(BE `AugmentCancelRequest#reason` optional).
 */
export function AugmentCancelModal({
  open,
  loading,
  activeJobCount,
  onClose,
  onConfirm,
}: AugmentCancelModalProps) {
  const [reason, setReason] = useState('');

  const tooLong = reason.length > AUGMENT_CANCEL_REASON_MAX_LENGTH;

  const handleClose = () => {
    setReason('');
    onClose();
  };

  const handleConfirm = () => {
    if (tooLong) return;
    const trimmed = reason.trim();
    setReason('');
    onConfirm(trimmed === '' ? undefined : trimmed);
  };

  return (
    <Modal
      open={open}
      onClose={handleClose}
      title="증강 요청을 취소할까요?"
      description="취소하면 이 요청은 종결되며 되돌릴 수 없습니다. 이미 외부 시스템이 처리 중인 작업의 결과는 반영되지 않습니다."
      size="md"
    >
      <div className="flex flex-col gap-3" data-testid="augment-cancel-modal">
        {activeJobCount !== undefined && activeJobCount > 0 && (
          <p className="text-sub text-gray-600" data-testid="augment-cancel-target-count">
            진행 중인 작업 {activeJobCount.toLocaleString('ko-KR')}건에 취소를 전달합니다.
          </p>
        )}
        <Field>
          <FieldLabel>취소 사유 (선택)</FieldLabel>
          <Textarea
            className="min-h-[127px]"
            value={reason}
            onChange={(e) => setReason(e.target.value)}
            maxLength={AUGMENT_CANCEL_REASON_MAX_LENGTH}
          />
          <FieldError>
            {tooLong ? `최대 ${AUGMENT_CANCEL_REASON_MAX_LENGTH}자` : undefined}
          </FieldError>
        </Field>
        <div className="flex justify-end gap-2">
          <Button variant="outline" onClick={handleClose} disabled={loading}>
            닫기
          </Button>
          <Button
            variant="danger"
            onClick={handleConfirm}
            disabled={loading || tooLong}
            loading={loading}
          >
            취소 확정
          </Button>
        </div>
      </div>
    </Modal>
  );
}
