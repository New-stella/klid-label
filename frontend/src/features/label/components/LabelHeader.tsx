// SCR-LABEL-001 다크 헤더 (mock 정합 — 풀스크린 라벨링 화면 상단 56px 바).
//
// 좌: × 닫기 + CCTV명 + 이벤트뱃지
// 중: Frame N/총 + 저장 상태(✓ 저장됨 / ● 편집 중)
// 우: N개 객체 + [히스토리] (INTERNAL only) + [저장] + [검수제출] (WORKER only)

import { GitBranch, HelpCircle, Save, X } from 'lucide-react';
import { Link, useNavigate } from 'react-router-dom';

import { EventTypeBadge } from '@/components/common/EventTypeBadge';
import { cn } from '@/lib/cn';

interface LabelHeaderProps {
  cctvName?: string;
  /** 영상의 EV-코드 또는 한글 라벨(eventName). EventTypeBadge 로 전달 — categoryKey 금지. */
  eventType?: string;
  currentFrame: number;
  totalFrames: number;
  objectCount: number;
  dirty: boolean;
  videoId?: number | string;
  showHistory: boolean;
  onSave: () => void;
  saving?: boolean;
  /** 저장 버튼 비활성 (예: 영상 잠금 LOCKED_FOR_REDEIDENT) */
  saveDisabled?: boolean;
  /** 검수제출 — WORKER만 노출 (LabelingPage에서 isWorker 가드) */
  submitButton?: React.ReactNode;
  /**
   * 비식별 누락 신고 버튼 슬롯 (히스토리 우측에 배치).
   * INTERNAL 채널 + WORKER/REVIEWER 에게만 LabelingPage 에서 주입.
   */
  deidentReportButton?: React.ReactNode;
  /** 현재 프레임 이미지 타입 배지 (DEID/RAW) — 우측 정보 영역에 작게 노출 */
  frameImageType?: 'DEID' | 'RAW';
  /** X 닫기 버튼 클릭 콜백. 미지정 시 navigate(-1) 기본 동작 (dirty 가드 없음). */
  onClose?: () => void;
  /**
   * 히스토리 버튼 클릭 콜백. 지정 시 인라인 패널 토글(<button>),
   * 미지정 시 fallback 으로 별도 페이지(/history/{videoId}) <Link>.
   */
  onHistoryClick?: () => void;
  /** 인라인 패널 열림 상태 (aria-expanded 표기) — onHistoryClick 사용 시에만 의미 있음. */
  historyOpen?: boolean;
  /** 단축키 도움말(치트시트) 열기 콜백. 지정 시 우측에 도움말(?) 버튼 노출. */
  onHelpClick?: () => void;
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
  saveDisabled = false,
  submitButton,
  deidentReportButton,
  frameImageType,
  onClose,
  onHistoryClick,
  historyOpen = false,
  onHelpClick,
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
        {frameImageType && (
          <span
            data-testid="frame-image-type-badge"
            className={cn(
              'px-1.5 py-0.5 rounded text-[10px] font-semibold border',
              frameImageType === 'RAW'
                ? 'text-amber-200 border-amber-600 bg-amber-900/40'
                : 'text-emerald-200 border-emerald-700 bg-emerald-900/40',
            )}
            aria-label={`프레임 이미지 타입 ${frameImageType}`}
          >
            {frameImageType}
          </span>
        )}
        {deidentReportButton}
        {onHelpClick && (
          <button
            type="button"
            onClick={onHelpClick}
            aria-label="단축키 도움말"
            data-testid="shortcut-help-button"
            className="flex h-8 w-8 items-center justify-center rounded-lg text-gray-300 hover:bg-gray-700 hover:text-white transition-colors border border-gray-600"
          >
            <HelpCircle size={14} />
          </button>
        )}
        {showHistory && videoId !== undefined && (
          onHistoryClick ? (
            <button
              type="button"
              onClick={onHistoryClick}
              aria-label="히스토리 토글"
              aria-expanded={historyOpen}
              data-testid="history-toggle"
              className={cn(
                'flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs transition-colors border',
                historyOpen
                  ? 'bg-gray-700 text-white border-gray-500'
                  : 'text-gray-300 hover:bg-gray-700 border-gray-600',
              )}
            >
              <GitBranch size={14} />
              히스토리
            </button>
          ) : (
            <Link
              to={`/history/${videoId}`}
              className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs text-gray-300 hover:bg-gray-700 transition-colors border border-gray-600"
            >
              <GitBranch size={14} />
              히스토리
            </Link>
          )
        )}
        <button
          onClick={onSave}
          disabled={saving || saveDisabled}
          aria-label="저장"
          type="button"
          data-testid="label-header-save"
          className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs text-white bg-primary-600 hover:bg-primary-500 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
        >
          <Save size={14} />
          {saving ? '저장 중...' : '저장'}
        </button>
        {submitButton}
      </div>
    </header>
  );
}
