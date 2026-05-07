import { clsx } from 'clsx';

interface SkeletonProps {
  className?: string;
  width?: string | number;
  height?: string | number;
  rounded?: boolean;
}

export function Skeleton({ className, width, height, rounded = true }: SkeletonProps) {
  return (
    <span
      role="presentation"
      className={clsx(
        'inline-block animate-pulse bg-bgLight',
        rounded && 'rounded',
        className,
      )}
      style={{ width, height }}
    />
  );
}
