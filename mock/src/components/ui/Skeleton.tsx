interface SkeletonProps {
  width?: string;
  height?: string;
  circle?: boolean;
  className?: string;
}

export function Skeleton({
  width = '100%',
  height = '1rem',
  circle = false,
  className = '',
}: SkeletonProps) {
  return (
    <div
      className={[
        'animate-pulse bg-gray-200',
        circle ? 'rounded-full' : 'rounded',
        className,
      ].join(' ')}
      style={{ width, height }}
      aria-hidden="true"
    />
  );
}

export default Skeleton;
