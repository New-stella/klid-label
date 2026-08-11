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
  /**
   * R4·R5 — 폐기된 프레임 srcSn 집합. 썸네일을 흐리게 낮추고 '폐기' 표식을 얹는다.
   *
   * ⚠ <b>목록에서 빼지 않는다</b> — 빼면 복원할 자리를 찾을 수 없고, 내부 화면의 프레임 총량은
   *   폐기분을 포함한다(D2 — 총량이 줄면 진행 상황이 왜 바뀌었는지 알 수 없다).
   * ⚠ 상태 테두리(4색)와 <b>다른 축</b>이다 — 폐기는 "산출물에서 뺐다"이고 테두리는 "지금/문의/
   *   저장" 이라 섞으면 한쪽이 다른 쪽을 가린다.
   */
  discardedSrcSns?: Set<number>;
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
  /** R4·R5 — 폐기 프레임 여부. 흐림 + '폐기' 표식 + 접근성 이름에 함께 담는다. */
  discarded?: boolean;
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
  discarded = false,
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
      data-frame-discarded={discarded ? 'Y' : undefined}
      onClick={() => onSelect(index)}
      disabled={disabled}
      className={cn(
        'relative shrink-0 rounded overflow-hidden border-2 transition-all bg-gray-200',
        FRAME_STATUS_BORDER[status],
        disabled && 'opacity-50 cursor-not-allowed',
      )}
      style={{ width: 80, height: 45 }}
      // 흐림(시각)만으로는 보조기술 사용자가 구분할 수 없어 이름에도 담는다.
      aria-label={discarded ? `프레임 ${frameNo} (폐기)` : `프레임 ${frameNo}`}
    >
      {url ? (
        <img
          src={url}
          alt={`F${frameNo}`}
          width={80}
          height={45}
          loading="lazy"
          // 폐기 프레임은 이미지를 낮춰 목록에서 바로 구분되게 한다(이미지에만 적용 — 표식은 선명히).
          className={cn('w-full h-full object-cover', discarded && 'opacity-40 grayscale')}
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
      {/* 폐기 표식 — 아이콘 대신 글자다. 남는 글리프(삭제·숨김)를 빌려 쓰면 "지웠다"로 오인되는데
          폐기는 라벨·이미지를 그대로 두는 논리 폐기다. 이름은 aria-label 이 고정하므로 aria-hidden. */}
      {discarded && (
        <span
          className="absolute inset-x-0 top-0 bg-warning/90 text-center text-[9px] font-semibold leading-tight text-gray-900"
          aria-hidden
        >
          폐기
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
  discardedSrcSns,
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
            discarded={discardedSrcSns?.has(f.srcSn) ?? false}
            onSelect={onSelect}
            portalMode={portalMode}
            disabled={disabled}
          />
        );
      })}
    </div>
  );
}
