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

/**
 * 포털 낱장의 상태 테 — **킷이 칠하는 두 상태는 비운다.**
 * `CURRENT` 는 킷 규칙(`[aria-current]`)이 코발트로 그리고, `NONE` 의 hover 도 킷이 이미 갖는다.
 * 우리가 덮으면 부모 포털과 다른 회색이 되어 같은 줄이 화면마다 달라 보인다.
 */
const PORTAL_STATUS_BORDER: Record<FrameStatus, string> = {
  CURRENT: '',
  INQUIRY: FRAME_STATUS_BORDER.INQUIRY,
  REJECTION: FRAME_STATUS_BORDER.REJECTION,
  SAVED: FRAME_STATUS_BORDER.SAVED,
  NONE: '',
};

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
  /**
   * SCREEN-029 — 포털 라벨링의 <b>업로드 자산 출처</b>. 썸네일도 자산 축 창구를 쓴다.
   *
   * ★ 이 전파가 빠지면 메인 캔버스는 뜨는데 <b>썸네일만</b> 데이터마트 창구를 불러 전부 404/403
   *   이 된다 — 화면이 반쯤 살아 있어 발견이 늦다.
   */
  uploadSource?: boolean;
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
  uploadSource?: boolean;
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
  uploadSource,
  disabled = false,
}: FrameThumbnailProps) {
  const { url } = useImageBlob(srcSn, { portalMode, uploadSource });
  // 흐림(시각)만으로는 보조기술 사용자가 구분할 수 없어 이름에도 담는다.
  const name = discarded ? `프레임 ${frameNo} (폐기)` : `프레임 ${frameNo}`;

  if (portalMode) {
    /*
      ★포털 낱장 — <b>부모 포털 시안의 줄 꼴</b>이다(그림 위 · 번호 아래 · 고른 장은 코발트 테 +
        번호 굵게). 종전에는 회색 바닥에 번호를 그림 위로 겹쳐 얹어, 부모 포털 옆에 두면 이 줄만
        «되다 만» 것처럼 보였다(2026-09-16 사용자 지적).
      ★<b>배선·시험 후크는 관제와 같다</b> — `data-frame-*` 세 값과 접근성 이름이 같은 값을 받는다.
      ⚠ 고른 장을 `aria-current` 로 말한다(킷 규칙이 그 표식으로 테를 그린다). 관제의
        `listbox`/`option` 짜임은 <b>그대로 둔다</b> — 관제향 화면 불변 구속이라 한쪽에 맞추지 않는다.
      ⚠ 상태색(확인요청·저장)은 <b>그림에</b> 준다 — 킷이 테를 그림에 두기 때문이다. 현재 장은
        킷이 이미 칠하므로 겹쳐 주지 않는다.
    */
    return (
      <li>
        <button
          type="button"
          className="klid-frame-strip-item"
          aria-current={isSelected ? 'true' : undefined}
          data-frame-index={index}
          data-frame-status={status}
          data-frame-discarded={discarded ? 'Y' : undefined}
          onClick={() => onSelect(index)}
          disabled={disabled}
          aria-label={name}
        >
          <span className="relative block">
            {url ? (
              <img
                src={url}
                alt=""
                loading="lazy"
                className={cn(
                  'thumb',
                  PORTAL_STATUS_BORDER[status],
                  discarded && 'opacity-40 grayscale',
                )}
              />
            ) : (
              <span
                className={cn(
                  'thumb animate-pulse',
                  PORTAL_STATUS_BORDER[status],
                )}
              />
            )}
            {hasIssue && (
              <span className="absolute top-0 right-0 p-0.5 leading-none" aria-hidden>
                <Flag className="h-3 w-3 fill-danger text-danger" />
              </span>
            )}
            {discarded && (
              <span
                className="absolute inset-x-0 top-0 bg-warning/90 text-center text-[9px] font-semibold leading-tight text-gray-900"
                aria-hidden
              >
                폐기
              </span>
            )}
          </span>
          <span className="index" aria-hidden>
            {frameNo}
          </span>
        </button>
      </li>
    );
  }

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
      aria-label={name}
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
  uploadSource,
  disabled = false,
}: FrameFilmstripProps) {
  // 두 채널이 서로 다른 태그를 감싸므로(`div` · `ol`) 공통 상위 타입으로 잡는다.
  const scrollRef = useRef<HTMLElement>(null);

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

  /** 낱장 목록 — **두 채널이 같은 목록을 그린다.** 갈리는 것은 감싸개와 낱장 꼴뿐이다. */
  const renderThumbs = () =>
    frames.map((f, idx) => {
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
          uploadSource={uploadSource}
          disabled={disabled}
        />
      );
    });

  if (frames.length === 0) {
    return (
      <div
        className={cn(
          'flex items-center justify-center text-caption',
          portalMode ? 'py-6 text-gray-600' : 'h-full bg-gray-50 text-gray-600',
        )}
      >
        프레임 없음
      </div>
    );
  }

  if (portalMode) {
    /* ★킷 프레임 줄 — 낱장 76 · 16:9 · 번호는 그림 <b>아래</b>. 가로 스크롤 막대도 킷 것을 쓴다.
       ⚠ 높이를 우리가 정하지 않는다 — 낱장 치수가 킷 규칙에 있어, 여기서 `h-full` 로 묶으면
         번호 줄이 잘린다. 호출부가 이 줄을 <b>내용 높이</b>로 둔다. */
    return (
      <ol
        ref={scrollRef as React.Ref<HTMLOListElement>}
        className="klid-frame-strip klid-scrollbar"
        aria-label="프레임 목록"
      >
        {renderThumbs()}
      </ol>
    );
  }

  return (
    <div
      ref={scrollRef as React.Ref<HTMLDivElement>}
      className="h-full bg-gray-50 flex items-center gap-1 overflow-x-auto px-2 py-1"
      role="listbox"
      aria-label="프레임 목록"
    >
      {renderThumbs()}
    </div>
  );
}
