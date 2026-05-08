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
        'flex flex-col items-center justify-center gap-2 py-12 text-center',
        className,
      )}
    >
      <div
        className="mb-2 flex h-14 w-14 items-center justify-center rounded-full bg-red-50"
        aria-hidden="true"
      >
        <AlertTriangle className="h-7 w-7 text-red-500" />
      </div>
      <p className="text-section-title text-gray-900">{title}</p>
      <p className="text-body text-gray-500">{message}</p>
      {onRetry && (
        <Button variant="outline" size="sm" onClick={onRetry} className="mt-2">
          {retryLabel}
        </Button>
      )}
    </div>
  );
}
