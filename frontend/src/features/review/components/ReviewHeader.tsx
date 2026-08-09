// SCR-REVIEW-002 — 검수 화면 상단 헤더.
//
// 좌측: [X 닫기] [영상명/영상 번호 · 작업자/제출일]
// 우측: [상태 배지] [반려] [승인]
//
// 화면 사양(SCREEN-019 §검수 헤더) 정합:
// - 헤더에는 프레임 위치 표시를 두지 않는다 — 프레임 이동과 위치 표시는 별도 프레임 이동 영역이
//   단독으로 담당한다(진입점이 둘로 갈리지 않게).
// - 승인·반려는 이 헤더가 단독으로 담당한다(하단 액션 바를 별도로 두지 않는다) —
//   같은 액션이 두 곳에 있으면 상태별 활성 조건과 진행 표시 판정이 갈린다.
// - 승인·반려는 검수대기(REVIEW_PENDING)·검수중(REVIEWING) 일 때만 활성이고,
//   이미 처리된 검수(완료·반려)는 비활성 + 사유 안내를 함께 노출한다.
// - Phase 7b 예외: 승인 후 라벨/메타 수정으로 재검토 표시(needsRecheck)가 선 완료(COMPLETED)
//   영상은 승인 버튼만 다시 활성화된다. 반려 버튼은 이 예외와 무관하게 그대로 비활성이다.
// - 두 액션은 동시에 진행되지 않는다 — 한 쪽이 진행 중이면 다른 쪽도 비활성이다.
//
// UI/UX §4-9 정합:
// - 라이트 배경 (앱 전역 라이트 테마와 동일 — 목록/관리 화면 관례)
// - 좌표 마커 절대 사용 금지 (회귀 방지)

import { X } from 'lucide-react';

import { Button } from '@/components/common/Button';
import { StatusBadge } from '@/components/common/StatusBadge';
import { KRDS_ICON_HIT_AREA } from '@/lib/focusRing';

import type { ReviewStatus } from '../types';

export interface ReviewHeaderProps {
  videoId: number;
  cctvName: string;
  workerName: string;
  submittedAt: string; // ISO-8601
  status: ReviewStatus;
  /**
   * Phase 7b — 검수 승인 이후 라벨/메타가 수정되어 재검토가 필요한가(BE V177 REVLT_YN).
   * `true` 이고 `status==='COMPLETED'` 이면 승인 버튼이 다시 활성화된다. 반려 버튼은
   * 이 값과 무관하게 완료 상태에서 그대로 비활성이다(데이터마트 노출이 승인 상태로
   * 게이팅되어, 반려로 승인 상태를 내리면 이미 통지된 영상 행이 관제에서 사라진다).
   */
  needsRecheck?: boolean;
  /** 승인 요청 진행 중 — 진행 표시 + 두 액션 비활성을 함께 유발한다. */
  isApproving?: boolean;
  /** 반려 요청 진행 중 — 진행 표시 + 두 액션 비활성을 함께 유발한다. */
  isRejecting?: boolean;
  onClose: () => void;
  /** 승인. 미지정 시 승인 버튼을 렌더하지 않는다. */
  onApprove?: () => void;
  /** 반려. 미지정 시 반려 버튼을 렌더하지 않는다. */
  onReject?: () => void;
}

/**
 * `2026-05-07T10:00:00Z` → `2026-05-07 19:00` (Local Time, ko-KR).
 *
 * 보안: 입력은 ISO-8601 문자열만 받고 직접 DOM 삽입 없음 (XSS 방어).
 */
function formatSubmittedAt(iso: string): string {
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return iso;
  const pad = (n: number) => String(n).padStart(2, '0');
  const y = date.getFullYear();
  const m = pad(date.getMonth() + 1);
  const d = pad(date.getDate());
  const hh = pad(date.getHours());
  const mm = pad(date.getMinutes());
  return `${y}-${m}-${d} ${hh}:${mm}`;
}

export function ReviewHeader({
  videoId,
  cctvName,
  workerName,
  submittedAt,
  status,
  needsRecheck = false,
  isApproving = false,
  isRejecting = false,
  onClose,
  onApprove,
  onReject,
}: ReviewHeaderProps) {
  const formattedDate = formatSubmittedAt(submittedAt);

  // 이미 처리된 검수는 재판정할 수 없다 — 단 아래 canReapprove 가 승인만 예외로 다시 연다.
  const isFinal = status === 'COMPLETED' || status === 'REJECTED';
  // 승인 후 수정으로 재검토 표시가 선 완료(COMPLETED) 영상만 승인을 다시 허용한다.
  // 반려는 이 조건과 무관하게 완료 상태에서 항상 잠근다(위 프로퍼티 문서 참조).
  const canReapprove = status === 'COMPLETED' && needsRecheck;
  // 두 액션은 여전히 서로를 본다 — 한 쪽이 진행 중이면 다른 쪽도 잠근다.
  const approveDisabled = (isFinal && !canReapprove) || isApproving || isRejecting;
  const rejectDisabled = isFinal || isApproving || isRejecting;

  const finalMessage =
    status === 'COMPLETED'
      ? (canReapprove
          ? '검수 승인 이후 라벨/메타가 수정되어 재검토가 필요합니다. 다시 확인 후 승인하세요.'
          : '이미 승인 처리된 검수입니다.')
      : status === 'REJECTED'
        ? '이미 반려 처리된 검수입니다.'
        : null;

  const hasActions = Boolean(onApprove || onReject);

  return (
    <header
      className="flex h-16 shrink-0 items-center gap-3 border-b border-gray-200 bg-white px-4 text-gray-900"
      data-testid="review-header"
    >
      <Button
        variant="ghost"
        size="sm"
        onClick={onClose}
        className={`${KRDS_ICON_HIT_AREA} shrink-0 p-0`}
        aria-label="검수 페이지 닫기"
      >
        <X size={18} aria-hidden />
      </Button>
      <div className="min-w-0 flex-1">
        <p className="truncate text-body-md font-semibold text-gray-900">
          <span data-testid="review-header-cctv-name">{cctvName}</span>
          <span className="ml-2 text-label font-normal text-gray-500">#{videoId}</span>
        </p>
        <p className="truncate text-caption text-gray-500">
          작업자: <span data-testid="review-header-worker-name">{workerName}</span> · 제출:{' '}
          {formattedDate}
        </p>
      </div>
      <div className="flex shrink-0 items-center gap-1.5">
        <StatusBadge status={status} />
        {/* 재검토 필요 표시 — 상태 배지와 나란히, 대체가 아니라 병기(SCREEN-019). */}
        {needsRecheck && <StatusBadge status="NEEDS_RECHECK" />}
      </div>
      {hasActions && (
        <div className="flex shrink-0 items-center gap-3" data-testid="review-header-actions">
          {/* 비활성 사유는 disabled 속성만으로는 전달되지 않는다 — 텍스트 안내를 함께 노출한다. */}
          {finalMessage && (
            <span className="text-caption text-gray-500" data-testid="review-header-action-message">
              {finalMessage}
            </span>
          )}
          {onReject && (
            <Button
              variant="danger"
              size="sm"
              onClick={onReject}
              disabled={rejectDisabled}
              loading={isRejecting}
              aria-label="반려"
              data-testid="review-action-reject"
            >
              반려
            </Button>
          )}
          {onApprove && (
            <Button
              variant="primary"
              size="sm"
              onClick={onApprove}
              disabled={approveDisabled}
              loading={isApproving}
              aria-label="승인"
              data-testid="review-action-approve"
            >
              승인
            </Button>
          )}
        </div>
      )}
    </header>
  );
}
