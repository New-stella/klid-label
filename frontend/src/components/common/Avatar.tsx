import { cn } from '@/lib/cn';

/**
 * 이니셜 원형 아바타 (UI-109).
 *
 * 사용자 이름 옆에 붙는 이니셜 1자 원형 배지. 표면은 primary-50, 글자는 primary-600 으로
 * 대비 6.09:1(AA)을 만족한다.
 *
 * ⚠ **이 컴포넌트만으로 사용자를 식별하게 두지 않는다** — 호출부가 이름 텍스트를 항상 함께
 *   표시한다(UI-109 accessibility_notes / SCREEN-024 디자인 결정). 그래서 자체적으로는
 *   `aria-hidden` 이며 스크린리더에 이니셜을 두 번 읽히지 않는다.
 */
export interface AvatarProps {
  /** 표시할 이니셜 1자. 2자 이상이 들어오면 첫 글자만 쓴다. */
  initial: string;
  /** md=36px(기본) / sm=28px. */
  size?: 'sm' | 'md';
  className?: string;
}

// 지름은 DS-001 4px 배수 규칙을 따른다(36px = 4×9, 28px = 4×7).
const SIZE_CLASSES = {
  sm: 'h-7 w-7 text-label',
  md: 'h-9 w-9 text-body-md',
} as const;

export function Avatar({ initial, size = 'md', className }: AvatarProps) {
  // 문자열 인덱싱이 아니라 Array.from 으로 자른다 — 서로게이트 페어(이모지 등)가 반쪽만
  // 남아 깨진 글자로 렌더되는 것을 막는다.
  const glyph = Array.from(initial.trim())[0] ?? '';

  return (
    <span
      aria-hidden="true"
      className={cn(
        'inline-flex shrink-0 items-center justify-center rounded-full bg-primary-50 font-semibold text-primary-600',
        SIZE_CLASSES[size],
        className,
      )}
    >
      {glyph}
    </span>
  );
}

export default Avatar;
