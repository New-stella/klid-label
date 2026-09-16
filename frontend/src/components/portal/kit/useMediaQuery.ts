import { useEffect, useState } from 'react'

/**
 * 미디어 질의 하나를 구독한다. **레이아웃은 CSS 가 맡는 것이 원칙이고**, 이 훅은 CSS 로
 * 풀 수 없는 자리에만 쓴다 — 폭에 따라 **렌더할 것 자체가 달라질 때**다.
 * (예: 좁은 화면에서는 목록을 통째로 옆으로 밀어 보여 주므로 페이지 넘김 컨트롤이 아예 없다.
 *  CSS 로 감추면 보이지 않는 버튼이 탭 순서와 보조기술에 남는다.)
 *
 * 첫 렌더는 늘 `false` 다 — 브라우저에서 재는 값이라 그 뒤 effect 가 실제 값으로 맞춘다.
 * 그래서 기본값(false)일 때의 모습이 넓은 화면이어야 한다.
 *
 * 기준점은 design.md §9 의 둘(767 · 1023)만 쓴다. 제3의 기준을 이 훅으로 만들지 말 것.
 */
export function useMediaQuery(query: string) {
  const [matches, setMatches] = useState(false)
  useEffect(() => {
    const mq = window.matchMedia(query)
    const update = () => setMatches(mq.matches)
    update()
    mq.addEventListener('change', update)
    return () => mq.removeEventListener('change', update)
  }, [query])
  return matches
}
