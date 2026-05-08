// SCR-LABEL-001 하단 프레임 썸네일 strip (mock 정합 — 다크 톤, 80×45 thumb).
//
// 보안: thumbnailUrl은 BE 신뢰 도메인만. img alt 텍스트만 사용 (XSS 자동 escape).

import { useEffect, useRef } from 'react';

import { cn } from '@/lib/cn';

import type { FrameSummary } from '../types';

interface DarkFrameStripProps {
  frames: FrameSummary[];
  currentIndex: number;
  onSelect: (index: number) => void;
  /** 검수 시점 이슈 표시용 frameNo 집합 */
  issueFrameNos?: Set<number>;
}

export function DarkFrameStrip({
  frames,
  currentIndex,
  onSelect,
  issueFrameNos,
}: DarkFrameStripProps) {
  const scrollRef = useRef<HTMLDivElement>(null);

  // 현재 선택된 thumb를 가운데로 자동 스크롤
  // jsdom에는 scrollIntoView가 없으므로 typeof 가드 필수 (테스트 환경 호환)
  useEffect(() => {
    const container = scrollRef.current;
    if (!container) return;
    const thumb = container.querySelector(
      `[data-frame-index="${currentIndex}"]`,
    ) as HTMLElement | null;
    if (thumb && typeof thumb.scrollIntoView === 'function') {
      thumb.scrollIntoView({ behavior: 'smooth', block: 'nearest', inline: 'center' });
    }
  }, [currentIndex]);

  if (frames.length === 0) {
    return (
      <div className="h-full bg-gray-800 flex items-center justify-center text-gray-500 text-xs">
        프레임 없음
      </div>
    );
  }

  return (
    <div
      ref={scrollRef}
      className="h-full bg-gray-800 flex items-center gap-1 overflow-x-auto px-2 py-1"
      role="listbox"
      aria-label="프레임 목록"
    >
      {frames.map((f, idx) => {
        const isSelected = idx === currentIndex;
        const hasIssue = issueFrameNos?.has(f.frameNo);
        return (
          <button
            key={f.srcSn ?? f.frameNo}
            type="button"
            role="option"
            aria-selected={isSelected}
            data-frame-index={idx}
            onClick={() => onSelect(idx)}
            className={cn(
              'relative shrink-0 rounded overflow-hidden border-2 transition-all bg-black',
              isSelected
                ? 'border-blue-500 scale-105'
                : 'border-transparent hover:border-gray-500',
            )}
            style={{ width: 80, height: 45 }}
            aria-label={`프레임 ${f.frameNo}`}
          >
            {f.thumbnailUrl ? (
              <img
                src={f.thumbnailUrl}
                alt={`F${f.frameNo}`}
                width={80}
                height={45}
                loading="lazy"
                className="w-full h-full object-cover"
              />
            ) : (
              <div className="w-full h-full bg-gray-700 flex items-center justify-center text-gray-500 text-[10px]">
                F{f.frameNo}
              </div>
            )}
            <span className="absolute bottom-0 left-0 right-0 text-center text-white text-[9px] bg-black/50">
              {f.frameNo}
            </span>
            {hasIssue && (
              <span className="absolute top-0 right-0 text-xs leading-none p-0.5">🚩</span>
            )}
          </button>
        );
      })}
    </div>
  );
}
