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
        'flex w-full flex-col gap-2 rounded-lg border border-gray-200 bg-white p-5 text-left shadow-sm transition-colors',
        onClick &&
          'cursor-pointer hover:border-primary-400 focus-visible:ring-2 focus-visible:ring-primary-500',
        className,
      )}
    >
      <div className="flex items-start justify-between">
        <span className="text-sub font-medium text-gray-500">{label}</span>
        {icon && <span aria-hidden="true">{icon}</span>}
      </div>
      <div className="flex items-baseline gap-1">
        <strong className="text-page-title text-gray-900 tabular-nums">{formatNumber(value)}</strong>
        {unit && <span className="text-sub text-gray-500">{unit}</span>}
      </div>
      {trend && (
        <span
          className={cn(
            'text-sub font-medium',
            trend.delta > 0 && 'text-green-600',
            trend.delta < 0 && 'text-red-500',
            trend.delta === 0 && 'text-gray-500',
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
