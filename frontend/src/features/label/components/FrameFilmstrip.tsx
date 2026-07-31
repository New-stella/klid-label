import { cn } from '@/lib/cn';

import type { FrameSummary } from '../types';

interface FrameFilmstripProps {
  frames: FrameSummary[];
  currentIndex: number;
  onSelect: (index: number) => void;
  /** 프레임 전환 차단(장시간 작업 진행 중) — 썸네일 선택을 비활성화한다. */
  disabled?: boolean;
}

/**
 * 프레임 thumbnail 가로 스크롤 — 클릭 시 해당 프레임으로 이동.
 *
 * 보안: thumbnailUrl은 BE 신뢰 도메인만. img alt 텍스트만 사용 (XSS 자동 escape).
 */
export function FrameFilmstrip({
  frames,
  currentIndex,
  onSelect,
  disabled = false,
}: FrameFilmstripProps) {
  return (
    <div
      className="flex gap-1 overflow-x-auto border-t border-border bg-bgLight px-2 py-2"
      role="listbox"
      aria-label="프레임 목록"
    >
      {frames.map((f, idx) => (
        <button
          key={f.srcSn}
          type="button"
          role="option"
          aria-selected={idx === currentIndex}
          onClick={() => onSelect(idx)}
          disabled={disabled}
          className={cn(
            'flex shrink-0 flex-col items-center gap-1 rounded p-1 transition',
            idx === currentIndex ? 'bg-primary' : 'bg-white hover:bg-border',
            disabled && 'cursor-not-allowed opacity-50',
          )}
        >
          <img
            src={f.thumbnailUrl}
            alt={`프레임 ${f.frameNo}`}
            loading="lazy"
            width={80}
            height={45}
            className="rounded bg-black object-cover"
          />
          <span
            className={cn(
              'text-xs',
              idx === currentIndex ? 'text-white' : 'text-neutral',
            )}
          >
            {f.frameNo}
          </span>
        </button>
      ))}
    </div>
  );
}
