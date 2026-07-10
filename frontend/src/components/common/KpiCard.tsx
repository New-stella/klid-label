import { type ReactNode } from 'react';
import { TrendingDown, TrendingUp } from 'lucide-react';

import { cn } from '@/lib/cn';
import { KRDS_FOCUS } from '@/lib/focusRing';

export interface KpiCardProps {
  label: ReactNode;
  value: number | string;
  unit?: ReactNode;
  trend?: {
    delta: number;
    label?: string;
  };
  icon?: ReactNode;
  iconBgClassName?: string;
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
  iconBgClassName,
  className,
  onClick,
}: KpiCardProps) {
  const Wrapper = onClick ? 'button' : 'div';
  return (
    <Wrapper
      type={onClick ? 'button' : undefined}
      onClick={onClick}
      className={cn(
        'flex w-full items-start justify-between rounded-lg border border-gray-200 bg-white px-6 py-5 text-left shadow-sm transition-colors',
        onClick && cn('cursor-pointer hover:border-primary-400', KRDS_FOCUS),
        className,
      )}
    >
      <div className="min-w-0 flex-1">
        <p className="truncate text-sm font-medium text-gray-500">{label}</p>
        <p className="mt-1 text-2xl font-bold tabular-nums text-gray-900">
          {formatNumber(value)}
          {unit && <span className="ml-1 text-sm font-normal text-gray-500">{unit}</span>}
        </p>
        {trend && (
          <div
            className={cn(
              'mt-1.5 flex items-center gap-1 text-xs font-medium',
              trend.delta > 0 && 'text-success',
              trend.delta < 0 && 'text-danger',
              trend.delta === 0 && 'text-gray-500',
            )}
          >
            {trend.delta > 0 && <TrendingUp className="h-3.5 w-3.5" />}
            {trend.delta < 0 && <TrendingDown className="h-3.5 w-3.5" />}
            <span>
              {trend.delta > 0 ? '+' : ''}
              {trend.delta.toLocaleString('ko-KR')}
              {trend.label ? ` ${trend.label}` : ''}
            </span>
          </div>
        )}
      </div>
      {icon && (
        <div className={cn('rounded-lg p-2.5', iconBgClassName ?? 'bg-gray-100')} aria-hidden="true">
          {icon}
        </div>
      )}
    </Wrapper>
  );
}
