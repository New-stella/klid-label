import { type HTMLAttributes, type ReactNode } from 'react';

import { cn } from '@/lib/cn';

export interface CardProps extends Omit<HTMLAttributes<HTMLDivElement>, 'title'> {
  title?: ReactNode;
  description?: ReactNode;
  actions?: ReactNode;
  footer?: ReactNode;
  padding?: 'none' | 'sm' | 'md' | 'lg';
}

const paddingClass = {
  none: 'p-0',
  sm: 'p-3',
  md: 'px-6 py-4',
  lg: 'p-6',
} as const;

export function Card({
  title,
  description,
  actions,
  footer,
  padding = 'md',
  className,
  children,
  ...rest
}: CardProps) {
  const hasHeader = title || description || actions;

  return (
    <section
      className={cn(
        'rounded-lg border border-gray-200 bg-white shadow-sm',
        className,
      )}
      {...rest}
    >
      {hasHeader && (
        <div className="flex items-start justify-between border-b border-gray-100 px-6 py-4">
          <div className="flex-1 min-w-0 mr-4">
            {title && (
              // 카드 제목 = ladder `title-sm`(17px/w600). 크기·weight 모두 구 `text-base font-semibold` 와 동일.
              <h3 className="text-title-sm font-semibold text-gray-900 truncate">
                {title}
              </h3>
            )}
            {description && (
              // 카드 설명 = 제목 아래 보조 문장. `caption`(14px)/`body-sm`(15px) 도 후보라
              // 판정이 애매해 **크기를 보존**하는 `body-md`(17px)로 둔다(구 `text-sm` 과 동일).
              <p className="text-body-md text-gray-500 mt-0.5">{description}</p>
            )}
          </div>
          {actions && (
            <div className="flex items-center gap-2 shrink-0">{actions}</div>
          )}
        </div>
      )}
      <div className={paddingClass[padding]}>{children}</div>
      {footer && (
        <div className="border-t border-gray-100 px-6 py-4 text-sub text-gray-500">
          {footer}
        </div>
      )}
    </section>
  );
}
