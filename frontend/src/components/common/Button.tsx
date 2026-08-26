import {
  Children,
  cloneElement,
  forwardRef,
  isValidElement,
  type ButtonHTMLAttributes,
  type ComponentType,
  type ReactElement,
  type ReactNode,
} from 'react';

import { cn } from '@/lib/cn';
import { KRDS_FOCUS } from '@/lib/focusRing';

import { Spinner } from './Spinner';

export type ButtonVariant =
  | 'primary'
  | 'secondary'
  | 'outline'
  | 'danger'
  | 'ghost'
  | 'success'
  | 'link';
/**
 * 텍스트 크기 4종(xs/sm/md/lg) + 아이콘 전용 정사각형 4종.
 * 아이콘 전용 size 는 시각 라벨이 없으므로 호출부가 `aria-label` 을 반드시 지정해야 한다.
 */
export type ButtonSize =
  | 'xs'
  | 'sm'
  | 'md'
  | 'lg'
  | 'icon'
  | 'icon-xs'
  | 'icon-sm'
  | 'icon-lg';

export interface ButtonProps extends Omit<ButtonHTMLAttributes<HTMLButtonElement>, 'type'> {
  variant?: ButtonVariant;
  size?: ButtonSize;
  loading?: boolean;
  fullWidth?: boolean;
  type?: 'button' | 'submit' | 'reset';
  leftIcon?: ComponentType<{ className?: string }>;
  rightIcon?: ComponentType<{ className?: string }>;
  /**
   * true 면 button 을 렌더하지 않고 **자식 요소에 버튼 스타일만 위임**한다
   * (링크·다른 오버레이 프리미티브에 버튼 모양을 입힐 때).
   *
   * ⚠ 스타일 위임 전용이다 — `type`/`disabled` 처럼 button 에만 유효한 속성은 내려보내지
   *   않는다(자식이 `<a>` 일 수 있다). 비활성 처리는 자식이 소유하며 여기서는
   *   `aria-disabled`·`aria-busy` 만 붙인다.
   */
  asChild?: boolean;
  children?: ReactNode;
}

const variantClass: Record<ButtonVariant, string> = {
  // 시안 `.btn-primary { background: var(--p-5) }` + `:hover { background: var(--p-6) }`.
  // `--p-5`(#256EF4) = primary-500(= DEFAULT) · `--p-6`(#0B50D0) = primary-600 이다.
  // 구 값은 기본·호버가 각각 한 단씩 어두웠다(600/700).
  //
  // active/disabled 는 시안에 규정이 없어 아래 근거로 정했다.
  //  · active = 700 — danger 주석이 적어 둔 "눌림은 어두워지는 방향" 을 유지하되, 구 값
  //    800 을 그대로 두면 호버(600)에서 두 단을 건너뛰어 눌림 피드백만 과장된다. 500→600→700
  //    으로 **한 단씩** 내려가던 구 계단 간격을 그대로 옮긴 것이다.
  //  · disabled = 300 유지 — 시안 근거가 없어 옮기지 않는다. 기본이 한 단 옅어져도(600→500)
  //    300 은 여전히 그보다 옅어 "비활성은 옅다" 는 신호가 그대로 성립한다.
  //
  // ⚠ 흰 글자 대비가 6.83:1 → **4.55:1** 로 내려간다. AA(4.5)는 통과하나 경계이며,
  //   primary 값이 조금이라도 밝아지면 미달이 된다. 그 경계는 이미 contrastGuard 가
  //   `primary.DEFAULT` 를 [4.5, 4.7) 구간으로 단언해 지키고 있다.
  primary:
    'bg-primary-500 !text-white hover:bg-primary-600 active:bg-primary-700 disabled:bg-primary-300',
  // 테두리는 시안 `.btn-secondary` 의 `--border-strong`(= 중립 400단, #8A949E).
  // 한 단 옅은 300 은 흰 배경 위 2.01:1 이라 WCAG 1.4.11(비텍스트 3:1) 미달이었고
  // 400 은 3.08:1 로 통과한다 — 시안 정합과 접근성이 같은 방향이다.
  secondary:
    'bg-white text-gray-700 border border-gray-400 hover:bg-gray-50 active:bg-gray-100 disabled:text-gray-400 disabled:border-gray-200',
  outline:
    'bg-white text-primary-600 border border-primary-600 hover:bg-primary-50 active:bg-primary-100 disabled:text-primary-300 disabled:border-primary-200',
  danger:
    // 눌림 피드백은 primary(600→700→800)와 동일하게 "어두워지는" 방향으로 통일.
    // 옛 `/90 → /80` 은 오히려 옅어져(밝아져) 방향이 반전됐었다 → brightness 필터로 정정.
    'bg-danger text-white hover:brightness-95 active:brightness-90 disabled:bg-danger/40',
  // 글자색이 gray-700 인 이유는 눌림 배경(active:bg-gray-200) 때문이다 — gray-600 은 그
  // 배경 위에서 4.10:1 로 AA 미달이고 gray-700 이어야 5.65:1 로 통과한다.
  ghost:
    'bg-transparent text-gray-700 hover:bg-gray-100 active:bg-gray-200 disabled:text-gray-400',
  // primary 와 같은 "어두워지는" 눌림 방향(600→700→800). success DEFAULT(500) 대신 600 을
  // 쓰는 이유는 흰 글자 대비 때문이다 — 500 은 4.57:1 로 AA(4.5) 경계에 붙지만 600 은 5.9:1.
  success:
    'bg-success-600 text-white hover:bg-success-700 active:bg-success-800 disabled:bg-success-300',
  // 텍스트 링크형 — 배경 없이 밑줄. 색만으로 구분하지 않도록 밑줄을 항상 유지한다.
  link:
    'bg-transparent text-primary-600 underline underline-offset-4 hover:text-primary-700 active:text-primary-800 disabled:text-primary-300',
};

const sizeClass: Record<ButtonSize, string> = {
  // KRDS 터치 타깃 44x44px 규칙: md/lg 는 min-h-11(44px) 보장.
  // sm 은 밀집 UI(테이블 액션 등) 전용 컴팩트 예외 — 44px 미만 허용하되
  // 단독 터치 타깃으로 쓸 때는 md 이상 사용을 권장한다.
  //
  // 글자 크기는 DS-001 ladder step 으로 배정한다(원시 스케일 금지).
  //  · md/lg = `btn-label`(= ladder `button` 17px/w500) — 표준 버튼.
  //  · sm    = `body-sm`(15px) — 시안 `.btn-sm { min-height: 36px; font-size: 15px }` 정합.
  //            `button`(17px)까지 올리면 테이블 액션·툴바가 무너지므로 md/lg 와는 다른 step 이다.
  // ⚠ 실제 weight 는 베이스의 `font-medium`(500)이 이긴다(Tailwind 는 font-weight 를
  //   font-size 뒤에 출력한다). 즉 sm 은 15px/500 이며, ladder step 자신의 400 이 아니다.
  //  · xs    = `caption`(14px/400) — 밀집 패널 전용. ladder 최소 단이라 sm 보다 더 줄이는 것은
  //            글자 크기가 아니라 여백으로 한다(ladder 밖 px 금지).
  xs: 'text-caption px-2 py-1 gap-1',
  // sm 의 `min-h-9`(36px)은 시안이 정한 하한이다 — 없으면 높이가 내용에 따라 흔들린다.
  sm: 'min-h-9 text-body-sm px-3 py-1.5 gap-1.5',
  md: 'min-h-11 px-4 text-btn-label gap-2',
  lg: 'min-h-11 px-5 py-2.5 text-btn-label gap-2',
  // 아이콘 전용 정사각형. `icon` 이 KRDS 터치 타깃 44px 기준이며, 그보다 작은 단은
  // 밀집 UI(툴바·목록 행 액션) 전용 예외다.
  icon: 'h-11 w-11 p-0',
  'icon-xs': 'h-8 w-8 p-0',
  'icon-sm': 'h-9 w-9 p-0',
  'icon-lg': 'h-12 w-12 p-0',
};

const iconSize: Record<ButtonSize, string> = {
  xs: 'h-3 w-3',
  sm: 'h-3.5 w-3.5',
  md: 'h-4 w-4',
  lg: 'h-5 w-5',
  icon: 'h-4 w-4',
  'icon-xs': 'h-3.5 w-3.5',
  'icon-sm': 'h-4 w-4',
  'icon-lg': 'h-5 w-5',
};

export const Button = forwardRef<HTMLButtonElement, ButtonProps>(function Button(
  {
    variant = 'primary',
    size = 'md',
    loading = false,
    fullWidth = false,
    disabled,
    type = 'button',
    className,
    leftIcon: LeftIcon,
    rightIcon: RightIcon,
    asChild = false,
    children,
    ...rest
  },
  ref,
) {
  const isDisabled = disabled || loading;
  const rootClass = cn(
    // 모서리는 시안 `.btn` 의 `--radius-md`(6px) = borderRadius 토큰 `md`.
    'inline-flex items-center justify-center rounded-md font-medium transition-colors duration-100 disabled:cursor-not-allowed',
    KRDS_FOCUS,
    variantClass[variant],
    sizeClass[size],
    fullWidth && 'w-full',
    className,
  );

  if (asChild) {
    const child = Children.only(children);
    if (!isValidElement(child)) return null;
    const element = child as ReactElement<{ className?: string }>;
    return cloneElement(element, {
      ...rest,
      className: cn(rootClass, element.props.className),
      'aria-busy': loading || undefined,
      'aria-disabled': isDisabled || undefined,
    } as Partial<{ className?: string }>);
  }

  return (
    <button
      ref={ref}
      type={type}
      disabled={isDisabled}
      aria-busy={loading || undefined}
      className={rootClass}
      {...rest}
    >
      {loading && <Spinner size="sm" label="처리 중" />}
      {!loading && LeftIcon && <LeftIcon className={iconSize[size]} />}
      {children}
      {!loading && RightIcon && <RightIcon className={iconSize[size]} />}
    </button>
  );
});
