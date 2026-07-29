import { CheckCircle2, ClipboardCheck, Hourglass, XCircle } from 'lucide-react';

import { KpiCard } from '@/components/common/KpiCard';
import { Skeleton } from '@/components/common/Skeleton';

import { REVIEW_STATUS_LABEL } from '../reviewListParams';
import type { ReviewStatus, ReviewSummary } from '../types';

interface ReviewKpiCardsProps {
  summary: ReviewSummary | undefined;
  isLoading: boolean;
  isError: boolean;
  /** 현재 선택된 상태 필터(FE 코드). 빈 문자열 = 전체. */
  selected: '' | ReviewStatus;
  /** 카드 클릭 — 같은 카드를 다시 누르면 `undefined` 로 해제 신호를 준다. */
  onSelect: (status: ReviewStatus | undefined) => void;
}

/**
 * 검수목록 KPI 4카드 (REVIEWER 전용).
 *
 * 숫자는 **서버 집계(GET /v1/reviews/summary) = 필터 결과 전체 기준**이다 —
 * 현재 페이지 20건 안에서 세지 않는다.
 *
 * 카드 ↔ 파라미터 매핑 (표시는 FE 코드, 전송 시 `api.ts` 가 BE 코드로 역매핑):
 *   검수요청 = REVIEW_PENDING(BE PENDING) / 검수중 = REVIEWING(BE IN_REVIEW) /
 *   승인 = COMPLETED(BE APPROVED) / 반려 = REJECTED
 *
 * 상태 3종(로딩 / 정상 / 실패)을 구분 렌더한다 — 로딩을 0건으로 오인하면 "대상이 없다" 는 오판이 된다.
 * 클릭 시맨틱(`button` + `aria-pressed`)은 공통 `KpiCard` 가 담당한다.
 */
export function ReviewKpiCards({
  summary,
  isLoading,
  isError,
  selected,
  onSelect,
}: ReviewKpiCardsProps) {
  if (isLoading) {
    return (
      <div data-testid="kpi-loading" className="grid grid-cols-4 gap-4">
        {Array.from({ length: 4 }).map((_, i) => (
          <div
            key={i}
            className="rounded-lg border border-gray-200 bg-white px-6 py-5 shadow-sm"
          >
            <Skeleton height={14} />
            <div className="mt-2">
              <Skeleton height={24} width="50%" />
            </div>
          </div>
        ))}
      </div>
    );
  }

  if (isError || !summary) {
    // 목록은 정상일 수 있으므로 페이지 전체를 막지 않고 카드 영역에만 표시한다.
    return (
      <div
        data-testid="kpi-error"
        role="status"
        className="rounded-lg border border-gray-200 bg-gray-50 px-4 py-3 text-sm text-gray-600"
      >
        집계 정보를 불러오지 못했습니다. 목록은 정상 표시됩니다.
      </div>
    );
  }

  // 같은 카드를 다시 누르면 해제(전체) — 토글.
  const toggle = (value: ReviewStatus) =>
    onSelect(selected === value ? undefined : value);

  return (
    <div className="grid grid-cols-4 gap-4">
      {/* 라벨은 select·활성 필터 배지·빈 목록 안내와 **같은 상수**에서 온다 — 하드코딩하면
          한쪽만 바뀌어 같은 상태를 화면마다 다르게 부르게 된다. */}
      <KpiCard
        data-testid="kpi-pending"
        label={REVIEW_STATUS_LABEL.REVIEW_PENDING}
        value={summary.pending}
        selected={selected === 'REVIEW_PENDING'}
        onClick={() => toggle('REVIEW_PENDING')}
        icon={<Hourglass size={22} className="text-warning" aria-hidden />}
        iconBgClassName="bg-warning/10"
      />
      <KpiCard
        data-testid="kpi-inReview"
        label={REVIEW_STATUS_LABEL.REVIEWING}
        value={summary.inReview}
        selected={selected === 'REVIEWING'}
        onClick={() => toggle('REVIEWING')}
        icon={<ClipboardCheck size={22} className="text-primary-600" aria-hidden />}
        iconBgClassName="bg-primary-50"
      />
      <KpiCard
        data-testid="kpi-approved"
        label={REVIEW_STATUS_LABEL.COMPLETED}
        value={summary.approved}
        selected={selected === 'COMPLETED'}
        onClick={() => toggle('COMPLETED')}
        icon={<CheckCircle2 size={22} className="text-success" aria-hidden />}
        iconBgClassName="bg-success/10"
      />
      <KpiCard
        data-testid="kpi-rejected"
        label={REVIEW_STATUS_LABEL.REJECTED}
        value={summary.rejected}
        selected={selected === 'REJECTED'}
        onClick={() => toggle('REJECTED')}
        icon={<XCircle size={22} className="text-danger" aria-hidden />}
        iconBgClassName="bg-danger/10"
      />
    </div>
  );
}

export default ReviewKpiCards;
