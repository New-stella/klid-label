import { type ReactNode } from 'react';

import { cn } from '@/lib/cn';

import { Breadcrumb, type BreadcrumbItem } from './Breadcrumb';

export interface PageHeaderProps {
  title: ReactNode;
  description?: ReactNode;
  breadcrumb?: BreadcrumbItem[];
  actions?: ReactNode;
  className?: string;
}

export function PageHeader({
  title,
  description,
  breadcrumb,
  actions,
  className,
}: PageHeaderProps) {
  return (
    <header className={cn('flex flex-col gap-2 border-b border-gray-200 pb-4', className)}>
      {breadcrumb && breadcrumb.length > 0 && <Breadcrumb items={breadcrumb} />}
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div className="flex flex-col gap-1">
          <h1 className="text-page-title text-gray-900">{title}</h1>
          {/* 설명 글자색은 `gray-600` 이 하한이다 — 이 헤더가 놓이는 페이지 배경이
              gray-50(#F4F5F6)이라 gray-500 은 그 위에서 4.13:1 로 AA(4.5:1) 미달이다
              (gray-600 은 5.77:1). DataTable 헤더와 같은 판정 기준이다. */}
          {description && <p className="text-body text-gray-600">{description}</p>}
        </div>
        {actions && <div className="flex items-center gap-2">{actions}</div>}
      </div>
    </header>
  );
}
