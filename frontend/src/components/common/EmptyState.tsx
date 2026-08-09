import { Inbox } from 'lucide-react';
import { type ReactNode } from 'react';

import { cn } from '@/lib/cn';

import { Button } from './Button';

export interface EmptyStateProps {
  title?: ReactNode;
  message?: ReactNode;
  icon?: ReactNode;
  action?: {
    label: string;
    onClick: () => void;
  };
  className?: string;
}

export function EmptyState({
  title,
  message = '데이터가 없습니다',
  icon,
  action,
  className,
}: EmptyStateProps) {
  return (
    <div
      role="status"
      className={cn(
        'flex flex-col items-center justify-center gap-2 py-12 text-center',
        className,
      )}
    >
      <div
        className="mb-2 flex h-14 w-14 items-center justify-center rounded-full bg-gray-100"
        aria-hidden="true"
      >
        {icon ?? <Inbox className="h-7 w-7 text-gray-400" />}
      </div>
      {title && <p className="text-section-title text-gray-700">{title}</p>}
      <p className="text-body text-gray-600">{message}</p>
      {action && (
        <Button variant="outline" size="sm" onClick={action.onClick} className="mt-2">
          {action.label}
        </Button>
      )}
    </div>
  );
}
