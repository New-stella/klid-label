import { useRef, useState } from 'react';

import { Button } from '@/components/common/Button';
import { ProgressBar } from '@/components/common/ProgressBar';
import { Role } from '@/lib/api/types';
import { roleSatisfies } from '@/lib/authz';
import { useAuthStore } from '@/stores/useAuthStore';
import { useUiStore } from '@/stores/useUiStore';

import { useCancelAugment } from '../hooks/useAugmentDecision';
import { useAugmentProgress } from '../hooks/useAugmentProgress';
import { useResultRefreshOnProgressTerminal } from '../hooks/useResultRefreshOnProgressTerminal';
import {
  type AugmentCancelResult,
  type AugmentProgressStatus,
  type AugmentProgressUnavailableReason,
} from '../types';

import { AugmentCancelModal } from './AugmentCancelModal';

/** 진행 상태 문구 — 기술 코드가 아니라 사용자 언어로. */
const STATUS_LABEL: Record<AugmentProgressStatus, string> = {
  RECEIVED: '접수됨',
  RUNNING: '처리 중',
  SUCCEEDED: '완료',
  FAILED: '실패',
  CANCELED: '취소됨',
};

/**
 * 진행률을 산출하지 못한 사유별 안내 — **넷을 같은 모양으로 표시하면 안 된다**.
 * NOOP 은 배포 기본값이라 오류로 표시하면 사용자가 영원히 에러를 보고,
 * TRANSIENT_ERROR 를 정상처럼 표시하면 진짜 장애를 운영이 인지하지 못한다.
 */
const UNAVAILABLE_TEXT: Record<
  AugmentProgressUnavailableReason,
  { title: string; detail: string }
> = {
  NOOP: {
    title: '외부 증강 시스템 미연동',
    detail: '현재 환경은 외부 증강 시스템과 연동되어 있지 않아 진행률을 표시하지 않습니다.',
  },
  TRANSIENT_ERROR: {
    title: '일시적으로 진행률을 가져오지 못했습니다',
    detail: '작업은 계속 진행 중이며, 잠시 후 자동으로 다시 조회합니다.',
  },
  AWAITING_ACK: {
    title: '접수 확인 중',
    detail: '외부 시스템의 접수 확인을 기다리고 있습니다.',
  },
  QUERY_LIMIT_EXCEEDED: {
    title: '조회 대상이 많아 진행률을 표시할 수 없습니다',
    detail: '작업은 정상 진행 중이며, 진행률만 잠시 표시되지 않습니다.',
  },
};

/**
 * 알 수 없는 사유의 폴백 — BE 가 5번째 사유를 추가해도 **안내와 진행률이 통째로 사라지지 않게** 한다.
 *
 * 구 구현은 `UNAVAILABLE_TEXT[알 수 없는 값]` 이 `undefined` 인 채로 `unavailable === null` 검사만
 * 해서, 사유가 온 순간 안내문도 진행률 바도 렌더되지 않았다(무음 저하 = fail-open).
 * 진행률을 신뢰할 수 없다는 사실 자체는 서버가 이미 알려줬으므로 그 사실만 정직하게 전한다.
 */
const UNKNOWN_UNAVAILABLE = {
  title: '진행률을 표시할 수 없습니다',
  detail: '작업은 계속 진행 중이며, 진행률만 표시되지 않습니다.',
};

export interface AugmentProgressPanelProps {
  /** 결과 항목 id (= 진행상태/취소 API 의 path param) */
  augmentId: number;
  /** 폴링 대상인지 — 해상도 파생(RESL_*)은 외부 위탁이 없어 BE 가 400 을 준다 */
  enabled: boolean;
}

/**
 * 항목별 진행 상태 + 요청 취소.
 *
 * - 진행률은 **서버 실값**만 그린다(가짜 0/50/100 금지). 산출 불가면 사유별 안내로 대체한다.
 * - 폴링 주기는 서버 권고(`nextPollAfterMs`)를 따르고 종결되면 스스로 멈춘다.
 * - 취소는 REVIEWER 전용(BE 403) + 서버가 `cancelable=true` 를 준 경우에만 노출한다.
 */
export function AugmentProgressPanel({ augmentId, enabled }: AugmentProgressPanelProps) {
  const role = useAuthStore((s) => s.claims?.role);
  const pushToast = useUiStore((s) => s.pushToast);
  const [modalOpen, setModalOpen] = useState(false);
  const [cancelResult, setCancelResult] = useState<AugmentCancelResult | null>(null);

  const { data, isError, isFetching, refetch } = useAugmentProgress(augmentId, enabled);

  /**
   * 진행 상태가 종결이면 결과 조회를 갱신한다 — 화면의 "자동 갱신"은 이 배선이 전부다.
   * 이게 없으면 여기서 "완료 100%" 를 그리는 동안 같은 화면 위쪽 배너는 "증강 처리 중" 으로 남는다.
   */
  useResultRefreshOnProgressTerminal(data?.status);

  /** 연타 동기 락 — 리렌더보다 먼저 잠기므로 같은 tick 의 연속 클릭도 첫 건만 통과한다. */
  const cancelLockRef = useRef(false);
  const cancel = useCancelAugment({
    onSuccess: (result) => {
      setCancelResult(result);
      pushToast({
        // 부분 취소(canceled && !fullyCanceled)는 성공이 아니다 — 남은 청크는 외부에서 계속
        // 처리될 수 있다. 초록 토스트로 알리면 바로 아래 warning 카드와 신호가 어긋난다.
        variant: result.canceled
          ? result.fullyCanceled
            ? 'success'
            : 'warning'
          : 'info',
        message: result.message,
      });
    },
    onError: () =>
      pushToast({ variant: 'error', message: '취소 요청을 처리하지 못했습니다.' }),
    onSettled: () => {
      cancelLockRef.current = false;
    },
  });

  const handleConfirm = (reason?: string) => {
    if (cancelLockRef.current) return;
    cancelLockRef.current = true;
    setModalOpen(false);
    cancel.mutate({ id: augmentId, reason });
  };

  if (!enabled) return null;

  // 검수자 자리 — 관리자는 계층으로 함께 들어온다(판정은 `@/lib/authz` 소유).
  const canCancel = roleSatisfies(role, Role.REVIEWER) && data?.cancelable === true;
  const unavailable = data?.unavailableReason
    ? (UNAVAILABLE_TEXT[data.unavailableReason] ?? UNKNOWN_UNAVAILABLE)
    : null;
  const showBar = data != null && data.progress !== null && unavailable === null;
  /**
   * 지금 취소가 전달될 청크 수 = 전체 − 종결분.
   * `totalJobCount` 는 **종결분을 포함한 위탁 청크 총 수**라 그대로 "진행 중" 이라고 말하면
   * 확인 모달(10건)과 취소 응답(`targetJobCount` 2건)이 같은 화면에서 서로 모순된다.
   */
  const activeJobCount = data
    ? Math.max(0, data.totalJobCount - data.terminalJobCount)
    : undefined;

  return (
    <section
      data-testid={`augment-progress-${augmentId}`}
      aria-label="증강 진행 상태"
      className="rounded border border-border bg-white p-3"
    >
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="flex items-center gap-2">
          <h3 className="text-sub font-semibold text-gray-600">진행 상태</h3>
          {data && (
            <span
              data-testid="augment-progress-status"
              data-status={data.status}
              className="text-sub text-gray-700"
            >
              {STATUS_LABEL[data.status] ?? '확인 중'}
            </span>
          )}
        </div>
        {canCancel && (
          <Button
            variant="outline"
            size="sm"
            data-testid={`augment-cancel-${augmentId}`}
            onClick={() => setModalOpen(true)}
            disabled={cancel.isPending}
          >
            요청 취소
          </Button>
        )}
      </div>

      {showBar && data && (
        <div className="mt-3 space-y-1.5">
          <div className="flex items-center justify-between text-sub text-gray-500">
            <span>진행률</span>
            <span
              data-testid="augment-progress-value"
              className="font-medium tabular-nums"
            >
              {data.progress}%
            </span>
          </div>
          <ProgressBar
            value={data.progress ?? 0}
            tone={data.status === 'FAILED' ? 'danger' : 'primary'}
            size="md"
          />
          {data.totalJobCount > 0 && (
            <p className="text-sub text-gray-500">
              처리 완료 {data.terminalJobCount.toLocaleString('ko-KR')} /{' '}
              {data.totalJobCount.toLocaleString('ko-KR')}건
            </p>
          )}
        </div>
      )}

      {unavailable && data && (
        <p
          data-testid="augment-progress-unavailable"
          data-reason={data.unavailableReason ?? undefined}
          className="mt-2 text-sub text-gray-600"
        >
          <span className="font-medium text-gray-700">{unavailable.title}</span>
          {' — '}
          {unavailable.detail}
        </p>
      )}

      {/* 조회 실패 — 자동 폴링은 멈춘 상태다(무한 재요청 금지). 되살릴 수단을 함께 준다. */}
      {isError && (
        <div
          className="mt-2 flex flex-wrap items-center gap-2"
          data-testid={`augment-progress-error-${augmentId}`}
        >
          <p className="text-sub text-gray-500">
            {data
              ? '진행 상태 자동 갱신이 중단되었습니다 — 위 값은 마지막으로 확인된 정보입니다.'
              : '진행 상태를 불러오지 못했습니다. 결과 확인에는 영향이 없습니다.'}
          </p>
          <Button
            variant="outline"
            size="sm"
            data-testid={`augment-progress-retry-${augmentId}`}
            onClick={() => {
              void refetch();
            }}
            disabled={isFetching}
          >
            다시 시도
          </Button>
        </div>
      )}

      {cancelResult && <CancelResultNotice result={cancelResult} />}

      <AugmentCancelModal
        open={modalOpen}
        loading={cancel.isPending}
        activeJobCount={activeJobCount}
        onClose={() => setModalOpen(false)}
        onConfirm={handleConfirm}
      />
    </section>
  );
}

/**
 * 취소 결과 안내 — **부분 취소를 드러낸다**.
 *
 * 문구의 정본은 BE `message` 다. 재시도 동선을 임의로 안내하지 않는다 — 증강이 이미 종결이라
 * 재요청하면 "이미 종결된 증강" 멱등 응답만 나오는, 수행 불가능한 안내가 된다.
 */
function CancelResultNotice({ result }: { result: AugmentCancelResult }) {
  const partial = result.canceled && !result.fullyCanceled;
  return (
    <div
      data-testid="augment-cancel-result"
      role="status"
      className={`mt-3 rounded border p-3 text-sub ${
        partial
          ? 'border-warning/40 bg-warning/10 text-warning-700'
          : 'border-border bg-bgLight text-gray-700'
      }`}
    >
      <p className="font-medium">{result.message}</p>
      {partial && (
        <p className="mt-1">
          진행 중이던 {result.targetJobCount.toLocaleString('ko-KR')}건 중{' '}
          {result.canceledJobCount.toLocaleString('ko-KR')}건만 외부 시스템에 취소가
          전달되었습니다. 나머지는 외부에서 계속 처리될 수 있으나 그 결과는 반영되지
          않습니다.
        </p>
      )}
    </div>
  );
}
