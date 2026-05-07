import { clsx } from 'clsx';

interface SpinnerProps {
  size?: 'sm' | 'md' | 'lg';
  className?: string;
  label?: string;
}

export function Spinner({ size = 'md', className, label = '로딩 중' }: SpinnerProps) {
  const dim = size === 'sm' ? 'h-4 w-4' : size === 'lg' ? 'h-12 w-12' : 'h-6 w-6';
  return (
    <div
      role="status"
      aria-live="polite"
      aria-label={label}
      className={clsx(
        'inline-block animate-spin rounded-full border-2 border-border border-t-primary',
        dim,
        className,
      )}
    >
      <span className="sr-only">{label}</span>
    </div>
  );
}
