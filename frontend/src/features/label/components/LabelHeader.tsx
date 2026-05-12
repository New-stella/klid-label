// SCR-LABEL-001 다크 헤더 (mock 정합 — 풀스크린 라벨링 화면 상단 56px 바).
//
// 좌: × 닫기 + CCTV명 + 이벤트뱃지
// 중: Frame N/총 + 저장 상태(✓ 저장됨 / ● 편집 중)
// 우: N개 객체 + [히스토리] (INTERNAL only) + [저장] + [검수제출] (WORKER only)

import { GitBranch, Save, X } from 'lucide-react';
import { Link, useNavigate } from 'react-router-dom';

import { EventTypeBadge } from '@/components/common/EventTypeBadge';
import { cn } from '@/lib/cn';

interface LabelHeaderProps {
  cctvName?: string;
  eventType?: string;
  currentFrame: number;
  totalFrames: number;
  objectCount: number;
  dirty: boolean;
  videoId?: number | string;
  showHistory: boolean;
  onSave: () => void;
  saving?: boolean;
  /** 검수제출 — WORKER만 노출 (LabelingPage에서 isWorker 가드) */
  submitButton?: React.ReactNode;
  /** X 닫기 버튼 클릭 콜백. 미지정 시 navigate(-1) 기본 동작 (dirty 가드 없음). */
  onClose?: () => void;
}

export function LabelHeader({
  cctvName,
  eventType,
  currentFrame,
  totalFrames,
  objectCount,
  dirty,
  videoId,
  showHistory,
  onSave,
  saving = false,
  submitButton,
  onClose,
}: LabelHeaderProps) {
  const navigate = useNavigate();
  const handleClose = onClose ?? (() => navigate(-1));

  return (
    <header
      className="flex items-center gap-3 px-4 bg-gray-800 border-b border-gray-700 shrink-0"
      style={{ height: 56 }}
    >
      <button
        onClick={handleClose}
        className="p-1.5 rounded text-gray-400 hover:text-white hover:bg-gray-700 transition-colors"
        aria-label="뒤로가기"
        type="button"
      >
        <X size={18} />
      </button>

      <div className="flex-1 min-w-0 flex items-center gap-2">
        <p className="text-sm font-semibold text-white truncate">
          {cctvName ?? `프레임 ${currentFrame + 1}`}
        </p>
        {eventType && <EventTypeBadge eventType={eventType} size="sm" />}
      </div>

      {/* Center status */}
      <div className="text-center shrink-0">
        <p className="text-sm font-medium text-white" data-testid="frame-counter">
          Frame {currentFrame + 1} / {totalFrames}
        </p>
        <p className={cn('text-xs', dirty ? 'text-yellow-400' : 'text-green-400')}>
          {dirty ? '● 편집 중' : '✓ 저장됨'}
        </p>
      </div>

      <div className="flex-1 flex justify-end items-center gap-2">
        <span className="text-xs text-gray-400" aria-label="객체 수">
          {objectCount}개 객체
        </span>
        {showHistory && videoId !== undefined && (
          <Link
            to={`/history/${videoId}`}
            className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs text-gray-300 hover:bg-gray-700 transition-colors border border-gray-600"
          >
            <GitBranch size={14} />
            히스토리
          </Link>
        )}
        <button
          onClick={onSave}
          disabled={saving}
          aria-label="저장"
          type="button"
          className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs text-white bg-blue-600 hover:bg-blue-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
        >
          <Save size={14} />
          {saving ? '저장 중...' : '저장'}
        </button>
        {submitButton}
      </div>
    </header>
  );
}
