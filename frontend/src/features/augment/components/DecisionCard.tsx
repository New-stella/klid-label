import { useState } from 'react';

import { Button } from '@/components/common/Button';
import { formatDateTime } from '@/features/review/formatDateTime';

import { type AugmentDecision, type AugmentDiscardState } from '../types';

import { RejectReasonModal } from './RejectReasonModal';

export interface DecisionCardProps {
  status: AugmentDecision;
  decidedAt?: string;
  rejectReason?: string;
  /**
   * 폐기(소프트 삭제) 축 — **반려 이후의 생명주기**. 결정 축(`status`)과 별개다.
   * 폐기 상태가 아니면(표식 없음 · 복구됨 · 해상도 파생) null/undefined 로 오며 안내를 그리지 않는다.
   */
  discard?: AugmentDiscardState | null;
  onAccept(): void;
  /** 사유는 RejectReasonModal에서 검증 후 전달 */
  onReject(reason: string): void;
  loading?: boolean;
}

/**
 * 폐기 유예 안내 — 반려된 결과물이 언제 실삭제되는지.
 *
 * 표시 규칙(전부 "없는 정보를 지어내지 않는다" 에서 나온다):
 * - `purged` : 이미 삭제됨(복구 불가) → 예정 일시 대신 그 사실만
 * - `purgeAt === null` : 폐기 스윕 비활성 → **일시를 만들어 보이지 않는다**(영원히 안 지워질 수 있다)
 * - `purgeAt` 존재 : 스윕이 주기 배치(기본 1시간)라 그 시각에 정확히 지워지지 않는다 → **"예정"** 으로 쓴다
 *
 * 복구 버튼은 여기 두지 않는다(별도 Phase). 보안: 값은 전부 BE 응답이며 JSX 자동 escape 로만 렌더한다.
 */
function DiscardNotice({ discard }: { discard: AugmentDiscardState }) {
  return (
    <div
      data-testid="decision-discard"
      data-purged={discard.purged ? 'true' : 'false'}
      className="mt-2 rounded border border-danger/30 bg-white p-2 text-sub text-neutral"
    >
      {discard.purged ? (
        <p>유예 기간이 지나 삭제되었습니다. 이 결과물은 복구할 수 없습니다.</p>
      ) : discard.purgeAt ? (
        <p data-testid="decision-discard-purge-at">
          유예 기간이 지나면 삭제됩니다. 삭제 예정:{' '}
          {formatDateTime(discard.purgeAt)} (예정 시각이며 실제 삭제는 이후 처리
          시점에 이뤄집니다.)
        </p>
      ) : (
        <p>폐기 처리되었습니다. 삭제 예정 일시는 정해져 있지 않습니다.</p>
      )}
    </div>
  );
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
  discard,
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

  if (status === 'CANCELED') {
    // 사용자 취소로 종결된 항목. 잡 집계(AugmentJobStatus)는 이를 "종료"로 세어 COMPLETED 를
    // 주므로, 취소가 "완료"로 보이지 않게 하는 보정은 이 표시 축의 책임이다.
    return (
      <div
        data-testid="decision-card"
        data-decision="CANCELED"
        className="rounded border border-border bg-bgLight p-3"
      >
        <div className="flex items-center gap-2">
          <span className="text-section-title text-gray-600">취소됨</span>
        </div>
        <p className="mt-1 text-sub text-neutral">
          사용자 요청으로 취소되어 이 결과는 활용되지 않습니다.
        </p>
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
        {discard && <DiscardNotice discard={discard} />}
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
