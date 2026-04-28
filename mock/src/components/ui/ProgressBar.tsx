type ProgressTone = 'primary' | 'success' | 'warning' | 'danger';
type ProgressSize = 'sm' | 'md';

interface ProgressBarProps {
  value: number;
  tone?: ProgressTone;
  size?: ProgressSize;
  showLabel?: boolean;
  className?: string;
}

const TONE_CLASSES: Record<ProgressTone, string> = {
  primary: 'bg-primary-500',
  success: 'bg-green-500',
  warning: 'bg-yellow-400',
  danger: 'bg-red-500',
};

const SIZE_CLASSES: Record<ProgressSize, string> = {
  sm: 'h-1.5',
  md: 'h-2.5',
};

export function ProgressBar({
  value,
  tone = 'primary',
  size = 'md',
  showLabel = false,
  className = '',
}: ProgressBarProps) {
  const clamped = Math.min(100, Math.max(0, value));

  return (
    <div className={['flex items-center gap-2', className].join(' ')}>
      <div
        className={[
          'flex-1 bg-gray-200 rounded-full overflow-hidden',
          SIZE_CLASSES[size],
        ].join(' ')}
        role="progressbar"
        aria-valuenow={clamped}
        aria-valuemin={0}
        aria-valuemax={100}
      >
        <div
          className={[
            'h-full rounded-full transition-all duration-300',
            TONE_CLASSES[tone],
          ].join(' ')}
          style={{ width: `${clamped}%` }}
        />
      </div>
      {showLabel && (
        <span className="text-xs font-medium text-gray-600 tabular-nums w-10 text-right">
          {clamped}%
        </span>
      )}
    </div>
  );
}

export default ProgressBar;
