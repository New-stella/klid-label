interface EventTypeBadgeProps {
  eventType: string;
  size?: 'sm' | 'md';
}

const EVENT_COLORS: Record<string, string> = {
  쓰러짐: 'bg-purple-100 text-purple-700',
  폭력: 'bg-red-100 text-red-700',
  교통사고: 'bg-blue-100 text-blue-700',
  '이상행동(유괴)': 'bg-amber-100 text-amber-700',
  침수: 'bg-cyan-100 text-cyan-700',
  산불: 'bg-rose-100 text-rose-700',
};

const SIZE_CLASSES = {
  sm: 'text-xs px-2 py-0.5',
  md: 'text-sm px-2.5 py-1',
};

export function EventTypeBadge({ eventType, size = 'sm' }: EventTypeBadgeProps): JSX.Element {
  const colorClass = EVENT_COLORS[eventType] ?? 'bg-gray-100 text-gray-600';
  return (
    <span
      className={[
        'inline-flex items-center font-medium rounded-full',
        colorClass,
        SIZE_CLASSES[size],
      ].join(' ')}
    >
      {eventType}
    </span>
  );
}

export default EventTypeBadge;
