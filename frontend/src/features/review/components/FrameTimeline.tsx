// SCR-REVIEW-002 Phase 5 — 프레임 타임라인 (footer).
//
// 가로 스크롤 썸네일 + 진행률 바 + 진행 텍스트.
// - 썸네일 클릭 시 onSelect(idx) — 부모(ReviewPage) 가 store 의 currentFrameIdx 갱신.
// - 현재 프레임 자동 scrollIntoView (smooth, block: nearest, inline: center).
// - 키보드 ←/→ 로 이전/다음 프레임 이동 (a11y 권장).
//
// UI/UX 정합 (mock FrameStrip + FrameSlider):
// - 썸네일 64×40 / 현재 프레임 border-primary + scale-105.
// - 좌측 진행 바 + 우측 "x / y 00:00" 텍스트.
//
// 보안:
// - 썸네일은 useImageBlob(srcSn) 으로 BE 인증 fetch (Bearer 자동) → blob URL 사용.
//   <img src=BE_url> 직접 호출은 인증 헤더 누락(401) 회피.
// - 텍스트는 JSX 자동 이스케이프.
//
// 성능 (frontend-performance.md):
// - <img loading="lazy"> — above-the-fold 외 lazy load.
// - width/height 명시 (CLS 방지).
// - 인라인 객체 prop 회피 — 진행바 width 만 인라인 style (동적).

import { useEffect, useRef, type KeyboardEvent } from 'react';

import {
  FRAME_STATUS_BORDER,
  resolveFrameStatus,
} from '@/features/label/components/frameStatus';
import { useImageBlob } from '@/features/label/hooks/useImageBlob';

import type { FrameDetail } from '../types';

interface FrameTimelineProps {
  frames: FrameDetail[];
  currentFrameIdx: number;
  onSelect: (idx: number) => void;
  // R1 — 프레임 상태색은 미해소이슈·저장·현재만 반영한다.
  // v2 반려는 영상 단위(REJECTION.srcSn=null)라 프레임 매핑 불가 → 주황(반려) 프레임색 미대상.
  /** 미해소 문의 프레임 srcSn 집합 → 빨강 테두리 (R1). */
  inquirySrcSns?: Set<number>;
  /** 라벨 저장된 프레임 srcSn 집합 → 연두 테두리 (R1, 부가). */
  savedSrcSns?: Set<number>;
}

const THUMB_WIDTH = 64;
const THUMB_HEIGHT = 40;

/**
 * 단일 썸네일 — useImageBlob 으로 BE 인증 이미지 fetch.
 * 컴포넌트 분리 이유: 각 프레임마다 hook 호출이 필요하므로 컴포넌트 단위로 격리.
 *
 * 보안: blob: URL 만 노출. img alt 는 JSX 자동 escape.
 */
function FrameTimelineThumbnail({
  srcSn,
  displayNo,
}: {
  srcSn: number;
  displayNo: number;
}) {
  const { url } = useImageBlob(srcSn);
  return (
    <img
      src={url ?? ''}
      alt={`프레임 ${displayNo}`}
      width={THUMB_WIDTH}
      height={THUMB_HEIGHT}
      loading="lazy"
      className="rounded object-cover"
      style={{ width: THUMB_WIDTH, height: THUMB_HEIGHT }}
    />
  );
}

/**
 * 프레임 인덱스 기반 가상 타임코드 (V1.x — 실제 영상 FPS 연동 전).
 * 1 frame = 1 second 가정 (mock 과 동일).
 */
function formatTimecode(idx: number): string {
  const seconds = Math.max(0, idx);
  const mm = String(Math.floor(seconds / 60)).padStart(2, '0');
  const ss = String(seconds % 60).padStart(2, '0');
  return `${mm}:${ss}`;
}

export function FrameTimeline({
  frames,
  currentFrameIdx,
  onSelect,
  inquirySrcSns,
  savedSrcSns,
}: FrameTimelineProps) {
  const scrollRef = useRef<HTMLDivElement>(null);
  const total = frames.length;

  // 현재 프레임 썸네일 자동 scrollIntoView.
  useEffect(() => {
    const container = scrollRef.current;
    if (!container) return;
    const thumb = container.querySelector<HTMLElement>(
      `[data-frame-idx="${currentFrameIdx}"]`,
    );
    if (thumb) {
      thumb.scrollIntoView({
        behavior: 'smooth',
        block: 'nearest',
        inline: 'center',
      });
    }
  }, [currentFrameIdx]);

  const handleKeyDown = (e: KeyboardEvent<HTMLDivElement>) => {
    if (total === 0) return;
    if (e.key === 'ArrowLeft') {
      e.preventDefault();
      if (currentFrameIdx > 0) onSelect(currentFrameIdx - 1);
    } else if (e.key === 'ArrowRight') {
      e.preventDefault();
      if (currentFrameIdx < total - 1) onSelect(currentFrameIdx + 1);
    }
  };

  if (total === 0) {
    return (
      <div
        className="flex h-20 shrink-0 items-center justify-center border-t border-gray-200 bg-white px-4 text-caption text-gray-500"
        data-testid="frame-timeline-empty"
        aria-label="프레임 없음"
      >
        프레임이 없습니다
      </div>
    );
  }

  const safeIdx = Math.min(Math.max(currentFrameIdx, 0), total - 1);
  const progressPercent = ((safeIdx + 1) / total) * 100;
  const timecode = formatTimecode(safeIdx);

  return (
    <div
      className="flex shrink-0 flex-col border-t border-gray-200 bg-white"
      data-testid="frame-timeline"
      role="region"
      aria-label="프레임 타임라인"
      tabIndex={0}
      onKeyDown={handleKeyDown}
    >
      {/* 진행률 바 + 텍스트 — 좌측 progress / 우측 텍스트 */}
      <div className="flex items-center gap-3 px-4 py-2">
        <div
          className="h-1 flex-1 overflow-hidden rounded-full bg-gray-200"
          data-testid="frame-timeline-progress"
          role="progressbar"
          aria-valuemin={0}
          aria-valuemax={total}
          aria-valuenow={safeIdx + 1}
        >
          <div
            className="h-full bg-primary-500 transition-all"
            data-testid="frame-timeline-progress-bar"
            style={{ width: `${progressPercent}%` }}
          />
        </div>
        <span
          className="whitespace-nowrap font-mono text-mono tabular-nums text-gray-500"
          data-testid="frame-timeline-counter"
        >
          {safeIdx + 1} / {total} {timecode}
        </span>
      </div>

      {/* 가로 스크롤 썸네일 리스트 */}
      <div
        ref={scrollRef}
        className="flex items-center gap-1 overflow-x-auto px-2 pb-2"
        data-testid="frame-timeline-strip"
      >
        {frames.map((f, idx) => {
          const isCurrent = idx === safeIdx;
          // 표시 번호는 인덱스 기반 1-based — BE frameNo 의 0/1-base 차이에 무관하게
          // 헤더 카운터(`Frame N/total`)와 일관성을 유지한다.
          const displayNo = idx + 1;
          // R1 — resolveFrameStatus 4색 엔진 재사용(현재 > 미해소이슈 > 저장 > 기본).
          // hasRejection 은 항상 false — v2 반려는 영상 단위라 프레임 매핑 불가(주황 미대상).
          const status = resolveFrameStatus({
            isCurrent,
            hasInquiry: inquirySrcSns?.has(f.srcSn) ?? false,
            hasRejection: false,
            hasLabel: savedSrcSns?.has(f.srcSn) ?? false,
          });
          return (
            <button
              key={f.srcSn}
              type="button"
              data-frame-idx={idx}
              data-testid={`frame-timeline-thumb-${idx}`}
              onClick={() => onSelect(idx)}
              aria-current={isCurrent ? 'true' : undefined}
              aria-label={`프레임 ${displayNo}`}
              className={[
                'relative flex shrink-0 flex-col items-center gap-0.5 rounded border-2 p-0.5 transition-all',
                FRAME_STATUS_BORDER[status],
              ].join(' ')}
            >
              <FrameTimelineThumbnail srcSn={f.srcSn} displayNo={displayNo} />
              <span className="text-[10px] leading-none text-gray-500">
                {displayNo}
              </span>
            </button>
          );
        })}
      </div>
    </div>
  );
}
