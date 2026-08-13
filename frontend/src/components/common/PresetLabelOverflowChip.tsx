import { cn } from '@/lib/cn';

/**
 * 프리셋 라벨 칩 축약 칩 (UI-113).
 *
 * 프리셋 카드의 라벨 칩이 표시 한도(6개)를 넘으면 나머지 개수만 `+N` 으로 축약해 보여준다.
 * PresetCodeChip(UI-092)과 달리 라벨 정보를 갖지 않는 **숫자 전용** 칩이다.
 *
 * `+N` 텍스트 자체가 정보를 전달하므로 아이콘을 병기하지 않는다(UI-113 accessibility_notes).
 * 대비: gray-600 on gray-100 = 5.13:1(AA).
 */
/** 프리셋 카드에서 라벨 칩을 그대로 노출하는 최대 개수 — 초과분이 이 칩으로 접힌다. */
export const PRESET_LABEL_CHIP_LIMIT = 6;

export interface PresetLabelOverflowChipProps {
  /** 표시 한도를 초과한 나머지 라벨 개수(N). 0 이하면 렌더하지 않는다. */
  count: number;
  className?: string;
}

export function PresetLabelOverflowChip({ count, className }: PresetLabelOverflowChipProps) {
  // 0건일 때 "+0" 을 그리면 접힌 것이 없다는 사실을 잘못 알린다.
  if (count <= 0) return null;

  return (
    <span
      className={cn(
        'inline-flex items-center rounded-full bg-gray-100 px-2 py-0.5 text-label font-semibold text-gray-600',
        className,
      )}
    >
      +{count}
    </span>
  );
}

export default PresetLabelOverflowChip;
