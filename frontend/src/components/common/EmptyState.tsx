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
        'flex flex-col items-center justify-center gap-2 py-8 text-center text-neutral',
        className,
      )}
    >
      <div aria-hidden="true">
        {icon ?? <Inbox className="h-8 w-8 text-neutral" />}
      </div>
      {title && <p className="text-section-title text-primary">{title}</p>}
      <p className="text-body">{message}</p>
      {action && (
        <Button variant="outline" size="sm" onClick={action.onClick} className="mt-2">
          {action.label}
        </Button>
      )}
    </div>
  );
}
