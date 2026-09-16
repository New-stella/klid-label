import type { ReactNode } from 'react'
import { cx } from './util'
import './ProgressBar.css'

/**
 * 진행률 막대. 트랙 gray-10 + 바 primary-45.
 *
 * `caption` · `showValue` 를 주면 막대 위에 **무엇이 얼마나 갔는지(왼쪽) · 퍼센트(오른쪽)** 줄이
 * 붙는다 (2026-09-11 추가 — 저작도구 영상 올리는 중). 막대만으로는 정확한 값이 안 보이고,
 * 화면마다 그 줄을 따로 그리면 글자급·간격이 갈린다. 안 주면 종전과 같은 막대 하나다.
 *
 * `tone="danger"` 는 **도중에 멈춘 진행**이다 (2026-09-14 추가 — 저작도구 전송이 끊김). 막대와 퍼센트가
 * 위험 색으로 바뀌어 「여기까지 가다 멈췄다」를 말한다. `hint` 는 막대 **아래** 한 줄 안내다
 * (올리는 중에만 서는 「멈출 수 없습니다」 같은 말).
 */
export function ProgressBar({
  value,
  size = 'md',
  label,
  caption,
  showValue,
  tone = 'default',
  hint,
  className,
}: {
  /** 0~100 */
  value: number
  /** sm=6px 목록 안 / md=8px 기본 */
  size?: 'sm' | 'md'
  /** 스크린리더용 설명 */
  label?: string
  /** 막대 위 왼쪽 — 무엇이 얼마나 갔는지 (예: 「224 KB / 224 KB 보냈습니다」) */
  caption?: ReactNode
  /** 막대 위 오른쪽에 퍼센트를 글자로 적는다 */
  showValue?: boolean
  /** 도중에 멈춘 진행이면 danger */
  tone?: 'default' | 'danger'
  /** 막대 아래 안내 한 줄 */
  hint?: ReactNode
  className?: string
}) {
  const pct = Math.max(0, Math.min(100, value))
  const bar = (
    <div
      role="progressbar"
      aria-valuenow={Math.round(pct)}
      aria-valuemin={0}
      aria-valuemax={100}
      aria-label={label}
      className={cx('klid-progress', !caption && !showValue && !hint && className)}
      data-size={size}
      data-tone={tone}
    >
      <div className="bar" style={{ width: `${pct}%` }} />
    </div>
  )
  if (!caption && !showValue && !hint) return bar
  return (
    <div className={cx('klid-progress-field', className)} data-tone={tone}>
      {(caption || showValue) && (
        <div className="klid-progress-head">
          {caption && <span className="klid-progress-caption">{caption}</span>}
          {showValue && <strong className="klid-progress-value">{Math.round(pct)}%</strong>}
        </div>
      )}
      {bar}
      {hint && <p className="klid-progress-hint">{hint}</p>}
    </div>
  )
}
