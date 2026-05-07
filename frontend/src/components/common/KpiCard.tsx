import { type ReactNode } from 'react';

import { cn } from '@/lib/cn';

export interface KpiCardProps {
  label: ReactNode;
  value: number | string;
  unit?: ReactNode;
  trend?: {
    delta: number;
    label?: string;
  };
  icon?: ReactNode;
  className?: string;
  onClick?: () => void;
}

function formatNumber(value: number | string): string {
  if (typeof value === 'number') return value.toLocaleString('ko-KR');
  return value;
}

export function KpiCard({
  label,
  value,
  unit,
  trend,
  icon,
  className,
  onClick,
}: KpiCardProps) {
  const Wrapper = onClick ? 'button' : 'div';
  return (
    <Wrapper
      type={onClick ? 'button' : undefined}
      onClick={onClick}
      className={cn(
        'flex w-full flex-col gap-2 rounded border border-border bg-white p-4 text-left shadow-sm',
        onClick &&
          'cursor-pointer hover:border-primary focus-visible:ring-2 focus-visible:ring-accent',
        className,
      )}
    >
      <div className="flex items-start justify-between">
        <span className="text-sub text-neutral">{label}</span>
        {icon && <span aria-hidden="true">{icon}</span>}
      </div>
      <div className="flex items-baseline gap-1">
        <strong className="text-page-title text-primary">{formatNumber(value)}</strong>
        {unit && <span className="text-sub text-neutral">{unit}</span>}
      </div>
      {trend && (
        <span
          className={cn(
            'text-sub',
            trend.delta > 0 && 'text-success',
            trend.delta < 0 && 'text-danger',
            trend.delta === 0 && 'text-neutral',
          )}
        >
          {trend.delta > 0 && '▲ '}
          {trend.delta < 0 && '▼ '}
          {Math.abs(trend.delta).toLocaleString('ko-KR')}
          {trend.label ? ` ${trend.label}` : ''}
        </span>
      )}
    </Wrapper>
  );
}
