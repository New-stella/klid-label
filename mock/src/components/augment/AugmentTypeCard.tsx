import { Check } from 'lucide-react';

type AugmentType = 'WINTER' | 'NIGHT' | 'RAIN' | 'RESOLUTION';

interface Props {
  type: AugmentType;
  selected: boolean;
  onToggle: () => void;
}

const TYPE_META: Record<
  AugmentType,
  { emoji: string; name: string; seed: number }
> = {
  WINTER: {
    emoji: '❄️',
    name: '겨울',
    seed: 111,
  },
  NIGHT: {
    emoji: '🌙',
    name: '야간',
    seed: 222,
  },
  RAIN: {
    emoji: '🌧',
    name: '비',
    seed: 333,
  },
  RESOLUTION: {
    emoji: '📐',
    name: '해상도',
    seed: 444,
  },
};

export function AugmentTypeCard({ type, selected, onToggle }: Props) {
  const meta = TYPE_META[type];

  return (
    <button
      type="button"
      onClick={onToggle}
      className={[
        'relative flex flex-col text-left rounded-xl border-2 overflow-hidden transition-all cursor-pointer',
        'focus:outline-none focus:ring-2 focus:ring-primary-500 focus:ring-offset-1',
        selected
          ? 'border-primary-500 shadow-md shadow-primary-100'
          : 'border-gray-200 hover:border-gray-300 bg-white',
      ].join(' ')}
      aria-pressed={selected}
    >
      {/* Placeholder image */}
      <div className="w-full aspect-video overflow-hidden bg-gray-100">
        <img
          src={`https://picsum.photos/seed/${meta.seed}/400/225`}
          alt={`${meta.name} 증강 예시`}
          className="w-full h-full object-cover"
          loading="lazy"
          width={400}
          height={225}
        />
      </div>

      {/* Card body */}
      <div className={['p-4', selected ? 'bg-primary-50' : 'bg-white'].join(' ')}>
        <div className="flex items-center gap-2 mb-1">
          <span className="text-xl leading-none" aria-hidden="true">
            {meta.emoji}
          </span>
          <span className="font-semibold text-gray-900 text-sm">{meta.name}</span>
        </div>
      </div>

      {/* Check badge */}
      {selected && (
        <span className="absolute top-2 right-2 w-6 h-6 bg-primary-600 text-white rounded-full flex items-center justify-center shadow">
          <Check size={13} strokeWidth={3} />
        </span>
      )}
    </button>
  );
}

export default AugmentTypeCard;
