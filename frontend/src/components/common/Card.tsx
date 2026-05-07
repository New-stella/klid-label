import { type HTMLAttributes, type ReactNode } from 'react';

import { cn } from '@/lib/cn';

export interface CardProps extends Omit<HTMLAttributes<HTMLDivElement>, 'title'> {
  title?: ReactNode;
  actions?: ReactNode;
  footer?: ReactNode;
  padding?: 'none' | 'sm' | 'md' | 'lg';
}

const paddingClass = {
  none: 'p-0',
  sm: 'p-3',
  md: 'p-4',
  lg: 'p-6',
} as const;

export function Card({
  title,
  actions,
  footer,
  padding = 'md',
  className,
  children,
  ...rest
}: CardProps) {
  return (
    <section
      className={cn(
        'rounded border border-border bg-white shadow-sm',
        className,
      )}
      {...rest}
    >
      {(title || actions) && (
        <div className="flex items-center justify-between border-b border-border px-4 py-3">
          {title && <h3 className="text-section-title text-primary">{title}</h3>}
          {actions && <div className="flex items-center gap-2">{actions}</div>}
        </div>
      )}
      <div className={paddingClass[padding]}>{children}</div>
      {footer && (
        <div className="border-t border-border px-4 py-3 text-sub text-neutral">
          {footer}
        </div>
      )}
    </section>
  );
}
