import { Pin } from 'lucide-react';
import type { ComponentType } from 'react';

import { cn } from '@/lib/cn';

/**
 * 자유 의미 소형 pill 배지 (UI-111).
 *
 * 공지 목록·상세의 "중요(고정)" 표시와 "발행/작성중" 상태처럼 **도메인 자체 의미**를 갖는
 * 배지에 쓴다.
 *
 * ⚠ **StatusBadge(UI-014)와 역할이 다르다** — 그쪽은 워크플로 코드 축(16종 고정 매핑)이라
 *   코드를 넘기면 라벨이 결정되지만, 이쪽은 라벨을 호출부가 정한다(UI-111 description).
 *   워크플로 상태를 이 컴포넌트로 그리지 말 것 — 매핑이 두 곳으로 갈린다.
 *
 * 색상 대비(WCAG 실측): pinned 8.43:1(AAA) · success 6.99:1(AA) · neutral 7.07:1(AAA) ·
 * **error 8.01:1(AAA)** — `text-danger-700`(#8A240F) on `bg-danger-50`(#FDEFEC) ·
 * **warn 8.43:1(AAA)** — `text-warning-700`(#614100) on `bg-warning-50`(#FFF3DB) ·
 * **info 6.82:1(AA)** — `text-info-700`(#085691) on `bg-info-50`(#E7F4FE).
 * `label` 이 필수인 이유가 여기 있다 — 색상 단독으로 의미를 전달하지 않는다.
 *
 * ⚠ variant 를 새로 추가할 때도 이 계약이 그대로 적용된다 — 배경·텍스트 조합의 대비를 **실제로
 *   측정해** 본문 기준(4.5:1) 이상임을 확인하고 그 수치를 여기에 적는다. 대비 수치가 이
 *   컴포넌트의 계약이라, 신규 variant 가 그 검증을 비켜가면 컴포넌트의 존재 근거가 약해진다.
 *   회귀 가드는 `src/test/contrastGuard.test.ts` 가 소스의 실제 클래스를 읽어 계산한다.
 */
export type BadgeVariant = 'pinned' | 'success' | 'neutral' | 'error' | 'warn' | 'info';

export interface BadgeProps {
  /**
   * pinned=중요(고정) · success=발행 · neutral=작성중(DRAFT) ·
   * error=실패 등 위험·실패 계열(배치 작업 묶음의 「실패」 표식 등) ·
   * warn=되돌릴 수 있는 경고 계열(배치 작업 묶음의 「건너뜀」 표식 등) ·
   * info=알림 계열(배치 작업 묶음의 「해제됨」 표식 등).
   *
   * ⚠ 기존 값은 유지·불변이며 `error`·`warn`·`info` 는 **추가만** 된 것이라 기존 호출부는
   *   영향받지 않는다.
   * ⚠ `warn` 은 `pinned` 와 **같은 클래스 조합**이지만 개명·재사용이 아니다 — 「고정」과
   *   「건너뜀」은 다른 뜻이라 한쪽 톤이 바뀔 때 다른 쪽이 끌려가면 안 된다. 두 키를 합치지 말 것.
   */
  variant: BadgeVariant;
  /** 배지에 표시할 텍스트. 색상 단독 구분을 피하기 위해 필수다. */
  label: string;
  /**
   * 라벨 앞에 병기할 아이콘. 미지정 시 pinned 는 Pin 아이콘을 기본으로 쓰고
   * 나머지 variant 는 아이콘 없이 렌더한다.
   */
  icon?: ComponentType<{ className?: string; 'aria-hidden'?: boolean | 'true' | 'false' }>;
  className?: string;
  /**
   * 시험 훅 — 설계 계약(UI-111 props)이 아니라 호출부가 자기 배지를 지목하기 위한 통로다.
   * 배지의 **의미·표현에는 아무 영향이 없다**(렌더 결과의 속성 하나가 늘 뿐이다).
   */
  'data-testid'?: string;
}

const VARIANT_CLASSES: Record<BadgeVariant, string> = {
  pinned: 'bg-warning-50 text-warning-700',
  success: 'bg-success-50 text-success-700',
  neutral: 'bg-gray-100 text-gray-700',
  // ★ DS-001 semantic **error** 스케일 — 코드 키는 기존 호출부 보존을 위해 `danger` 다
  //   (tailwind.config.js 주석 참조: 정본 명칭 error, 코드 키 danger). 가장 옅은 단계를 배경,
  //   진한 단계를 텍스트로 쓰고 **같은 스케일 밖으로 나가지 않는다** — 실측 8.01:1(AAA).
  error: 'bg-danger-50 text-danger-700',
  // ★ DS-001 semantic **warn** 스케일(코드 키 warning). 시안 `.badge-warn` = 배경 --w-0 /
  //   글자 --w-7 그대로다 — 실측 8.43:1(AAA). `pinned` 와 값이 같은 것은 의도다(위 주석).
  warn: 'bg-warning-50 text-warning-700',
  // ★ DS-001 semantic **info** 스케일. 시안 `.badge-info` = 배경 --i-0 / 글자 --i-7 — 실측 6.82:1(AA).
  info: 'bg-info-50 text-info-700',
};

const DEFAULT_ICONS: Partial<
  Record<BadgeVariant, ComponentType<{ className?: string; 'aria-hidden'?: boolean | 'true' | 'false' }>>
> = {
  pinned: Pin,
};

export function Badge({
  variant,
  label,
  icon,
  className,
  'data-testid': dataTestId,
}: BadgeProps) {
  const Icon = icon ?? DEFAULT_ICONS[variant];

  return (
    <span
      data-testid={dataTestId}
      className={cn(
        'inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-label font-semibold',
        VARIANT_CLASSES[variant],
        className,
      )}
    >
      {Icon && <Icon className="h-3 w-3 shrink-0" aria-hidden="true" />}
      {label}
    </span>
  );
}

export default Badge;
