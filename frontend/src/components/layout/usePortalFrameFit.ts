import { useEffect } from 'react';
import {
  FRAME_SIZE_MESSAGE,
  FRAME_VIEW_MESSAGE,
  type FrameFit,
  type FrameSizeMessage,
  type FrameViewMessage,
} from '@portal/pages/workspace/authoring/frameFit';

/**
 * 포털 카드 높이 맞추기 — 저작도구 쪽 (주고받는 말의 정본은 포털 `frameFit.ts`).
 *
 *   · 이 화면 높이 · 맞춤(지금은 모든 면이 화면 높이만큼 — 2026-09-15 사용자 결정) · 창이 떠 있는지를 포털에 알린다
 *   · 포털이 알려 온 «눈에 보이는 구간»을 문서 뿌리의 변수로 걸어 둔다 — 카드가 길어져 iframe 이 스스로
 *     스크롤하지 않을 때 창 · 알림 · 탭 줄이 이 구간에 맞춰 선다 (`PortalFrameLayout.css`)
 *
 * iframe 안이 아니면(단독으로 열었을 때) 아무것도 하지 않는다.
 */
export function usePortalFrameFit(slot: HTMLElement | null, fit: FrameFit, path: string) {
  useEffect(() => {
    const host = window.parent;
    if (host === window || !slot) return;
    const root = document.documentElement;

    let last = '';
    const report = () => {
      const message: FrameSizeMessage = {
        type: FRAME_SIZE_MESSAGE,
        fit,
        height: Math.ceil(slot.getBoundingClientRect().bottom + window.scrollY),
        // `in` 이 열린 창이다 — `shown` 은 닫힌 창도 자리만 잡은 채 달고 있다(보이지 않는 확인 창 등)
        overlay: document.querySelector('.krds-modal.in') !== null,
        path,
      };
      // 같은 말을 되풀이하지 않는다 — 포털이 받을 때마다 구간을 되보내 주고받기가 끝나지 않는다
      const key = JSON.stringify(message);
      if (key === last) return;
      last = key;
      host.postMessage(message, '*');
    };

    const onMessage = (e: MessageEvent) => {
      if (e.source !== host) return;
      const data = e.data as Partial<FrameViewMessage> | null;
      if (data?.type !== FRAME_VIEW_MESSAGE) return;
      root.style.setProperty('--klid-host-view-top', `${data.top}px`);
      root.style.setProperty('--klid-host-view-height', `${data.height}px`);
      // 포털이 높이를 맞춰 준다는 표시 — 목록 면이 화면 높이를 채우던 규칙이 풀려 제 높이로 줄어든다
      root.setAttribute('data-host-fit', '');
    };

    window.addEventListener('message', onMessage);
    const resize = new ResizeObserver(report);
    resize.observe(slot);
    // 창이 열리고 닫히는 것은 크기 변화로 안 잡힌다(창은 흐름 밖에 뜬다) — 문서 변화를 지켜 따로 알린다.
    // 화면 높이만큼 설 때만 — 카드를 꽉 채우는 맞춤이면 창이 떠도 카드 높이가 그대로다
    const mutation = new MutationObserver(report);
    if (fit === 'content') {
      mutation.observe(document.body, {
        subtree: true,
        childList: true,
        attributes: true,
        attributeFilter: ['class'],
      });
    }
    report();

    return () => {
      window.removeEventListener('message', onMessage);
      resize.disconnect();
      mutation.disconnect();
    };
  }, [slot, fit, path]);
}
