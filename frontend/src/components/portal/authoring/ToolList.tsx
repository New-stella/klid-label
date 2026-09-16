import { useRef, type KeyboardEvent } from 'react'
import type { LucideIcon } from 'lucide-react'
import { Kbd } from './Kbd'
import { cx } from '../kit/util'
import './ToolList.css'

/* 저작도구 화면에서만 쓰는 조각이라 스토리북 부품 목록에 두지 않고 화면 곁에 둔다 (2026-09-14 사용자 지시) */

export interface ToolListItem<T extends string = string> {
  value: T
  label: string
  /** 표식 — `swatch` 를 주면 그 자리에 색 칩이 대신 선다 */
  icon?: LucideIcon
  /** 표식 자리의 색 칩 (라벨 고르기처럼 낱개마다 정해진 색이 있는 목록). CSS 색 값(토큰) */
  swatch?: string
  /** 단축키. 줄 오른쪽 끝에 키 이름표로 선다 */
  shortcut?: string
  /** 이 줄만 잠근다 (회전 보기 중의 그리기 도구) */
  disabled?: boolean
  /** 얹었을 때 뜨는 설명 (브라우저 기본 말풍선) */
  title?: string
}

/**
 * 도구 목록 — **하나만 켜지는 도구**를 세로로 늘어놓는다. 줄마다 표식 · 이름 · 단축키.
 * 라벨링 편집기의 그리기 도구(선택 · 바운딩 박스 · 폴리곤)가 쓴다.
 *
 *   <ToolList label="그리기 도구" items={TOOLS} value={tool} onChange={setTool} />
 *
 * - 아이콘 버튼 줄(IconButton selected)로 두지 않는다 — 도구 이름과 단축키가 늘 보여야
 *   처음 온 사람이 무엇을 누르면 되는지 외우지 않아도 된다.
 * - 켜진 줄은 **옅은 코발트 면 + 코발트 글자**다. 채움(primary-50)으로 켜면 좁은 칸에 파란
 *   덩어리가 서서 캔버스보다 먼저 읽힌다.
 * - 보조기술에는 라디오 묶음으로 읽힌다. 화살표 위아래로 옮기고, 단축키는 `aria-keyshortcuts` 로 알린다
 *   (이름표 글자는 이름에 섞이지 않게 숨긴다 — 「바운딩 박스 B」 로 읽히지 않게).
 * - `disabled` 는 묶음 전체를 잠근다 (회전 중처럼 도구를 쓸 수 없는 동안). 까닭은 곁에 글로 적는다.
 * - 고르는 도구가 아니라 **누르는 조작**(회전 · 화면 맞춤)은 같은 줄 모양의 `ToolAction`,
 *   **켜고 끄는 조작**(그리드 표시)은 `ToolSwitch` 를 쓴다.
 */
export function ToolList<T extends string>({
  label,
  items,
  value,
  onChange,
  disabled,
  className,
}: {
  /** 보조기술이 읽을 묶음 이름 */
  label: string
  items: readonly ToolListItem<T>[]
  value: T
  onChange: (next: T) => void
  disabled?: boolean
  className?: string
}) {
  const refs = useRef<(HTMLButtonElement | null)[]>([])

  /* 라디오 묶음의 키 문법 — 화살표로 옮기면 그 도구가 바로 켜진다 */
  const onKeyDown = (e: KeyboardEvent, index: number) => {
    const step = e.key === 'ArrowDown' || e.key === 'ArrowRight' ? 1 : e.key === 'ArrowUp' || e.key === 'ArrowLeft' ? -1 : 0
    if (!step) return
    e.preventDefault()
    const next = (index + step + items.length) % items.length
    onChange(items[next].value)
    refs.current[next]?.focus()
  }

  return (
    <div
      className={cx('klid-tool-list', className)}
      role="radiogroup"
      aria-label={label}
      aria-disabled={disabled || undefined}
    >
      {items.map((item, i) => {
        const on = item.value === value
        return (
          <button
            key={item.value}
            ref={(el) => {
              refs.current[i] = el
            }}
            type="button"
            role="radio"
            aria-checked={on}
            aria-keyshortcuts={item.shortcut}
            tabIndex={on ? 0 : -1}
            disabled={disabled || item.disabled}
            title={item.title}
            className="klid-tool-item"
            onClick={() => onChange(item.value)}
            onKeyDown={(e) => onKeyDown(e, i)}
          >
            <ToolItemBody icon={item.icon} swatch={item.swatch} label={item.label} shortcut={item.shortcut} />
          </button>
        )
      })}
    </div>
  )
}

/**
 * 도구 줄 하나 — **누르는 조작**. 도구 목록 줄과 생김새가 같다 (표식 · 이름 · 단축키).
 * 라벨링 편집기의 보기 판(회전 · 화면 맞춤 · 영역 확대)이 쓴다 — 곁의 그리기 도구 판과 줄 모양을 맞춘다.
 *
 *   <ToolAction icon={RotateCcw} label="좌 90° 회전" onClick={rotateLeft} />
 *
 * - 켜고 끄는 조작(그리드 표시)은 `ToolSwitch` 를 쓴다.
 * - 여러 줄을 세울 때는 `klid-tool-list` 로 감싸면 도구 목록과 줄 사이가 같다.
 */
export function ToolAction({
  icon,
  label,
  shortcut,
  disabled,
  title,
  onClick,
}: {
  icon: LucideIcon
  label: string
  /** 단축키. 줄 오른쪽 끝에 키 이름표로 선다 */
  shortcut?: string
  disabled?: boolean
  /** 얹었을 때 뜨는 설명 (브라우저 기본 말풍선) */
  title?: string
  onClick?: () => void
}) {
  return (
    <button
      type="button"
      className="klid-tool-item"
      aria-keyshortcuts={shortcut}
      disabled={disabled}
      title={title}
      onClick={onClick}
    >
      <ToolItemBody icon={icon} label={label} shortcut={shortcut} />
    </button>
  )
}

/**
 * 도구 줄 하나 — **켜고 끄는 조작**. 표식 · 이름 · 오른쪽 끝 상태 글자(On · Off). 보기 판의 그리드 표시가 쓴다.
 *
 *   <ToolSwitch icon={Grid3x3} label="그리드 표시" checked={grid} onChange={setGrid} />
 *
 * - 줄 전체가 누르는 자리다. 누를 때마다 On · Off 가 바뀐다.
 * - 켜짐은 상태 글자가 말한다 — 줄 면을 코발트로 물들이지 않는다 (둘이 같이 켜지면 켜짐이 두 번 읽힌다).
 * - 보조기술에는 눌림 상태(`aria-pressed`)로 알린다. 상태 글자는 이름에 섞이지 않게 숨긴다.
 */
export function ToolSwitch({
  icon,
  label,
  checked,
  onChange,
  disabled,
}: {
  icon: LucideIcon
  label: string
  checked: boolean
  onChange: (next: boolean) => void
  disabled?: boolean
}) {
  return (
    <button
      type="button"
      className="klid-tool-item"
      aria-pressed={checked}
      disabled={disabled}
      onClick={() => onChange(!checked)}
    >
      <ToolItemBody icon={icon} label={label} />
      <span className="state" aria-hidden>
        {checked ? 'On' : 'Off'}
      </span>
    </button>
  )
}

/* 도구 줄의 속 — 도구 목록과 누르는 줄이 같은 속을 쓴다 */
function ToolItemBody({
  icon: Icon,
  swatch,
  label,
  shortcut,
}: {
  icon?: LucideIcon
  swatch?: string
  label: string
  shortcut?: string
}) {
  return (
    <>
      {swatch ? (
        <span className="swatch" style={{ backgroundColor: swatch }} aria-hidden />
      ) : Icon ? (
        <Icon className="icon" aria-hidden />
      ) : (
        <span aria-hidden />
      )}
      <span className="label">{label}</span>
      {shortcut && (
        <span className="key" aria-hidden>
          <Kbd>{shortcut}</Kbd>
        </span>
      )}
    </>
  )
}
