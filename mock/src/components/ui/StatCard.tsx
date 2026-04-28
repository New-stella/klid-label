import { TrendingUp, TrendingDown, type LucideIcon } from 'lucide-react';

type StatTone = 'primary' | 'success' | 'warning' | 'danger';

interface Delta {
  value: string | number;
  direction: 'up' | 'down';
}

interface StatCardProps {
  label: string;
  value: string | number;
  delta?: Delta;
  icon?: LucideIcon;
  tone?: StatTone;
  className?: string;
}

const TONE_CLASSES: Record<StatTone, { icon: string; bg: string }> = {
  primary: { icon: 'text-primary-600', bg: 'bg-primary-50' },
  success: { icon: 'text-green-600', bg: 'bg-green-50' },
  warning: { icon: 'text-yellow-600', bg: 'bg-yellow-50' },
  danger: { icon: 'text-red-600', bg: 'bg-red-50' },
};

export function StatCard({
  label,
  value,
  delta,
  icon: Icon,
  tone = 'primary',
  className = '',
}: StatCardProps) {
  const colors = TONE_CLASSES[tone];

  return (
    <div
      className={[
        'bg-white border border-gray-200 rounded-lg shadow-sm px-6 py-5 flex items-start justify-between',
        className,
      ].join(' ')}
    >
      <div className="flex-1 min-w-0">
        <p className="text-sm text-gray-500 font-medium truncate">{label}</p>
        <p className="text-2xl font-bold text-gray-900 mt-1 tabular-nums">{value}</p>
        {delta && (
          <div
            className={[
              'flex items-center gap-1 mt-1.5 text-xs font-medium',
              delta.direction === 'up' ? 'text-green-600' : 'text-red-500',
            ].join(' ')}
          >
            {delta.direction === 'up' ? (
              <TrendingUp size={12} />
            ) : (
              <TrendingDown size={12} />
            )}
            <span>{delta.value}</span>
          </div>
        )}
      </div>
      {Icon && (
        <div className={['rounded-lg p-2.5', colors.bg].join(' ')}>
          <Icon size={22} className={colors.icon} />
        </div>
      )}
    </div>
  );
}

export default StatCard;
