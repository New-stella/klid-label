import { type ButtonHTMLAttributes, forwardRef } from 'react'
import { cx } from './util'
import './IconButton.css'

type Size = 'sm' | 'md' | 'lg'
type Tone = 'default' | 'primary' | 'muted' | 'danger' | 'favorite'

interface IconButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  /** sm=28 패널 토글 / md=32 카드·모달 액션 / lg=36 툴바·페이저 (아이콘은 박스의 약 절반) */
  size?: Size
  /** primary=코발트 아이콘 (표 안 다운로드처럼 그 줄의 주요 동작) / danger=삭제류 */
  tone?: Tone
  /** 도구 선택처럼 눌린 상태를 유지하는 토글일 때. 코발트 채움 + aria-pressed
   *  (tone="favorite" 은 표면을 안 칠하고 별만 금색으로 채운다) */
  selected?: boolean
  /** 누른 뒤 기다리는 중 (받는 중 등) — 잠기고 아이콘이 돈다. 화면은 도는 모양의 아이콘을 꽂는다 */
  busy?: boolean
  /** 아이콘만 있으므로 필수 */
  'aria-label': string
}

/**
 * 아이콘 전용 정사각 버튼. 삭제류는 tone="danger".
 *
 * ★ 잠글 때 `disabled` 와 `aria-disabled` 가 갈린다 — `disabled` 는 pointer-events 를 끊어
 *   **hover·focus 가 아예 오지 않는다.** 왜 못 누르는지를 마우스를 올려 알려 주려면
 *   `aria-disabled` 를 쓰고 onClick 을 걸지 않는다 (모양은 둘이 같다).
 * KRDS Button 의 icon variant 는 버튼 높이 스케일(36/44/52)을 공유해
 * 이 별도 스케일(28/32/36)과 어긋나므로 custom 으로 둔다. (기준: design.md §3)
 */
export const IconButton = forwardRef<HTMLButtonElement, IconButtonProps>(
  ({ className, size = 'md', tone = 'default', selected, busy, type = 'button', ...props }, ref) => (
    <button
      ref={ref}
      type={type}
      aria-pressed={selected}
      className={cx('klid-icon-btn', className)}
      data-size={size}
      data-tone={tone}
      data-busy={busy || undefined}
      {...props}
      aria-busy={busy || props['aria-busy']}
      aria-disabled={busy || props['aria-disabled']}
    />
  ),
)
IconButton.displayName = 'IconButton'
