// 포털 라벨링 — 앱 전역 알림을 포털 알림(Toaster)으로 옮겨 띄운다.
//
// <h3>왜 옮기나</h3>
// 라벨링 흐름(저장 · 되돌리기 · 차단 안내 · 속성값 저장 …)은 관제판과 같은 코드라 알림을 **앱 전역
// 알림 저장소**(`useUiStore.pushToast`)로 보낸다. 그 저장소를 그리는 자리(`ToastProvider`)는 관제 모양이라
// 포털 화면에서는 모양이 갈린다. 알림을 부르는 코드를 채널마다 가르면 흐름이 두 벌이 되므로, 부르는
// 쪽은 그대로 두고 **포털 화면이 받아서 포털 알림으로 다시 띄운다.**
//
// ★ 받자마자 전역 저장소에서 지운다 — 안 지우면 같은 알림이 관제 모양으로도 한 번 더 뜬다.
//   레이아웃 효과에서 지우므로 전역 자리가 그려지기 전에 사라진다(깜빡임 없음).
// ★ 이 훅은 포털 라벨링 화면이 떠 있는 동안만 산다 — 관제판 알림 흐름은 건드리지 않는다.

import { useLayoutEffect } from 'react';

import { useToasts, type ToastTone } from '@portal/components/custom';
import { useUiStore, type ToastVariant } from '@/stores/useUiStore';

/** 앱 알림 성격 → 포털 알림 성격. 「오류」는 포털에서 위험(danger)이다. */
const TONE: Record<ToastVariant, ToastTone> = {
  success: 'success',
  error: 'danger',
  warning: 'warning',
  info: 'info',
};

export function usePortalToastBridge() {
  const toasts = useToasts();
  const pending = useUiStore((s) => s.toasts);
  const dismissGlobal = useUiStore((s) => s.dismissToast);
  const push = toasts.push;

  useLayoutEffect(() => {
    if (pending.length === 0) return;
    for (const t of pending) {
      push({ tone: TONE[t.variant], message: t.message });
      dismissGlobal(t.id);
    }
  }, [pending, push, dismissGlobal]);

  return toasts;
}
