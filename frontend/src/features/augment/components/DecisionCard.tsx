import { useState } from 'react';

import { Button } from '@/components/common/Button';

import { type AugmentDecision } from '../types';
import { RejectReasonModal } from './RejectReasonModal';

export interface DecisionCardProps {
  status: AugmentDecision;
  decidedAt?: string;
  rejectReason?: string;
  onAccept(): void;
  /** 사유는 RejectReasonModal에서 검증 후 전달 */
  onReject(reason: string): void;
  loading?: boolean;
}

/**
 * 활용 결정 카드 — UI/UX §4-12.
 * - PENDING: [채택]/[거부] 버튼 노출
 * - ACCEPTED: 결정 일시 표시 (변경 불가)
 * - REJECTED: 거부 사유 표시 (변경 불가)
 *
 * 보안: rejectReason / decidedAt은 BE 응답값. React가 자동 escape (XSS 방어).
 */
export function DecisionCard({
  status,
  decidedAt,
  rejectReason,
  onAccept,
  onReject,
  loading,
}: DecisionCardProps) {
  const [rejectOpen, setRejectOpen] = useState(false);

  if (status === 'ACCEPTED') {
    return (
      <div
        data-testid="decision-card"
        data-decision="ACCEPTED"
        className="rounded border border-success bg-success/5 p-3"
      >
        <div className="flex items-center gap-2">
          <span className="text-section-title text-success">채택됨</span>
        </div>
        {decidedAt && (
          <p className="mt-1 text-sub text-neutral">
            결정 일시: {new Date(decidedAt).toLocaleString('ko-KR')}
          </p>
        )}
      </div>
    );
  }

  if (status === 'REJECTED') {
    return (
      <div
        data-testid="decision-card"
        data-decision="REJECTED"
        className="rounded border border-danger bg-danger/5 p-3"
      >
        <div className="flex items-center gap-2">
          <span className="text-section-title text-danger">거부됨</span>
        </div>
        {decidedAt && (
          <p className="mt-1 text-sub text-neutral">
            결정 일시: {new Date(decidedAt).toLocaleString('ko-KR')}
          </p>
        )}
        {rejectReason && (
          <p
            data-testid="decision-reject-reason"
            className="mt-2 text-body text-primary whitespace-pre-wrap break-words"
          >
            거부 사유: {rejectReason}
          </p>
        )}
      </div>
    );
  }

  // PENDING
  return (
    <div
      data-testid="decision-card"
      data-decision="PENDING"
      className="flex items-center justify-between rounded border border-border bg-white p-3"
    >
      <span className="text-body text-primary">활용 결정 대기</span>
      <div className="flex items-center gap-2">
        <Button
          variant="primary"
          size="sm"
          onClick={onAccept}
          disabled={loading}
          loading={loading}
        >
          채택
        </Button>
        <Button
          variant="danger"
          size="sm"
          onClick={() => setRejectOpen(true)}
          disabled={loading}
        >
          거부
        </Button>
      </div>
      <RejectReasonModal
        open={rejectOpen}
        loading={loading}
        onClose={() => setRejectOpen(false)}
        onConfirm={(reason) => {
          setRejectOpen(false);
          onReject(reason);
        }}
      />
    </div>
  );
}
