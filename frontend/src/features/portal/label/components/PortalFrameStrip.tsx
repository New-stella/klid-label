// 포털 라벨링 — 캔버스 아래 프레임 줄.
//
// <h3>흐름은 원본 그대로</h3>
// 원본 프레임 줄(`FrameFilmstrip`)과 같다 —
//   · 낱장 그림은 프레임마다 인증 이미지 창구로 받아 온다(`useImageBlob` · 포털/업로드 출처 그대로)
//   · 낱장을 누르면 화면의 `onSelect`(미저장 확인이 거기 한 번 있다)
//   · 지금 장이 바뀌면 그 장을 줄 가운데로 굴린다
//
// <h3>모양 — 포털 저작도구 화면이 정본</h3>
// 포털 화면의 프레임 줄 조각(`FrameStrip`)을 그대로 쓴다 — 고른 장은 코발트 테 + 번호 굵게.
// ★ 원본 줄에 있던 「저장된 프레임(연두 테)」 · 「폐기(흐림 + 표식)」 표시는 포털 프레임 줄 조각에 모양이 없다.
//   새 모양을 짓지 않고 **보조기술 이름**에만 남겼다(「프레임 3 (저장됨)」).
//
// 조각이 그림 주소를 한꺼번에 받으므로, 프레임마다 이미지를 받는 훅은 화면에 그리지 않는 받기 전용
// 자리(`ThumbSource`)가 돌리고 주소만 모은다.

import { useCallback, useEffect, useRef, useState } from 'react';

import { FrameStrip, type FrameStripItem } from '@portal/pages/workspace/authoring/FrameStrip';
import { useImageBlob } from '@/features/label/hooks/useImageBlob';
import type { FrameSummary } from '@/features/label/types';

/** 프레임 하나의 그림을 받아 주소만 올려 준다. 화면에는 아무것도 그리지 않는다. */
function ThumbSource({
  srcSn,
  portalMode,
  uploadSource,
  onUrl,
}: {
  srcSn: number;
  portalMode: boolean;
  uploadSource: boolean;
  onUrl: (srcSn: number, url: string | null) => void;
}) {
  const { url } = useImageBlob(srcSn, { portalMode, uploadSource });
  useEffect(() => {
    onUrl(srcSn, url);
  }, [srcSn, url, onUrl]);
  return null;
}

export function PortalFrameStrip({
  frames,
  currentIndex,
  onSelect,
  savedSrcSns,
  discardedSrcSns,
  uploadSource,
}: {
  frames: FrameSummary[];
  currentIndex: number;
  onSelect: (index: number) => void;
  savedSrcSns: Set<number>;
  discardedSrcSns: Set<number>;
  uploadSource: boolean;
}) {
  const [urls, setUrls] = useState<Record<number, string | null>>({});
  const onUrl = useCallback((srcSn: number, url: string | null) => {
    setUrls((prev) => (prev[srcSn] === url ? prev : { ...prev, [srcSn]: url }));
  }, []);

  const wrapRef = useRef<HTMLDivElement>(null);
  // 지금 장을 줄 가운데로 — **줄만 가로로 민다.**
  // ⚠ `scrollIntoView` 를 쓰지 않는다 — 그건 조상 스크롤 전부를 움직여, 스크롤되는 틀(포털 iframe) 안에서
  //   문서까지 끌어내려 편집기 머리 줄 · 도구 줄이 위로 밀려났다. 원본은 편집기가 창 전체를 덮어 드러나지 않았다.
  useEffect(() => {
    const current = wrapRef.current?.querySelector('[aria-current="true"]');
    if (!(current instanceof HTMLElement)) return;
    let scroller = current.parentElement;
    while (scroller && scroller !== wrapRef.current && scroller.scrollWidth <= scroller.clientWidth) {
      scroller = scroller.parentElement;
    }
    if (!scroller || typeof scroller.scrollTo !== 'function') return;
    const left = current.offsetLeft - scroller.offsetLeft - (scroller.clientWidth - current.offsetWidth) / 2;
    scroller.scrollTo({ left: Math.max(0, left), behavior: 'smooth' });
  }, [currentIndex]);

  const items: FrameStripItem[] = frames.map((f) => {
    const marks = [
      savedSrcSns.has(f.srcSn) ? '저장됨' : null,
      discardedSrcSns.has(f.srcSn) ? '폐기' : null,
    ].filter(Boolean);
    return {
      // 아직 못 받은 장은 주소를 비워 둔다 — 빈 문자열을 넘기면 브라우저가 페이지를 다시 받으려 한다.
      // 조각 타입은 문자열만 받지만, 주소가 없으면 속성 자체를 빼야 옳아서 그대로 넘긴다(낱장 칸은 회색 바탕으로 선다)
      src: urls[f.srcSn] ?? (undefined as unknown as string),
      label: String(f.frameNo),
      name: marks.length > 0 ? `프레임 ${f.frameNo} (${marks.join(' · ')})` : `프레임 ${f.frameNo}`,
    };
  });

  return (
    <div ref={wrapRef}>
      {frames.map((f) => (
        <ThumbSource
          key={f.srcSn}
          srcSn={f.srcSn}
          portalMode
          uploadSource={uploadSource}
          onUrl={onUrl}
        />
      ))}
      <FrameStrip items={items} current={currentIndex} onSelect={onSelect} label="프레임 목록" />
    </div>
  );
}
