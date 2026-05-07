import { ReactNode } from 'react';

import { useUiStore } from '@/stores/useUiStore';

import { Toast } from './Toast';



interface ToastProviderProps {
  children: ReactNode;
}

export function ToastProvider({ children }: ToastProviderProps) {
  const toasts = useUiStore((s) => s.toasts);
  const dismissToast = useUiStore((s) => s.dismissToast);

  return (
    <>
      {children}
      <div
        className="pointer-events-none fixed right-4 top-4 z-[1000] flex flex-col gap-2"
        aria-label="알림"
      >
        {toasts.map((t) => (
          <Toast
            key={t.id}
            id={t.id}
            variant={t.variant}
            message={t.message}
            onDismiss={dismissToast}
          />
        ))}
      </div>
    </>
  );
}
