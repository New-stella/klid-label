type Tone = 'info' | 'success' | 'warning' | 'danger' | 'neutral' | 'purple';
type Size = 'sm' | 'md';

interface BadgeProps {
  tone?: Tone;
  size?: Size;
  children: React.ReactNode;
  className?: string;
}

const TONE_CLASSES: Record<Tone, string> = {
  info: 'bg-blue-100 text-blue-700',
  success: 'bg-green-100 text-green-700',
  warning: 'bg-yellow-100 text-yellow-700',
  danger: 'bg-red-100 text-red-700',
  neutral: 'bg-gray-100 text-gray-600',
  purple: 'bg-purple-100 text-purple-700',
};

const SIZE_CLASSES: Record<Size, string> = {
  sm: 'text-xs px-2 py-0.5',
  md: 'text-sm px-2.5 py-1',
};

export function Badge({
  tone = 'neutral',
  size = 'md',
  children,
  className = '',
}: BadgeProps) {
  return (
    <span
      className={[
        'inline-flex items-center font-medium rounded-full',
        TONE_CLASSES[tone],
        SIZE_CLASSES[size],
        className,
      ].join(' ')}
    >
      {children}
    </span>
  );
}

export default Badge;
