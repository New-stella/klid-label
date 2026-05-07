import { AlertTriangle } from 'lucide-react';
import { type ReactNode } from 'react';

import { cn } from '@/lib/cn';

import { Button } from './Button';

export interface ErrorStateProps {
  title?: ReactNode;
  message?: ReactNode;
  onRetry?: () => void;
  retryLabel?: string;
  className?: string;
}

export function ErrorState({
  title = '문제가 발생했습니다',
  message = '잠시 후 다시 시도해주세요',
  onRetry,
  retryLabel = '다시 시도',
  className,
}: ErrorStateProps) {
  return (
    <div
      role="alert"
      className={cn(
        'flex flex-col items-center justify-center gap-2 py-8 text-center',
        className,
      )}
    >
      <AlertTriangle className="h-8 w-8 text-danger" aria-hidden="true" />
      <p className="text-section-title text-primary">{title}</p>
      <p className="text-body text-neutral">{message}</p>
      {onRetry && (
        <Button variant="outline" size="sm" onClick={onRetry} className="mt-2">
          {retryLabel}
        </Button>
      )}
    </div>
  );
}
