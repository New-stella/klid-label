// SCR-LABEL-001 하단 프레임 썸네일 strip (mock 정합 — 다크 톤, 80×45 thumb).
//
// 각 썸네일은 자체 useImageBlob(srcSn) 으로 BE 인증 fetch → blob URL 발급.
// (썸네일 전용 API 가 아직 없어 본 이미지 엔드포인트 재사용 — Phase 6 별도 thumbnail API 도입 시 교체)
// 보안: blob: URL 만 노출. img alt 텍스트는 자동 escape.
// 성능: <img loading="lazy"> 로 뷰포트 외 썸네일은 fetch 지연. 동일 srcSn 은 추후 thumbnail API/React Query 도입 시 자연 캐싱.

import { useEffect, useRef } from 'react';

import { cn } from '@/lib/cn';

import { useImageBlob } from '../hooks/useImageBlob';
import type { FrameSummary } from '../types';

interface DarkFrameStripProps {
  frames: FrameSummary[];
  currentIndex: number;
  onSelect: (index: number) => void;
  /** 검수 시점 이슈 표시용 frameNo 집합 */
  issueFrameNos?: Set<number>;
  /** R16 — 포털 모드면 썸네일도 포털 전용 이미지 엔드포인트로 fetch (내부 API 403 회피). */
  portalMode?: boolean;
}

interface FrameThumbnailProps {
  srcSn: number;
  frameNo: number;
  index: number;
  isSelected: boolean;
  hasIssue: boolean;
  onSelect: (index: number) => void;
  portalMode?: boolean;
}

/**
 * 단일 썸네일 — 자체적으로 useImageBlob 호출로 BE 인증 이미지 fetch.
 * 컴포넌트 분리 이유: parent 에서 N 개 srcSn 을 동시에 fetch 하기 위해선 각 hook 호출이 컴포넌트 단위여야 함.
 */
function FrameThumbnail({
  srcSn,
  frameNo,
  index,
  isSelected,
  hasIssue,
  onSelect,
  portalMode,
}: FrameThumbnailProps) {
  const { url } = useImageBlob(srcSn, { portalMode });
  return (
    <button
      type="button"
      role="option"
      aria-selected={isSelected}
      data-frame-index={index}
      onClick={() => onSelect(index)}
      className={cn(
        'relative shrink-0 rounded overflow-hidden border-2 transition-all bg-black',
        isSelected ? 'border-blue-500 scale-105' : 'border-transparent hover:border-gray-500',
      )}
      style={{ width: 80, height: 45 }}
      aria-label={`프레임 ${frameNo}`}
    >
      {url ? (
        <img
          src={url}
          alt={`F${frameNo}`}
          width={80}
          height={45}
          loading="lazy"
          className="w-full h-full object-cover"
        />
      ) : (
        <div className="w-full h-full bg-gray-700 animate-pulse flex items-center justify-center text-gray-500 text-[10px]">
          F{frameNo}
        </div>
      )}
      <span className="absolute bottom-0 left-0 right-0 text-center text-white text-[9px] bg-black/50">
        {frameNo}
      </span>
      {hasIssue && (
        <span className="absolute top-0 right-0 text-xs leading-none p-0.5">🚩</span>
      )}
    </button>
  );
}

export function DarkFrameStrip({
  frames,
  currentIndex,
  onSelect,
  issueFrameNos,
  portalMode,
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
      {frames.map((f, idx) => (
        <FrameThumbnail
          key={f.srcSn ?? f.frameNo}
          srcSn={f.srcSn}
          frameNo={f.frameNo}
          index={idx}
          isSelected={idx === currentIndex}
          hasIssue={issueFrameNos?.has(f.frameNo) ?? false}
          onSelect={onSelect}
          portalMode={portalMode}
        />
      ))}
    </div>
  );
}
