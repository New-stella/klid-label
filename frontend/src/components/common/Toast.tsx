import { useEffect, type ComponentType } from 'react';
import { clsx } from 'clsx';
import { AlertTriangle, CheckCircle2, Info, XCircle } from 'lucide-react';

import type { ToastVariant } from '@/stores/useUiStore';

export interface ToastProps {
  id: string;
  variant: ToastVariant;
  message: string;
  durationMs?: number;
  onDismiss: (id: string) => void;
}

const variantClass: Record<ToastVariant, string> = {
  success: 'bg-success text-white',
  error: 'bg-danger text-white',
  warning: 'bg-warning text-white',
  info: 'bg-primary-600 text-white',
};

const variantLabel: Record<ToastVariant, string> = {
  success: '성공',
  error: '오류',
  warning: '경고',
  info: '안내',
};

// KRDS: 색만으로 구분 금지 → 변형별 아이콘을 텍스트와 병기.
const variantIcon: Record<ToastVariant, ComponentType<{ className?: string; 'aria-hidden'?: boolean | 'true' | 'false' }>> = {
  success: CheckCircle2,
  error: XCircle,
  warning: AlertTriangle,
  info: Info,
};

export function Toast({ id, variant, message, durationMs = 5000, onDismiss }: ToastProps) {
  useEffect(() => {
    const timer = setTimeout(() => onDismiss(id), durationMs);
    return () => clearTimeout(timer);
  }, [id, durationMs, onDismiss]);

  const Icon = variantIcon[variant];

  return (
    <div
      role="alert"
      aria-live="polite"
      className={clsx(
        'pointer-events-auto flex items-center gap-2 rounded-lg px-4 py-3 shadow-lg transition-opacity duration-100',
        variantClass[variant],
      )}
    >
      <Icon className="h-4 w-4 shrink-0" aria-hidden="true" />
      <span className="sr-only">{variantLabel[variant]}: </span>
      <span className="text-body">{message}</span>
    </div>
  );
}
