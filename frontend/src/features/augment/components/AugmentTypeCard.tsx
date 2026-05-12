import { Check } from 'lucide-react';

import { cn } from '@/lib/cn';

import { type AugmentType } from '../types';

export interface AugmentTypeCardProps {
  type: AugmentType;
  selected: boolean;
  onToggle: () => void;
  disabled?: boolean;
}

interface TypeMeta {
  icon: string;
  title: string;
  description: string;
}

const TYPE_META: Record<AugmentType, TypeMeta> = {
  WINTER: {
    icon: '❄️',
    title: '겨울',
    description: '눈/설경 효과로 영상을 변환합니다.',
  },
  NIGHT: {
    icon: '🌙',
    title: '야간',
    description: '저조도 야간 환경으로 영상을 변환합니다.',
  },
  RAIN: {
    icon: '🌧',
    title: '비',
    description: '강우 효과로 영상을 변환합니다.',
  },
  RESOLUTION: {
    icon: '📐',
    title: '해상도',
    description: '해상도 변환으로 다양한 화질을 학습합니다.',
  },
};

/**
 * 증강 유형 카드 — UI/UX §4-12.
 * 클릭 가능한 카드 형태로 아이콘·제목·설명을 보여주고, 선택 시 보더/배경 강조.
 */
export function AugmentTypeCard({
  type,
  selected,
  onToggle,
  disabled,
}: AugmentTypeCardProps) {
  const meta = TYPE_META[type];
  return (
    <button
      type="button"
      onClick={onToggle}
      disabled={disabled}
      data-testid={`augment-type-${type}`}
      aria-pressed={selected}
      className={cn(
        'group relative flex flex-col items-start gap-2 rounded-lg border bg-white p-4 text-left transition-all',
        'focus:outline-none focus-visible:ring-2 focus-visible:ring-primary-500 focus-visible:ring-offset-1',
        'disabled:cursor-not-allowed disabled:opacity-50',
        selected
          ? 'border-primary-500 bg-primary-50 shadow-sm ring-1 ring-primary-300'
          : 'border-gray-200 hover:border-primary-300 hover:shadow-sm',
      )}
    >
      {selected && (
        <span className="absolute right-2 top-2 inline-flex h-5 w-5 items-center justify-center rounded-full bg-primary-600 text-white">
          <Check size={12} aria-hidden />
        </span>
      )}
      <span className="text-2xl" aria-hidden>
        {meta.icon}
      </span>
      <span
        className={cn(
          'text-sm font-semibold',
          selected ? 'text-primary-700' : 'text-gray-800',
        )}
      >
        {meta.title}
      </span>
      <span className="text-xs text-gray-500">{meta.description}</span>
    </button>
  );
}
