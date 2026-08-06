// SCR-REVIEW-002 Phase 2 — 검수 화면 상단 헤더.
//
// 좌측: [X 닫기] [영상명/작업자/제출일/프레임 카운터]
// 우측: 상태 배지 (검수중/승인/반려)
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
  currentFrame: number; // 1-based
  totalFrames: number;
  status: ReviewStatus;
  onClose: () => void;
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
  currentFrame,
  totalFrames,
  status,
  onClose,
}: ReviewHeaderProps) {
  const formattedDate = formatSubmittedAt(submittedAt);

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
      <div
        className="shrink-0 rounded-md bg-gray-100 px-3 py-1.5 text-body-md font-medium text-gray-900"
        aria-label="현재 프레임"
        data-testid="review-header-frame-counter"
      >
        Frame {currentFrame}/{totalFrames}
      </div>
      <div className="shrink-0">
        <StatusBadge status={status} />
      </div>
    </header>
  );
}
