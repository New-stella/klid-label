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
// 점유 표시(내 것/남의 것)·최근 승인자(행위 시점 역할)·검수 시작은 [@design ADR-067] [@design SCREEN-019].
// ★이 화면에 「이력 보기」 진입을 두지 않는다 — SCREEN-019 v56 이 명시적으로 걷어냈다.
//
// UI/UX §4-9 정합:
// - 라이트 배경 (앱 전역 라이트 테마와 동일 — 목록/관리 화면 관례)
// - 좌표 마커 절대 사용 금지 (회귀 방지)

import { X } from 'lucide-react';

import { Button } from '@/components/common/Button';
import { StatusBadge } from '@/components/common/StatusBadge';
import { KRDS_ICON_HIT_AREA } from '@/lib/focusRing';
import { ROLE_LABEL } from '@/lib/roleDisplay';

import { claimLabel, type ReviewClaimView } from '../reviewClaim';
import type { ReviewStatus } from '../types';

export interface ReviewHeaderProps {
  videoId: number;
  cctvName: string;
  workerName: string;
  submittedAt: string; // ISO-8601
  status: ReviewStatus;
  /**
   * 지금 이 영상을 보고 있는 사람 — 내가 잡았는지 남이 잡았는지 **구분해** 보인다(SCREEN-019).
   * 아무도 잡지 않았거나 유예가 지나 풀렸으면 `{kind:'none'}` 이고 표시하지 않는다.
   * ★만료 판정은 서버가 한다 — 화면이 「시작시각 + 유예」를 스스로 재지 않는다.
   */
  claim?: ReviewClaimView;
  /**
   * 점유가 선 시각(ISO-8601). 「{이름} 검수 중 · {시각}부터」로 함께 보인다 — 방금 잡은 것인지
   * 한참 전에 잡은 것인지 알아야 기다릴지 말지를 판단할 수 있다(SCREEN-019).
   */
  reviewStartedAt?: string | null;
  /**
   * 검수 시작이 거절된 사유 — **서버가 보낸 문장 그대로**를 싣는다.
   *
   * ★같은 409 에 사유가 셋이다(남의 점유 / 동시 시작 경합 / 받아들일 수 없는 상태). 사유 코드로
   * 우리 문장을 지어내면 결론은 맞고 사유는 거짓인 안내가 되므로 서버 문장을 그대로 보인다.
   * 막히는 것은 **검수 시작 하나뿐**이라 화면 전체를 잠그지 않는다.
   */
  claimConflictMessage?: string | null;
  /** 마지막 승인자 표시 이름. 승인 이력이 없으면 `null`/미지정. */
  lastApproverName?: string | null;
  /**
   * 승인 **시점**의 역할(`ADMIN`/`REVIEWER`). 옛 기록은 비어 있다 — 그때는 **빈 괄호를 남기지
   * 않고** 이름과 시각만 보인다.
   */
  lastApproverRole?: string | null;
  /** 마지막 승인 시각(ISO-8601). */
  lastApprovedAt?: string | null;
  /**
   * 검수 시작(점유 세우기). 미지정 시 버튼을 렌더하지 않는다.
   *
   * ★재검수 건은 **이 화면에 들어오는 것만으로 잡히지 않는다**(SCREEN-019) — 눌러야 잡히고,
   * 눌러도 상태 배지는 바뀌지 않는다(승인 상태 그대로 남는 것이 의도다 — 상태를 내리면
   * 관제 데이터마트에서 이미 통지된 행이 사라진다).
   * ★**내가 이미 잡고 있어도 버튼을 감추지 않는다** — 다시 누르면 거절되지 않고 잡은 시각만
   *   뒤로 밀려, 오래 보는 동안 유예로 풀리는 것을 막는다.
   */
  onStartReview?: () => void;
  /** 검수 시작 진행 중. */
  isStarting?: boolean;
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
  claim,
  reviewStartedAt,
  claimConflictMessage,
  lastApproverName,
  lastApproverRole,
  lastApprovedAt,
  onStartReview,
  isStarting = false,
  isApproving = false,
  isRejecting = false,
  onClose,
  onApprove,
  onReject,
}: ReviewHeaderProps) {
  const formattedDate = formatSubmittedAt(submittedAt);

  // 「{이름} 검수 중 · {시각}부터」 — 언제부터 보고 있는지가 있어야 기다릴지 말지를 판단한다.
  const claimBase = claim ? claimLabel(claim) : null;
  const claimSince = reviewStartedAt ? formatSubmittedAt(reviewStartedAt) : null;
  const claimText = claimBase
    ? claimSince
      ? `${claimBase} · ${claimSince}부터`
      : claimBase
    : null;

  // 승인 이력 표기 — 역할이 비면 빈 괄호를 남기지 않고 이름만 쓴다(옛 기록은 역할이 없다).
  // 표시명은 역할 표시 축의 **단일 진실원**을 쓴다(표를 복제하면 화면마다 이름이 갈린다).
  // 이 축에 실제로 나타나는 값은 `ADMIN`·`REVIEWER` 뿐이다 — 작업자는 승인할 수 없다.
  const approverName = lastApproverName?.trim();
  const approverRole = lastApproverRole?.trim();
  const approverText = approverName
    ? approverRole
      ? `${approverName}(${ROLE_LABEL[approverRole] ?? approverRole})`
      : approverName
    : null;
  const approvedAtText = lastApprovedAt ? formatSubmittedAt(lastApprovedAt) : null;
  const lastApprovalCore = [approverText, approvedAtText].filter(Boolean).join(' · ');
  // 「{이름}({역할}) · {시각} 승인」 — 무엇의 시각인지 밝히지 않으면 제출일과 구분되지 않는다.
  const lastApprovalText = lastApprovalCore ? `${lastApprovalCore} 승인` : '';

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

  const hasActions = Boolean(onApprove || onReject || onStartReview);

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
          {/* 마지막으로 누가 언제 어떤 역할로 승인했는지(SCREEN-019). 이력이 없으면 아예 두지 않는다. */}
          {lastApprovalText && (
            <>
              {' · 최근 승인: '}
              <span data-testid="review-header-last-approval">{lastApprovalText}</span>
            </>
          )}
        </p>
      </div>
      <div className="flex shrink-0 items-center gap-1.5">
        {/* 지금 누가 보고 있는지 — 색만으로 구분하지 않고 문구로 알린다. */}
        {claimText && (
          <span
            className="whitespace-nowrap text-caption text-gray-600"
            data-testid="review-header-claim"
          >
            {claimText}
          </span>
        )}
        <StatusBadge status={status} />
        {/* 재검토 필요 표시 — 상태 배지와 나란히, 대체가 아니라 병기(SCREEN-019). */}
        {needsRecheck && <StatusBadge status="NEEDS_RECHECK" />}
      </div>
      {/* 검수 시작이 거절된 사유 — 사라지는 알림이 아니라 **머무는 안내**다. 잠깐 떴다 사라지면
          자리를 비웠다 돌아온 사람이 왜 자기 이름이 안 뜨는지 알 길이 없다.
          ★막히는 것은 검수 시작 하나뿐이라 화면 전체가 잠긴 것처럼 보이게 하지 않는다. */}
      {claimConflictMessage && (
        <p
          className="shrink-0 text-caption text-danger"
          role="alert"
          data-testid="review-header-claim-conflict"
        >
          {claimConflictMessage}
        </p>
      )}
      {hasActions && (
        <div className="flex shrink-0 items-center gap-3" data-testid="review-header-actions">
          {/* 비활성 사유는 disabled 속성만으로는 전달되지 않는다 — 텍스트 안내를 함께 노출한다. */}
          {finalMessage && (
            <span className="text-caption text-gray-500" data-testid="review-header-action-message">
              {finalMessage}
            </span>
          )}
          {/* 검수 시작(점유 세우기) — 재검수 건은 눌러야 잡힌다(SCREEN-019). 잡아야 검수 목록의
              일괄 검수완료 대상에 담기며, 눌러도 상태 배지는 바뀌지 않는다. */}
          {onStartReview && (
            <Button
              variant="primary"
              size="sm"
              onClick={onStartReview}
              disabled={isStarting || isApproving || isRejecting}
              loading={isStarting}
              aria-label="검수 시작"
              data-testid="review-action-start"
            >
              검수 시작
            </Button>
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
