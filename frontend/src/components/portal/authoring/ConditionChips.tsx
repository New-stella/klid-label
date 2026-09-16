import type { ReactNode } from 'react'
import { cx } from '../kit/util'
import './ConditionChips.css'

/* 저작도구 화면에서만 쓰는 조각이라 스토리북 부품 목록에 두지 않고 화면 곁에 둔다 (2026-09-14 사용자 지시) */

/**
 * 조건 칩 줄 — **이름 · 값** 두 도막 칩을 흘려 세운다 (「시간대 밤」). 저작도구 증강 요청 줄이 쓴다.
 *
 *   <ConditionChips items={[{ label: '시간대', value: '밤' }]} empty="생성 조건 -" />
 *
 * - 이름은 한 단 흐리게, 값은 진하게 — 칩 여럿을 훑을 때 값이 먼저 읽힌다.
 * - 누르는 칩이 아니다 (고르는 칩 `ChipGroup` 과 갈래가 다르다). 그래서 테를 옅게 두고 얹어도 안 바뀐다.
 * - 조건이 하나도 없으면 칩 대신 `empty` 글이 선다.
 * - `look="text"` 는 면 · 테를 걷고 글자만 늘어놓아 사이를 공용 세로선으로 가른다 (업로드 자산 줄).
 */
export function ConditionChips({
  items,
  empty,
  label,
  look = 'chip',
  className,
}: {
  items: readonly { label: string; value: ReactNode }[]
  /** 조건이 없을 때 대신 서는 글 */
  empty?: ReactNode
  /** 묶음 이름 (보조기술용) */
  label?: string
  /** 칩 모양(기본) · 글자 모양(면 없이 세로선으로 가름) */
  look?: 'chip' | 'text'
  className?: string
}) {
  if (items.length === 0) return empty ? <span className="klid-condition-chips-empty">{empty}</span> : null
  return (
    <ul
      className={cx('klid-condition-chips', className)}
      aria-label={label}
      data-look={look === 'text' ? 'text' : undefined}
    >
      {items.map((it, i) => (
        <li key={it.label} className="klid-condition-chip">
          {look === 'text' && i > 0 && <span className="klid-meta-div" aria-hidden />}
          <span className="klid-condition-chip-label">{it.label}</span>
          <span className="klid-condition-chip-value">{it.value}</span>
        </li>
      ))}
    </ul>
  )
}
