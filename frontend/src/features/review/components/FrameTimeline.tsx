// @design SCREEN-019 §프레임 썸네일 스트립 — 검수 상세 화면의 캔버스 바로 위 스트립.
//
// 배치: 사양이 이 스트립을 "캔버스 위"에 두도록 규정한다. 부모(ReviewPage)가 **캔버스 컬럼 안**
//       (우측 패널과 나란한 열)의 첫 행에 마운트하며, 이 컴포넌트는 자기 높이만 차지한다
//       (캔버스가 남은 공간을 갖는다).
//
// ★한 줄 구성이다 — [접기/펼치기 토글] [가로 스크롤 썸네일] [카운터]. 확정 디자인(design-main)의
//   `.rv-filmstrip` 이 이 세 요소를 한 행에 둔다. 구 구현은 그 위에 **머리말 줄**(토글 텍스트 +
//   진행률 바 + 카운터)을 따로 얹어 행이 둘이었는데, 디자인에 없는 행이라 걷어냈다.
//   - 머리말 줄의 진행률 바(시각적 막대)는 상단 프레임 이동 바의 **전폭 위치 슬라이더**가 이미
//     같은 정보를 같은 화면에 표시한다(중복 표면 제거).
//   - 진행 상태의 접근성 계약(role="progressbar")은 사라지지 않고 **카운터로 옮겼다** —
//     디자인의 `.rv-filmstrip-progress` 도 같은 자리에 같은 role 을 둔다.
//
// - 썸네일 클릭 시 onSelect(idx) — 부모(ReviewPage) 가 store 의 currentFrameIdx 갱신.
// - 현재 프레임 자동 scrollIntoView (smooth, block: nearest, inline: center).
// - 키보드 ←/→ 로 이전/다음 프레임 이동 (a11y 권장).
//
// 접기/펼치기 (a11y):
// - 토글은 시맨틱 <button> + aria-expanded. 아이콘 전용이라 이름은 `aria-label` 이 담당한다
//   (셰브론 자체는 aria-hidden — 중복 낭독 방지).
// - 토글·카운터는 행의 양 끝에 두고, `role="toolbar"` 는 가운데 썸네일만 감싼다.
//   toolbar 안에서 ←/→ 는 항목 사이 포커스 이동에 쓰이는 키인데 이 툴바는 그 키를 프레임 이동에
//   쓰므로, 토글을 툴바 안에 두면 토글에 포커스가 있을 때 방향키가 포커스를 옮기는 대신 프레임을
//   바꾼다(툴바 키보드 관례와 어긋난다). 토글은 프레임 이동 컨트롤도 아니다.
// - 접힌 썸네일 목록은 **DOM 에서 제거**한다. aria-hidden 으로 감추기만 하면 보이지 않는 버튼에
//   Tab 포커스가 들어가 갇힌다. 툴바 컨테이너 자체는 접혀도 남아 방향키 이동 경로가 유지된다.
// - 접혀도 카운터·토글은 남아 현재 위치를 알 수 있고 다시 펼칠 수단이 유지된다.
// - 기본값은 **펼침** — 사양이 스트립을 화면 구성 요소로 명시하므로 첫 진입에 감춰져 있으면 안 된다.
//
// UI/UX 정합:
// - 썸네일 64×40 / 현재 프레임 border-primary.
// - 우측 카운터 "x / y" (구 구현의 가상 타임코드 "00:00" 은 디자인에 없어 제거 — 실제 FPS 가
//   아니라 인덱스를 1fps 로 가정해 환산한 표시값이라 카운터가 이미 같은 사실을 담고 있었다).
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

import { useEffect, useRef, useState, type KeyboardEvent } from 'react';
import { ChevronDown, ChevronUp } from 'lucide-react';

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

export function FrameTimeline({
  frames,
  currentFrameIdx,
  onSelect,
  inquirySrcSns,
  savedSrcSns,
}: FrameTimelineProps) {
  const scrollRef = useRef<HTMLDivElement>(null);
  const total = frames.length;
  /** 스트립 펼침 여부. 기본 펼침 — 사양상 스트립은 화면 구성 요소다. */
  const [expanded, setExpanded] = useState(true);

  // 현재 프레임 썸네일 자동 scrollIntoView.
  // 접힌 상태에서는 목록이 DOM 에 없어 scrollRef 가 null 이므로 자연히 건너뛴다.
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
    // 다시 펼쳤을 때도 현재 프레임이 보이도록 expanded 를 의존성에 둔다 — 접힌 동안 이동한
    // 프레임이 스크롤 밖에 있으면 펼친 직후 "현재 프레임이 안 보이는" 상태가 된다.
  }, [currentFrameIdx, expanded]);

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
        className="flex h-20 shrink-0 items-center justify-center border-b border-gray-200 bg-white px-4 text-caption text-gray-500"
        data-testid="frame-timeline-empty"
        aria-label="프레임 없음"
      >
        프레임이 없습니다
      </div>
    );
  }

  const safeIdx = Math.min(Math.max(currentFrameIdx, 0), total - 1);

  return (
    /* ★한 행이다(디자인 `.rv-filmstrip`) — 토글 · 썸네일 · 카운터가 나란히 선다. */
    <div className="flex shrink-0 items-center gap-2 border-b border-gray-200 bg-white px-3 py-2">
      {/* 접기/펼치기 토글 — 아이콘 전용(디자인). ⚠ toolbar **바깥**이다: 토글에 포커스가 있을
          때 ←/→ 가 프레임을 바꾸면 안 된다. */}
      <button
        type="button"
        onClick={() => setExpanded((prev) => !prev)}
        aria-expanded={expanded}
        aria-label={expanded ? '썸네일 스트립 접기' : '썸네일 스트립 펼치기'}
        title={expanded ? '썸네일 스트립 접기' : '썸네일 스트립 펼치기'}
        data-testid="frame-timeline-toggle"
        className="inline-flex h-11 w-8 shrink-0 items-center justify-center rounded border border-gray-300 bg-white text-gray-600 transition-colors hover:bg-gray-50 hover:text-gray-900"
      >
        {/* 접힘/펼침 표식 — 상태는 aria-expanded 가 이미 낭독하므로 아이콘은 aria-hidden. */}
        <span className="inline-flex" aria-hidden="true">
          {expanded ? (
            <ChevronUp className="h-4 w-4" />
          ) : (
            <ChevronDown className="h-4 w-4" />
          )}
        </span>
      </button>

      {/* role="toolbar" — 프레임 이동 컨트롤(썸네일 버튼)만 담는 모음이고, ←/→ 로 항목 사이를
          이동하는 toolbar 의 표준 키보드 패턴과 동작이 일치한다. 컨테이너가 버튼을 품는 것이
          toolbar 에서는 정상이라 중첩 위젯 문제가 생기지 않는다.
          ⚠ role="application" 을 쓰지 않는다 — 그건 스크린리더의 탐색(browse) 모드를 통째로 끄고
            모든 키를 위젯이 직접 받겠다는 선언이라 타임라인 하나에 붙일 수준이 아니다.
          ⚠ 접혀도 이 컨테이너는 남는다 — 방향키 프레임 이동은 스트립이 접힌 상태에서도 유지되는
            경로다(썸네일 버튼만 DOM 에서 빠진다). */}
      <div
        className="min-w-0 flex-1"
        data-testid="frame-timeline"
        role="toolbar"
        aria-orientation="horizontal"
        aria-label="프레임 타임라인"
        tabIndex={0}
        onKeyDown={handleKeyDown}
      >
        {/* 가로 스크롤 썸네일 리스트 — 접히면 DOM 에서 빠진다(포커스가 갇히지 않도록). */}
        {expanded && (
          <div
            ref={scrollRef}
            className="flex items-center gap-1 overflow-x-auto py-0.5"
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
        )}
      </div>

      {/* 카운터 — 디자인 `.rv-filmstrip-progress`. 구 머리말 줄의 진행률 막대가 갖고 있던
          `role="progressbar"` 계약을 이 요소가 그대로 이어받는다(접힌 상태에서도 남는다). */}
      <span
        className="shrink-0 whitespace-nowrap border-l border-gray-200 pl-2 font-mono text-mono tabular-nums text-gray-500"
        data-testid="frame-timeline-counter"
        role="progressbar"
        aria-valuemin={1}
        aria-valuemax={total}
        aria-valuenow={safeIdx + 1}
      >
        {safeIdx + 1} / {total}
      </span>
    </div>
  );
}
