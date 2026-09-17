import { useLayoutEffect, useRef, useState } from 'react';

import { isPortalEmbedChannel } from '@/lib/buildChannel';

/**
 * 임베드 편집기가 설 수 있는 **최소 높이**. 이보다 좁으면 캔버스가 쓸 수 없게 짜부라진다.
 * (실측 사고 당시 캔버스가 343 이었고, 그 화면으로는 라벨을 그릴 수 없었다.)
 */
export const PORTAL_EDITOR_MIN_HEIGHT = 560;

/**
 * 임베드 편집기의 높이 글 — 제 윗변부터 화면 아래까지 채우되 최소 높이를 밑으로 두지 않는다.
 *
 * @param offsetFromDocumentTop 문서 맨 위에서 편집기 윗변까지의 거리(px)
 */
export function portalEditorHeight(offsetFromDocumentTop: number): string {
  const top = Math.max(0, Math.round(offsetFromDocumentTop));
  return `max(${PORTAL_EDITOR_MIN_HEIGHT}px, calc(100vh - ${top}px))`;
}

/**
 * 포털 임베드에서 라벨링 편집기를 **흐름 안에 세우고** 화면 아래까지 채운다.
 *
 * ## 왜 `fixed inset-0` 이 아닌가 (2026-09-17 실화면 실측)
 *
 * Host 가 우리를 심는 자리에 **`contain: layout`** 이 걸려 있다. 그 선언은 그 요소를
 * `position: fixed` 의 **포함 블록**으로 만들기 때문에, 우리 `inset-0` 가 화면이 아니라
 * **그 자리 안쪽**을 덮는다. 그런데 우리 판이 흐름 «밖»이라 그 자리는 잴 내용이 없어
 * 제 최소 높이로 주저앉고, 그 주저앉은 높이를 우리가 다시 덮는 **되먹임**이 된다.
 * 실측: 자리 `518` · 편집기 `518` · 그중 캔버스가 **343** 뿐이었다.
 *
 * ★**Host 가 높이를 묶은 것이 아니다.** 같은 화면에서 우리 판을 흐름 «안»으로 돌리고 높이를
 *   주자 자리가 **934 까지 따라 자랐다**(실험 후 즉시 되돌림). 즉 고칠 자리는 우리 쪽이며
 *   Host 배포를 기다릴 일이 아니다.
 *
 * ⚠ 곁따라 풀리는 것 — 우리 머리 줄이 Host 머리 줄에 **가려 보이지 않던 것**도 함께 해소된다.
 *   Host 머리 줄은 `sticky · z-70` 이고 우리 판은 `z-50` 이라, 흐름 밖에서 화면 맨 위(`y=18`)에
 *   서던 우리 머리 줄이 그 아래 깔려 있었다(실측 — 「프레임 #58 저장됨 뒤로」가 렌더는 되는데
 *   화면에 없었다). 흐름 안으로 들어오면 Host 머리 줄 **아래**에 서므로 겹칠 일이 없다.
 *
 * ⚠ **관제·독립 빌드는 그대로 `fixed` 다** — 그쪽은 우리가 문서 전체를 갖는다.
 *   가르는 축은 사용자 채널(`claims.channel`)이 아니라 **빌드 형상**(`isPortalEmbedChannel`)이다.
 *   같은 포털 사용자라도 독립 배포본에서는 화면을 통째로 쓰기 때문이다.
 *
 * @param enabledOverride 시험이 채널 판정을 건너뛸 때만 쓴다. 비우면 빌드 채널이 정한다.
 */
export function usePortalEditorFill(enabledOverride?: boolean) {
  const embedded = enabledOverride ?? isPortalEmbedChannel();
  const ref = useRef<HTMLDivElement>(null);
  const [height, setHeight] = useState<string | undefined>(undefined);

  useLayoutEffect(() => {
    if (!embedded) {
      setHeight(undefined);
      return;
    }
    const measure = () => {
      const el = ref.current;
      if (!el) return;
      // ★문서 기준 오프셋으로 잰다 — 화면 기준(`rect.top`)으로 재면 스크롤할 때마다 값이
      //   흔들려 편집기 높이가 같이 출렁인다.
      const top = el.getBoundingClientRect().top + window.scrollY;
      setHeight(portalEditorHeight(top));
    };
    measure();
    window.addEventListener('resize', measure);
    return () => window.removeEventListener('resize', measure);
  }, [embedded]);

  return { embedded, ref, height };
}
