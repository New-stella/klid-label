import { create } from 'zustand';

export type ToastVariant = 'success' | 'error' | 'warning' | 'info';

export interface ToastItem {
  id: string;
  variant: ToastVariant;
  message: string;
}

export interface ModalItem {
  id: string;
  title: string;
}

/**
 * 동일 사유 차단/거부 안내를 묶는 창(ms).
 *
 * **사용자의 명시적 재시도를 삼키지 않도록 짧게 잡는다** — 이 창의 목적은 한 번의 상호작용에서
 * 프로그램적으로 연달아 터지는 **동일 사유**를 하나로 묶는 것뿐이다.
 */
export const BLOCK_NOTICE_BURST_MS = 300;

interface UiState {
  sidebarOpen: boolean;
  toasts: ToastItem[];
  modals: ModalItem[];
  /**
   * 마지막 차단/거부 안내(문구 + 시각). dedupe 판정의 **단일 저장소**다.
   *
   * 훅 인스턴스마다 들고 있으면 같은 화면의 서로 다른 소비처(캔버스/속성 패널/배타 실행 래퍼)가
   * 같은 사유로 연달아 거부될 때 같은 문구가 여러 번 뜬다(P-1 이 노렸던 "단일 정책"이 깨진다).
   */
  lastBlockNotice: { at: number; message: string } | null;
  toggleSidebar: () => void;
  setSidebarOpen: (open: boolean) => void;
  pushToast: (item: Omit<ToastItem, 'id'>) => string;
  dismissToast: (id: string) => void;
  /**
   * 차단/거부 안내 발행 — 같은 문구가 버스트 창 안에서 반복되면 1회만 노출한다.
   * 사유(문구)가 다르면 항상 알린다 — 삼키면 사용자는 왜 막혔는지 알 방법이 없다.
   */
  pushBlockNotice: (message: string) => void;
  /** dedupe 상태 초기화(테스트 격리용 — 테스트 간 안내가 서로를 삼키지 않게 한다). */
  resetBlockNotice: () => void;
  pushModal: (item: Omit<ModalItem, 'id'>) => string;
  popModal: (id: string) => void;
}

let counter = 0;
function nextId(prefix: string): string {
  counter += 1;
  return `${prefix}-${Date.now().toString(36)}-${counter}`;
}

export const useUiStore = create<UiState>((set, get) => ({
  sidebarOpen: true,
  toasts: [],
  modals: [],
  lastBlockNotice: null,
  toggleSidebar: () => set((s) => ({ sidebarOpen: !s.sidebarOpen })),
  setSidebarOpen: (open) => set({ sidebarOpen: open }),
  pushToast: (item) => {
    const id = nextId('toast');
    set((s) => ({ toasts: [...s.toasts, { id, ...item }] }));
    return id;
  },
  dismissToast: (id) => set((s) => ({ toasts: s.toasts.filter((t) => t.id !== id) })),
  pushBlockNotice: (message) => {
    const now = Date.now();
    const last = get().lastBlockNotice;
    if (last !== null && last.message === message && now - last.at < BLOCK_NOTICE_BURST_MS) return;
    set({ lastBlockNotice: { at: now, message } });
    // 내부 경로·식별자·좌표는 담지 않는다(호출측이 사용자 문구만 전달).
    get().pushToast({ variant: 'warning', message });
  },
  resetBlockNotice: () => set({ lastBlockNotice: null }),
  pushModal: (item) => {
    const id = nextId('modal');
    set((s) => ({ modals: [...s.modals, { id, ...item }] }));
    return id;
  },
  popModal: (id) => set((s) => ({ modals: s.modals.filter((m) => m.id !== id) })),
}));
