import { useCallback, useEffect, useMemo, useState, type HTMLAttributes } from 'react';

import { cn } from '@/lib/cn';

import { CardContext, useCardContext, type CardPart, type CardSize } from './cardContext';

/**
 * 컴파운드 카드 컨테이너 (UI-011).
 *
 * 루트(`Card`)는 셸만 제공하고 헤더·본문·푸터는 전용 하위 컴포넌트를 children 으로 조합해
 * 구성한다 — `Card` 자체엔 title/description/actions/footer 같은 콘텐츠 prop 이 없다.
 * 순서·존재 여부는 호출부가 결정한다(헤더 없이 `CardContent` 만 두거나 `CardFooter` 를
 * 생략하는 등 자유 조합).
 *
 * 구성: `CardHeader`(헤더 영역 — `CardAction` 이 있으면 자동으로 1fr/auto 2열 그리드로 전환) ·
 * `CardTitle`(제목 — 일반 텍스트 요소이며 시맨틱 헤딩이 아니다) ·
 * `CardDescription`(제목 아래 보조 설명, 톤 다운 텍스트) ·
 * `CardAction`(헤더 우측 상단 액션 슬롯) · `CardContent`(본문 영역) ·
 * `CardFooter`(하단 구분선 + 옅은 배경의 캡션 영역 — 존재하면 Card 자체 하단 패딩이 0으로
 * 줄어 Footer 가 카드 바닥까지 맞닿는다).
 */
export interface CardProps extends HTMLAttributes<HTMLDivElement> {
  /** 카드 내부 여백 프리셋. 'sm'이면 여백 토큰이 한 단계 줄고 CardTitle 폰트도 작아진다. */
  size?: CardSize;
}

export function Card({ size = 'default', className, children, ...rest }: CardProps) {
  const [present, setPresent] = useState({ footer: false, action: false });

  const register = useCallback((part: CardPart, isPresent: boolean) => {
    setPresent((prev) => (prev[part] === isPresent ? prev : { ...prev, [part]: isPresent }));
  }, []);

  const value = useMemo(
    () => ({ size, hasFooter: present.footer, hasAction: present.action, register }),
    [size, present.footer, present.action, register],
  );

  return (
    <CardContext.Provider value={value}>
      <div
        data-slot="card"
        data-size={size}
        className={cn(
          'flex flex-col rounded-lg border border-gray-200 bg-white shadow-sm',
          size === 'sm' ? 'gap-3 py-3' : 'gap-4 py-4',
          present.footer && 'pb-0',
          className,
        )}
        {...rest}
      >
        {children}
      </div>
    </CardContext.Provider>
  );
}

/** 헤더 영역 — `CardAction` 이 형제로 존재하면 1fr/auto 2열 그리드로 전환된다. */
export function CardHeader({ className, children, ...rest }: HTMLAttributes<HTMLDivElement>) {
  const card = useCardContext();
  const size = card?.size ?? 'default';

  return (
    <div
      data-slot="card-header"
      className={cn(
        'grid items-start gap-1',
        card?.hasAction ? 'grid-cols-[1fr_auto]' : 'grid-cols-1',
        size === 'sm' ? 'px-3' : 'px-6',
        className,
      )}
      {...rest}
    >
      {children}
    </div>
  );
}

/** 카드 제목 — 일반 텍스트 요소(시맨틱 헤딩 아님). 헤딩 탐색이 필요하면 호출부가 별도 처리. */
export function CardTitle({ className, ...rest }: HTMLAttributes<HTMLParagraphElement>) {
  const card = useCardContext();
  const size = card?.size ?? 'default';

  return (
    <p
      data-slot="card-title"
      className={cn(
        'truncate font-semibold text-gray-900',
        size === 'sm' ? 'text-body-sm' : 'text-title-sm',
        className,
      )}
      {...rest}
    />
  );
}

/** 제목 아래 보조 설명 — 톤 다운 텍스트. */
export function CardDescription({ className, ...rest }: HTMLAttributes<HTMLParagraphElement>) {
  return (
    <p
      data-slot="card-description"
      className={cn('text-body-md text-gray-500', className)}
      {...rest}
    />
  );
}

/** 헤더 우측 상단 액션 슬롯 — CardHeader 의 2번째 grid column 에 고정된다. */
export function CardAction({ className, children, ...rest }: HTMLAttributes<HTMLDivElement>) {
  const card = useCardContext();
  const { register } = card ?? {};

  useEffect(() => {
    register?.('action', true);
    return () => register?.('action', false);
  }, [register]);

  return (
    <div
      data-slot="card-action"
      className={cn(
        'col-start-2 row-start-1 flex items-center gap-2 self-start justify-self-end',
        className,
      )}
      {...rest}
    >
      {children}
    </div>
  );
}

/** 본문 영역. */
export function CardContent({ className, ...rest }: HTMLAttributes<HTMLDivElement>) {
  const card = useCardContext();
  const size = card?.size ?? 'default';

  return (
    <div
      data-slot="card-content"
      className={cn(size === 'sm' ? 'px-3' : 'px-6', className)}
      {...rest}
    />
  );
}

/** 하단 구분선 + 옅은 배경의 캡션 영역 — 존재 시 Card 루트의 하단 패딩을 0으로 줄인다. */
export function CardFooter({ className, children, ...rest }: HTMLAttributes<HTMLDivElement>) {
  const card = useCardContext();
  const { register } = card ?? {};
  const size = card?.size ?? 'default';

  useEffect(() => {
    register?.('footer', true);
    return () => register?.('footer', false);
  }, [register]);

  return (
    <div
      data-slot="card-footer"
      className={cn(
        'rounded-b-lg border-t border-gray-100 bg-gray-50 text-sub text-gray-600',
        size === 'sm' ? 'px-3 py-2' : 'px-6 py-3',
        className,
      )}
      {...rest}
    >
      {children}
    </div>
  );
}
