import { createContext, useContext } from 'react';

/**
 * Card 조합형(UI-011)의 크기 프리셋 + 하위 컴포넌트 존재 여부 통로.
 *
 * `CardHeader` 는 `CardAction` 이 형제로 존재하는지(2열 그리드 전환), `Card` 루트는
 * `CardFooter` 가 존재하는지(하단 패딩을 0으로 줄여 Footer 가 카드 바닥까지 맞닿게)를
 * 알아야 한다. 두 판정 모두 "특정 하위 컴포넌트가 렌더 트리 어딘가에 실제로 마운트됐는가"이므로
 * `Field` 조합형(UI-099, `fieldContext.ts`)과 동일한 register/unregister 패턴을 쓴다 —
 * `React.Children` 얕은 타입검사보다 조건부 렌더링·언마운트에 견고하다.
 */
export type CardSize = 'default' | 'sm';

export type CardPart = 'footer' | 'action';

export interface CardContextValue {
  size: CardSize;
  hasFooter: boolean;
  hasAction: boolean;
  /** 하위 조립 요소가 자신의 마운트 여부를 알린다. */
  register: (part: CardPart, present: boolean) => void;
}

export const CardContext = createContext<CardContextValue | null>(null);

export function useCardContext(): CardContextValue | null {
  return useContext(CardContext);
}
