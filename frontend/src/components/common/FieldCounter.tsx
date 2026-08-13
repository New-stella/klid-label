import { cn } from '@/lib/cn';

/**
 * 입력 글자 수 카운터 (UI-115).
 *
 * `18/200` 처럼 현재/최대 글자 수를 캡션 스타일로 표시해 길이 제한을 체감시킨다.
 * Field(UI-099) 라벨 옆에 병기하는 용도다.
 *
 * 표시는 mono(D2Coding)로 한다 — 숫자 폭이 고정돼 타이핑 중 카운터가 좌우로 흔들리지 않는다
 * (SCREEN-036 디자인). 대비: gray-500 on white = 4.51:1(AA).
 *
 * 스크린리더에는 읽히지 않는다(`aria-hidden`) — 입력 필드의 길이 제한은 `maxLength` 와
 * 검증 메시지가 전달하고, 타이핑마다 카운터가 읽히면 오히려 방해가 된다.
 *
 * ⚠ 한도 근접·초과 시의 색 전환(warn)은 **정의되지 않았다** — DS 사양에 없는 임계값을
 *   지어내지 않는다(UI-115 accessibility_notes). 필요해지면 사양을 먼저 확정한다.
 */
export interface FieldCounterProps {
  /** 현재 입력된 글자 수. */
  current: number;
  /** 허용 최대 글자 수. */
  max: number;
  className?: string;
}

export function FieldCounter({ current, max, className }: FieldCounterProps) {
  return (
    <span
      aria-hidden="true"
      className={cn('font-mono text-caption tabular-nums text-gray-500', className)}
    >
      {current}/{max}
    </span>
  );
}

export default FieldCounter;
