import type { ReactNode } from 'react'
import { cx, glued } from '../kit/util'
import './NoteList.css'

/* 저작도구 화면에서만 쓰는 조각이라 스토리북 부품 목록에 두지 않고 화면 곁에 둔다 (2026-09-14 사용자 지시) */

/**
 * 안내 사항 — 그 자리에서 알아 둘 규칙을 불렛 목록으로 늘어놓는다.
 *
 *   <NoteList items={['한 번에 한 편씩 올립니다.', '다 올리면 아래 목록에 「마킹 대기」로 나타납니다.']} />
 *
 * `Alert` 와 갈리는 자리: Alert 는 **무슨 일인가**를 표식(ⓘ ✓ △ ✕)이 말하는 띠다.
 * 이것은 성격이 하나뿐인 늘 떠 있는 규칙이라 표식이 할 말이 없고, 여러 가지를 **줄마다 하나씩**
 * 읽혀야 한다 — 그래서 표식 대신 불렛이 서고, 면·선 없이 회색 글로만 물러난다.
 *
 * **★ 문구가 다르다고 상자를 새로 만들지 않는다.** 화면은 `items` 에 글만 꽂는다.
 * 불렛·간격·색은 상자가 갖는다.
 */
export function NoteList({ items, className }: { items: readonly ReactNode[]; className?: string }) {
  return (
    <ul className={cx('klid-note-list', className)}>
      {items.map((item, i) => (
        <li key={i}>{glued(item)}</li>
      ))}
    </ul>
  )
}
