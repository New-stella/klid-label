import { useEffect } from 'react';
import { clsx } from 'clsx';

import type { ToastVariant } from '@/stores/useUiStore';

export interface ToastProps {
  id: string;
  variant: ToastVariant;
  message: string;
  durationMs?: number;
  onDismiss: (id: string) => void;
}

const variantClass: Record<ToastVariant, string> = {
  success: 'bg-green-600 text-white',
  error: 'bg-red-600 text-white',
  warning: 'bg-yellow-500 text-white',
  info: 'bg-primary-600 text-white',
};

const variantLabel: Record<ToastVariant, string> = {
  success: '성공',
  error: '오류',
  warning: '경고',
  info: '안내',
};

export function Toast({ id, variant, message, durationMs = 5000, onDismiss }: ToastProps) {
  useEffect(() => {
    const timer = setTimeout(() => onDismiss(id), durationMs);
    return () => clearTimeout(timer);
  }, [id, durationMs, onDismiss]);

  return (
    <div
      role="alert"
      aria-live="polite"
      className={clsx(
        'pointer-events-auto rounded-lg px-4 py-3 shadow-lg transition-opacity duration-100',
        variantClass[variant],
      )}
    >
      <span className="sr-only">{variantLabel[variant]}: </span>
      <span className="text-body">{message}</span>
    </div>
  );
}
