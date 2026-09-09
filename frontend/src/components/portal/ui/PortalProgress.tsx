/**
 * 포털 업로드 진행률 — 막대 + 보낸 용량 병기.
 *
 * <h3>퍼센트만 두지 않는다</h3>
 * 영상은 최대 5GB 다. `68%` 만으로는 «얼마나 더 기다려야 하나» 를 가늠할 수 없어 보낸 용량을
 * 함께 적는다. 조작 자리(버튼)에도 같은 값을 실어, 스크롤로 막대가 가려져도 상태를 읽을 수 있게
 * 하는 것은 호출부 몫이다.
 *
 * ★**숫자는 `tabular-nums`** — 진행률은 매 프레임 갱신되는데 비례폭 숫자를 쓰면 자릿수가 바뀔
 *   때마다 옆 글자가 밀린다.
 *
 * ★**막대 폭 전환에 `transition: all` 을 쓰지 않는다** — 바뀌는 속성만 명시한다.
 *
 * @design DS-002
 * @design SCREEN-033
 */

import { cn } from '@/lib/cn';

interface PortalProgressProps {
  /** 0~100. 범위 밖 값은 잘라 넣는다. */
  percent: number;
  /** 막대 위 왼쪽 설명(예: "2.23 GB / 3.28 GB 보냈습니다"). */
  caption?: string;
  /** 막대 아래 보조 안내(예: 멈출 수 없다 / 이어서 올린다). */
  note?: string;
  /** 실패·중단 표시. 채움이 오류색으로 바뀐다. */
  failed?: boolean;
  /** 보조기술이 읽을 이름. */
  label: string;
  className?: string;
}

export function PortalProgress({
  percent,
  caption,
  note,
  failed = false,
  label,
  className,
}: PortalProgressProps) {
  const value = Math.min(100, Math.max(0, Math.round(percent)));

  return (
    <div className={cn('flex flex-col gap-label-gap', className)}>
      <div className="flex items-baseline justify-between gap-inline">
        {caption !== undefined && (
          <span className="text-caption text-gray-600">{caption}</span>
        )}
        <span
          className={cn(
            'ml-auto text-title-sm tabular-nums',
            failed ? 'text-danger-600' : 'text-gray-900',
          )}
        >
          {value}%
        </span>
      </div>

      <div
        role="progressbar"
        aria-label={label}
        aria-valuenow={value}
        aria-valuemin={0}
        aria-valuemax={100}
        className="h-2 w-full overflow-hidden rounded-pill bg-gray-200"
      >
        <div
          className={cn(
            'h-full rounded-pill transition-[width] duration-base ease-standard',
            failed ? 'bg-danger-500' : 'bg-primary-500',
          )}
          style={{ width: `${value}%` }}
        />
      </div>

      {note !== undefined && <p className="text-caption text-pretty text-gray-600">{note}</p>}
    </div>
  );
}
