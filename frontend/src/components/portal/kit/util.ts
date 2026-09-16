import { Children, cloneElement, isValidElement, type ReactElement, type ReactNode } from 'react'
import { glue } from './text'

/** 클래스 결합용. 단순 필터·join (Tailwind 미사용 프로젝트라 tailwind-merge 불필요). */
export function cx(...classes: (string | false | null | undefined)[]) {
  return classes.filter(Boolean).join(' ')
}

/**
 * 부품이 받은 글에 **늘 붙어야 하는 자리**를 걸어 준다 (utils/text.ts `glue`).
 * 「확인할 수 / 있습니다」 「확인한 / 뒤」 「이미지· / 영상을」 처럼 뜻이 갈리는 자리에서
 * 접히지 않게 한다 — 화면은 글만 꽂고 아무것도 하지 않는다.
 *
 * 글자만이 아니라 조각(`<b>` 로 강조가 섞인 안내문 등)도 받아 **안쪽 글자까지** 훑는다.
 * 화면이 `phrase()` 로 접을 자리를 직접 고른 글은 그대로 지나간다.
 */
export function glued(node: ReactNode): ReactNode {
  if (typeof node === 'string') return glue(node)
  if (Array.isArray(node)) return Children.map(node, glued)
  if (isValidElement(node)) {
    const { children } = node.props as { children?: ReactNode }
    if (children !== undefined) return cloneElement(node as ReactElement, undefined, glued(children))
  }
  return node
}
