import { useState } from 'react';

import { Button } from '@/components/common/Button';
import { formatDateTime } from '@/features/review/formatDateTime';

import { type AugmentDecision, type AugmentDiscardState } from '../types';

import { RejectReasonModal } from './RejectReasonModal';
import { RestoreReasonModal } from './RestoreReasonModal';

export interface DecisionCardProps {
  status: AugmentDecision;
  decidedAt?: string;
  rejectReason?: string;
  /**
   * 폐기(소프트 삭제) 축 — **반려 이후의 생명주기**. 결정 축(`status`)과 별개다.
   * 폐기 상태가 아니면(표식 없음 · 복구됨 · 해상도 파생) null/undefined 로 오며 안내를 그리지 않는다.
   */
  discard?: AugmentDiscardState | null;
  /**
   * 복구 버튼 가시성 — **BE 가 자기 복구 사전조건으로 계산한 값**(`AugmentResult.restoreEligible`).
   *
   * 이 카드는 값을 **그대로** 쓰고 조건을 재유도하지 않는다. 미지정(구 BE)은 **그리지 않음**.
   */
  restoreEligible?: boolean;
  onAccept(): void;
  /** 사유는 RejectReasonModal에서 검증 후 전달 */
  onReject(reason: string): void;
  /**
   * 폐기(반려) 복구 — 사유는 RestoreReasonModal에서 검증 후 전달.
   * optional 로 두면 배선을 빠뜨렸을 때 버튼이 조용히 사라지므로 **필수**로 둔다.
   */
  onRestore(reason: string): void;
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
 * 보안: 값은 전부 BE 응답이며 JSX 자동 escape 로만 렌더한다.
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
  restoreEligible,
  onAccept,
  onReject,
  onRestore,
  loading,
}: DecisionCardProps) {
  const [rejectOpen, setRejectOpen] = useState(false);
  const [restoreOpen, setRestoreOpen] = useState(false);

  /**
   * 복구 버튼 가시성 — **BE 가 내려준 사전조건 판정을 그대로 쓴다** (Critical).
   *
   * 구 구현은 `status === 'REJECTED' && discard?.purged !== true` 로 **재유도**했는데, BE 의
   * `REJECTED` 는 세 입력(사람의 반려 · 폐기 표식 · **생성 영구 실패**)에서 나오고 복구 API 는
   * 앞 둘만 받는다. dead-letter 항목은 검수 행도 표식도 없어 **항상 404** 인데 버튼이 떴고,
   * 재조회해도 세 값이 그대로라 **무한 재시도**가 됐다(서버측 중복 차단이 없는 확정 정책).
   *
   * `discard.restorable` 은 **다른 축**이다 — 폐기 *표식* 수준 힌트라 표식이 없는 반려
   * (그랜드퍼더링)를 표현할 수 없다. 조건에 넣지 않는다.
   *
   * 미지정(구 BE)은 **그리지 않는다** — 반드시 실패하는 버튼보다 없는 편이 낫다(fail-closed).
   * 버튼을 그린 뒤 404/409 가 나는 것은 정상이며(BE 가 락 잡고 재판정) 처리는 호출부 몫이다.
   */
  const canRestore = status === 'REJECTED' && restoreEligible === true;

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
        {canRestore && (
          <>
            <div className="mt-3 flex justify-end">
              <Button
                variant="outline"
                size="sm"
                data-testid="decision-restore"
                onClick={() => setRestoreOpen(true)}
                disabled={loading}
              >
                복구
              </Button>
            </div>
            <RestoreReasonModal
              open={restoreOpen}
              loading={loading}
              onClose={() => setRestoreOpen(false)}
              onConfirm={(reason) => {
                setRestoreOpen(false);
                onRestore(reason);
              }}
            />
          </>
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
