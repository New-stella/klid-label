/**
 * 포털 채널 배지 — 알약 한 개. **분류·출처처럼 훑어서 읽는 표식**에 쓴다.
 *
 * <h3>관제 공통 `Badge` 를 쓰지 않는 이유</h3>
 * 그 부품은 관제 화면 여럿이 함께 쓰므로 포털 모양을 그쪽에 넣으면 관제 화면이 같이 바뀐다
 * (사용자 확정 구속: **관제향 화면·컴포넌트 불변**). 형태도 갈린다 — DS-002 배지는 알약이고
 * 굵기 500 이며 면과 경계를 함께 쓴다.
 *
 * <h3>색이 뜻을 혼자 나르지 않는다</h3>
 * 배지는 **언제나 한글 라벨을 함께** 갖는다. 그래서 색상 단독 구분 금지(KRDS) 요건을 글자가
 * 단독으로 충족하고, 아이콘은 두지 않는다 — 배지 안의 아이콘은 대개 라벨이 이미 말한 것을
 * 되풀이하는 장식이었다(2026-08-10 관제 축이 같은 이유로 걷어냈다).
 *
 * <h3>글자 색을 톤마다 갈지 않는 자리와 가는 자리</h3>
 * 이 부품은 **의미 톤**(성공·경고·위험)에서만 글자 색을 가른다. 분류를 말하는 `gray`·`outline`
 * 은 먹색 하나로 둔다 — 분류 배지까지 색 글자를 가지면 같은 줄의 제목과 무게를 다툰다.
 *
 * @design DS-002
 * @design SCREEN-028
 * @design SCREEN-044
 */

import type { ReactNode } from 'react';

import { cn } from '@/lib/cn';

/**
 * 배지 톤.
 *
 * `primary` 는 **주색 면**이라 「내 것·활성」 축에 쓴다. DS-002 에서 안내(info)와 주색이 같은
 * 값이므로 둘을 나란히 놓고 구별시키는 자리에는 쓸 수 없다(`DS-002.color_usage.info.avoid_for`).
 */
export type PortalBadgeTone = 'gray' | 'primary' | 'success' | 'warning' | 'danger' | 'outline';

/**
 * 톤별 살갗. 경계를 함께 두는 이유는 DS-002 가 평면을 출발점으로 삼아 **면만으로는 배지가
 * 배경에 잠기기** 때문이다(그림자를 기본으로 걸지 않는다).
 */
const TONE: Record<PortalBadgeTone, string> = {
  gray: 'border-gray-300 bg-gray-100 text-gray-800',
  primary: 'border-primary-200 bg-primary-50 text-primary-700',
  success: 'border-success-200 bg-success-50 text-success-700',
  warning: 'border-warning-200 bg-warning-50 text-warning-700',
  danger: 'border-danger-200 bg-danger-50 text-danger-700',
  // 면 없이 선만 — 성격이 아니라 «어느 갈래인지» 만 말하는 가장 조용한 자리.
  outline: 'border-gray-300 bg-white text-gray-700',
};

interface PortalBadgeProps {
  tone?: PortalBadgeTone;
  children: ReactNode;
  className?: string;
  /** 시험이 행 안에서 이 배지를 집을 수 있게 하는 자리. */
  'data-testid'?: string;
}

export function PortalBadge({
  tone = 'gray',
  children,
  className,
  'data-testid': testId,
}: PortalBadgeProps) {
  return (
    <span
      data-testid={testId}
      className={cn(
        // 굵기 500 — DS-002 상한 600 아래 한 단. 배지는 읽는 자리가 아니라 훑는 표식이다.
        'inline-flex shrink-0 items-center rounded-pill border px-2.5 py-0.5',
        'text-caption font-medium whitespace-nowrap',
        TONE[tone],
        className,
      )}
    >
      {children}
    </span>
  );
}
