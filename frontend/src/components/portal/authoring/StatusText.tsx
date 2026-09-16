import type { ReactNode } from 'react'
import { AlertTriangle, Check, CircleX, Info, type LucideIcon } from 'lucide-react'
import { cx } from '../kit/util'
import './StatusText.css'

/* 저작도구 화면에서만 쓰는 조각이라 스토리북 부품 목록에 두지 않고 화면 곁에 둔다 (2026-09-14 사용자 지시) */

/** 상태 한 줄의 성격. 표식과 색을 함께 정한다 */
export type StatusTextTone = 'success' | 'info' | 'warning' | 'danger'

/** 성격이 표식을 고른다 — 화면이 고르지 않으므로 같은 성격은 어디서나 같은 글리프다 (Alert 와 같은 방식) */
const TONE_ICON: Record<StatusTextTone, LucideIcon> = {
  success: Check,
  info: Info,
  warning: AlertTriangle,
  danger: CircleX,
}

/**
 * 상태 한 줄 — **표식 하나 + 짧은 말.** 면도 테도 없이 글자로만 선다.
 * 라벨링 편집기 머리 줄의 「저장됨」처럼 **화면이 지금 어떤 상태인지** 곁에서 알리는 자리다.
 *
 *   <StatusText tone="success">저장됨</StatusText>
 *
 * - 배지와 갈래가 다르다 — 배지는 목록의 한 건에 붙는 표식이고, 이 줄은 **화면 전체의 지금**이다.
 *   도구 줄 가운데에 알약이 서면 누를 수 있는 것처럼 읽힌다.
 * - 안내 띠(`Alert`)와도 다르다 — 띠는 읽고 넘어가야 하는 사정이고, 이 줄은 흘끗 보고 마는 상태다.
 * - 바뀌면 소리로도 알린다 (`role="status"`). 저장이 끝났는지는 눈으로만 확인할 수 있어서는 안 된다.
 * - 글은 이름꼴이라 마침표를 찍지 않는다 (UX-writing.md §2).
 */
export function StatusText({
  tone = 'success',
  icon,
  children,
  className,
}: {
  tone?: StatusTextTone
  /** 성격 글리프가 못 말하는 자리만 바꾼다 (저장 중의 도는 표식 등) */
  icon?: LucideIcon
  children: ReactNode
  className?: string
}) {
  const Icon = icon ?? TONE_ICON[tone]
  return (
    <span className={cx('klid-status-text', className)} data-tone={tone} role="status">
      <Icon className="icon" aria-hidden />
      {children}
    </span>
  )
}
