import { type ReactNode } from 'react';

import { cn } from '@/lib/cn';

import { Spinner } from './Spinner';

export interface LoadingOverlayProps {
  visible: boolean;
  message?: ReactNode;
  className?: string;
  fullscreen?: boolean;
}

export function LoadingOverlay({
  visible,
  message = '로딩 중',
  className,
  fullscreen = false,
}: LoadingOverlayProps) {
  if (!visible) return null;
  return (
    <div
      role="status"
      aria-live="polite"
      className={cn(
        'flex items-center justify-center gap-3 bg-white/70',
        fullscreen ? 'fixed inset-0 z-40' : 'absolute inset-0',
        className,
      )}
    >
      <Spinner size="lg" label={typeof message === 'string' ? message : '로딩 중'} />
      {typeof message === 'string' ? (
        <span className="text-body text-gray-600">{message}</span>
      ) : (
        message
      )}
    </div>
  );
}
