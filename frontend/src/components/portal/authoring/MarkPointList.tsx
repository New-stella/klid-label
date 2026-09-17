import type { ReactNode } from 'react'
import { X } from 'lucide-react'
import { cx } from '../kit/util'
import './MarkPointList.css'

/* 저작도구 화면에서만 쓰는 조각이라 스토리북 부품 목록에 두지 않고 화면 곁에 둔다 (2026-09-14 사용자 지시) */

/**
 * 마킹 지점 목록 — 찍어 둔 지점 **하나당 알약 하나**가 흘러 선다. 저작도구 업로드 영상 마킹의
 * 오른쪽 목록 카드가 쓴다 (위 재생 무대의 마킹 지점 줄 `MarkTimeline` 과 같은 지점을 가리킨다).
 *
 *   <MarkPointList points={[0, 300]} pointLabel={(f) => `F${f} (00:10)`} selected={300}
 *     onSelect={seek} onRemove={remove} removeLabel={(f) => `마킹 삭제 F${f}`} empty={<EmptyState … />} />
 *
 * - 알약을 누르면 그 지점을 **고른다** (화면은 재생 위치를 그 지점으로 옮긴다). 고른 알약 하나만 강조.
 * - `onRemove` 를 주면 알약 오른쪽에 ×가 선다 — 지우는 조작이 따로 있는 자리(수동 방식)만 준다.
 *   고르기와 지우기가 한 알약에 있어 누르는 자리를 둘로 나눈다 (알약 전체가 지우기면 고르지 못한다).
 * - 목록이 길면 **이 목록 안에서만** 굴린다 — 수백 개가 카드를 끝없이 늘이지 않게.
 * - 지점이 없으면 `empty` 가 그 자리에 선다 (빈 줄만 남기지 않는다).
 */
export function MarkPointList({
  points,
  pointLabel = String,
  selected,
  onSelect,
  onRemove,
  removeLabel = (p) => `${pointLabel(p)} 삭제`,
  label,
  empty,
  className,
}: {
  points: readonly number[]
  /** 알약에 적히는 말 */
  pointLabel?: (point: number) => string
  /** 고른 지점 (없으면 강조 없음) */
  selected?: number | null
  onSelect?: (point: number) => void
  /** 주면 알약마다 ×(지우기)가 선다 */
  onRemove?: (point: number) => void
  /** ×를 보조기술이 읽는 말 */
  removeLabel?: (point: number) => string
  /** 목록 이름 (보조기술용) */
  label: string
  /** 지점이 없을 때 대신 선다 */
  empty?: ReactNode
  className?: string
}) {
  if (points.length === 0) return empty ? <>{empty}</> : null
  return (
    <ul className={cx('klid-mark-points klid-scrollbar', className)} aria-label={label}>
      {points.map((p) => {
        const on = selected === p
        return (
          <li key={p} className="klid-mark-point" data-selected={on || undefined} data-removable={onRemove ? true : undefined}>
            <button
              type="button"
              className="klid-mark-point-pick"
              aria-pressed={onSelect ? on : undefined}
              onClick={onSelect ? () => onSelect(p) : undefined}
            >
              {pointLabel(p)}
            </button>
            {onRemove && (
              <button type="button" className="klid-mark-point-remove" aria-label={removeLabel(p)} onClick={() => onRemove(p)}>
                <X aria-hidden />
              </button>
            )}
          </li>
        )
      })}
    </ul>
  )
}
