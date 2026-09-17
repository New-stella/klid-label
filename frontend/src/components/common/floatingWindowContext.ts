import { createContext, useContext } from 'react';

/**
 * 비모달 창이 본문에 알려 주는 배치 정보. [@design UI-156]
 *
 * 본문(두 칸)이 자기 폭을 직접 잴 수 없어서가 아니라, <b>판정을 한 곳에 두기 위해서</b>다.
 * 창 폭은 창이 소유한 상태이고, 칸을 위아래로 쌓을지는 그 폭 하나로 정해진다 — 본문이 스스로
 * 재면 같은 값을 두 곳이 들고 있게 되어 크기 조절 중에 한쪽만 갱신되는 구간이 생긴다.
 */
export interface FloatingWindowLayout {
  /** 창의 현재 폭(px). */
  width: number;
  /** 폭이 좁아 칸을 위아래로 쌓아야 하는가. */
  stacked: boolean;
}

const FloatingWindowLayoutContext = createContext<FloatingWindowLayout>({
  width: 0,
  stacked: false,
});

export const FloatingWindowLayoutProvider = FloatingWindowLayoutContext.Provider;

/** 창 본문에서 배치 정보를 읽는다. 창 밖에서 부르면 기본값(쌓지 않음)이다. */
export function useFloatingWindowLayout(): FloatingWindowLayout {
  return useContext(FloatingWindowLayoutContext);
}
