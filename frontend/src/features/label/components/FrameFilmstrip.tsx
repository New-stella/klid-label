// SCR-LABEL-001 하단 프레임 썸네일 strip (mock 정합 — 80×45 thumb).
//
// 프레임 상태색은 확인요청(빨강)·저장(연두)·현재(강조)만 반영한다.
// v2 반려는 영상 단위(REJECTION.srcSn=null)라 특정 프레임에 매핑 불가 → 주황(반려) 프레임색 미대상.
//
// 각 썸네일은 자체 useImageBlob(srcSn) 으로 BE 인증 fetch → blob URL 발급.
// (썸네일 전용 API 가 아직 없어 본 이미지 엔드포인트 재사용 — Phase 6 별도 thumbnail API 도입 시 교체)
// 보안: blob: URL 만 노출. img alt 텍스트는 자동 escape.
// 성능: <img loading="lazy"> 로 뷰포트 외 썸네일은 fetch 지연. 동일 srcSn 은 추후 thumbnail API/React Query 도입 시 자연 캐싱.

import { useEffect, useRef } from 'react';
import { Flag } from 'lucide-react';

import { cn } from '@/lib/cn';

import { useImageBlob } from '../hooks/useImageBlob';
import type { FrameSummary } from '../types';

import {
  FRAME_STATUS_BORDER,
  resolveFrameStatus,
  type FrameStatus,
} from './frameStatus';

interface FrameFilmstripProps {
  frames: FrameSummary[];
  currentIndex: number;
  onSelect: (index: number) => void;
  /** 검수 시점 이슈 표시용 frameNo 집합 (깃발 아이콘) */
  issueFrameNos?: Set<number>;
  /** 확인요청(INQUIRY) 프레임 srcSn 집합 — 빨강 테두리. */
  inquirySrcSns?: Set<number>;
  /** 라벨 저장된 프레임 srcSn 집합 — 연두 테두리. */
  savedSrcSns?: Set<number>;
  /** R16 — 포털 모드면 썸네일도 포털 전용 이미지 엔드포인트로 fetch (내부 API 403 회피). */
  portalMode?: boolean;
  /** 프레임 전환 차단(장시간 작업 진행 중) — 썸네일 선택을 비활성화한다. */
  disabled?: boolean;
}

interface FrameThumbnailProps {
  srcSn: number;
  frameNo: number;
  index: number;
  isSelected: boolean;
  status: FrameStatus;
  hasIssue: boolean;
  onSelect: (index: number) => void;
  portalMode?: boolean;
  disabled?: boolean;
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
  status,
  hasIssue,
  onSelect,
  portalMode,
  disabled = false,
}: FrameThumbnailProps) {
  const { url } = useImageBlob(srcSn, { portalMode });
  return (
    <button
      type="button"
      role="option"
      aria-selected={isSelected}
      data-frame-index={index}
      data-frame-status={status}
      onClick={() => onSelect(index)}
      disabled={disabled}
      className={cn(
        'relative shrink-0 rounded overflow-hidden border-2 transition-all bg-gray-200',
        FRAME_STATUS_BORDER[status],
        disabled && 'opacity-50 cursor-not-allowed',
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
        <div className="w-full h-full bg-gray-200 animate-pulse flex items-center justify-center text-gray-700 text-[10px]">
          F{frameNo}
        </div>
      )}
      <span className="absolute bottom-0 left-0 right-0 text-center text-white text-[9px] bg-black/50">
        {frameNo}
      </span>
      {/* 확인요청 표식 — 이 버튼은 aria-label 로 이름이 고정돼 있어 자식 내용이 낭독되지 않는다.
          (이모지였을 때도 마찬가지로 낭독되지 않았다.) 따라서 아이콘은 `aria-hidden` 이 정확하다.
          테두리색(FRAME_STATUS_BORDER)과 별개 축이며 그 규칙은 건드리지 않는다. */}
      {hasIssue && (
        <span className="absolute top-0 right-0 p-0.5 leading-none" aria-hidden>
          <Flag className="h-3 w-3 fill-danger text-danger" />
        </span>
      )}
    </button>
  );
}

export function FrameFilmstrip({
  frames,
  currentIndex,
  onSelect,
  issueFrameNos,
  inquirySrcSns,
  savedSrcSns,
  portalMode,
  disabled = false,
}: FrameFilmstripProps) {
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
      <div className="h-full bg-gray-50 flex items-center justify-center text-gray-600 text-caption">
        프레임 없음
      </div>
    );
  }

  return (
    <div
      ref={scrollRef}
      className="h-full bg-gray-50 flex items-center gap-1 overflow-x-auto px-2 py-1"
      role="listbox"
      aria-label="프레임 목록"
    >
      {frames.map((f, idx) => {
        const status = resolveFrameStatus({
          isCurrent: idx === currentIndex,
          hasInquiry: inquirySrcSns?.has(f.srcSn) ?? false,
          // v2 반려는 영상 단위라 프레임 매핑 불가 → hasRejection 항상 false(주황 미대상).
          hasRejection: false,
          hasLabel: savedSrcSns?.has(f.srcSn) ?? false,
        });
        return (
          <FrameThumbnail
            key={f.srcSn ?? f.frameNo}
            srcSn={f.srcSn}
            frameNo={f.frameNo}
            index={idx}
            isSelected={idx === currentIndex}
            status={status}
            hasIssue={issueFrameNos?.has(f.frameNo) ?? false}
            onSelect={onSelect}
            portalMode={portalMode}
            disabled={disabled}
          />
        );
      })}
    </div>
  );
}
