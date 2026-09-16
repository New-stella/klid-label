import type { ReactNode } from 'react'
import { LoaderCircle, type LucideIcon } from 'lucide-react'
import { cx, glued } from './util'
import './EmptyState.css'

/**
 * 결과·목록이 비었을 때. (기준: design.md §5 빈 상태)
 *
 * size 규칙
 *   md (기본)  페이지 본문 영역. 세로 80 + 아이콘
 *   sm         카드·패널 안. 세로 56
 *   xs         표 안(td)·좁은 사이드 패널. 세로 24, 표면·아이콘 없음
 *
 * ★ **md 는 아이콘을 반드시 받는다** (2026-09-08 확정). 세로 80 짜리 판에 글 두 줄만 서면
 * 아래위가 비어 「덜 만든 화면」으로 읽힌다. 관리자 열 화면과 포털 둘이 그렇게 서 있었다.
 *
 * 아이콘은 **빈 까닭**이 고른다 — 화면이 고르지 않는다.
 *   조건으로 걸러 못 찾았다  → `SearchX` **하나로 고정**. 어느 화면에서나 같은 글리프여야
 *                              「없는 게 아니라 조건이 좁다」가 한눈에 읽힌다
 *   아직 아무것도 없다        → **그 대상의 없음꼴** (AI 모델 PackageOpen · 활용사례 ImageOff ·
 *                              지역코드 MapPinOff). 없음꼴이 없는 대상은 빈 함 `Inbox`
 *   아직 불러오는 중이다      → `busy` 를 준다. 아이콘은 **도는 고리로 고정**된다 (`icon` 을 안 받는다).
 *                              빈 목록과 같은 판에 서야 목록이 올 자리가 그대로 읽힌다 (2026-09-14)
 */
export function EmptyState({
  icon,
  title,
  desc,
  action,
  size = 'md',
  busy = false,
  className,
}: {
  icon?: LucideIcon
  title: string
  desc?: ReactNode
  action?: ReactNode
  size?: 'md' | 'sm' | 'xs'
  /** 불러오는 중 — 아이콘 자리에 고리가 돌고, 글을 보조기술이 읽어 준다 */
  busy?: boolean
  className?: string
}) {
  const Icon = busy ? LoaderCircle : icon
  const waiting = busy ? { role: 'status', 'aria-busy': true, 'data-busy': '' } : {}
  if (size === 'xs') {
    return (
      <div className={cx('klid-empty-state', className)} data-size="xs" {...waiting}>
        {glued(title)}
        {desc && <span className="desc">{glued(desc)}</span>}
        {action && <div className="action">{action}</div>}
      </div>
    )
  }
  return (
    <div className={cx('klid-empty-state', className)} data-size={size} {...waiting}>
      {Icon && <Icon className="icon" aria-hidden />}
      <p className="title">{glued(title)}</p>
      {desc && <p className="desc">{glued(desc)}</p>}
      {action && <div className="action">{action}</div>}
    </div>
  )
}
