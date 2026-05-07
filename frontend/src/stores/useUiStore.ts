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

interface UiState {
  sidebarOpen: boolean;
  toasts: ToastItem[];
  modals: ModalItem[];
  toggleSidebar: () => void;
  setSidebarOpen: (open: boolean) => void;
  pushToast: (item: Omit<ToastItem, 'id'>) => string;
  dismissToast: (id: string) => void;
  pushModal: (item: Omit<ModalItem, 'id'>) => string;
  popModal: (id: string) => void;
}

let counter = 0;
function nextId(prefix: string): string {
  counter += 1;
  return `${prefix}-${Date.now().toString(36)}-${counter}`;
}

export const useUiStore = create<UiState>((set) => ({
  sidebarOpen: true,
  toasts: [],
  modals: [],
  toggleSidebar: () => set((s) => ({ sidebarOpen: !s.sidebarOpen })),
  setSidebarOpen: (open) => set({ sidebarOpen: open }),
  pushToast: (item) => {
    const id = nextId('toast');
    set((s) => ({ toasts: [...s.toasts, { id, ...item }] }));
    return id;
  },
  dismissToast: (id) => set((s) => ({ toasts: s.toasts.filter((t) => t.id !== id) })),
  pushModal: (item) => {
    const id = nextId('modal');
    set((s) => ({ modals: [...s.modals, { id, ...item }] }));
    return id;
  },
  popModal: (id) => set((s) => ({ modals: s.modals.filter((m) => m.id !== id) })),
}));
