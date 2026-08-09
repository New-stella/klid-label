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
  /**
   * 필터 토글형 카드의 선택 상태 (선택). `onClick` 과 함께 쓸 때만 의미가 있다.
   *
   * 전달하면 `aria-pressed` 로 토글 버튼 시맨틱을 부여하고 시각적으로도 강조한다 —
   * 색상 강조만으로는 보조기술 사용자가 어떤 카드가 적용된 필터인지 알 수 없다.
   * 미전달 시 렌더·시맨틱은 기존과 완전히 동일하다(`aria-pressed` 자체가 붙지 않는다).
   */
  selected?: boolean;
  /** 테스트/자동화 훅 (선택). */
  'data-testid'?: string;
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
  selected,
  'data-testid': testId,
}: KpiCardProps) {
  const Wrapper = onClick ? 'button' : 'div';
  return (
    <Wrapper
      type={onClick ? 'button' : undefined}
      onClick={onClick}
      // 클릭형이면서 선택 상태가 주어진 경우에만 토글 시맨틱을 부여한다.
      aria-pressed={onClick && selected !== undefined ? selected : undefined}
      data-testid={testId}
      className={cn(
        'flex w-full items-start justify-between rounded-lg border border-gray-200 bg-white px-6 py-5 text-left shadow-sm transition-colors',
        onClick && cn('cursor-pointer hover:border-primary-400', KRDS_FOCUS),
        // 색상만이 아니라 테두리 두께로도 선택 상태를 구분한다(KRDS: 색상 단독 구분 금지).
        selected && 'border-2 border-primary-600 bg-primary-50',
        className,
      )}
    >
      <div className="min-w-0 flex-1">
        {/* 지표명 = ladder `label`(14px). ⚠ 구 `text-sm`(17px)에서 **크기가 줄어드는** 유일한
            공통 컴포넌트 지점이다 — 값(`display-sm` 26px)과의 위계를 ladder 대로 세운다.
            weight 는 `font-medium`(500)이 계속 이긴다. */}
        <p className="truncate text-label font-medium text-gray-500">{label}</p>
        {/* 지표값 = ladder `display-sm`(26px). 크기·weight 모두 구 `text-2xl font-bold` 와 동일. */}
        <p className="mt-1 text-display-sm font-bold tabular-nums text-gray-900">
          {formatNumber(value)}
          {/* 단위는 라벨이 아니라 **값의 일부**라 17px(`body-md`) 유지. */}
          {unit && <span className="ml-1 text-body-md font-normal text-gray-500">{unit}</span>}
        </p>
        {trend && (
          <div
            className={cn(
              // 증감 주석 = ladder `label`(14px). 크기는 구 `text-xs` 와 동일.
              'mt-1.5 flex items-center gap-1 text-label font-medium',
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
