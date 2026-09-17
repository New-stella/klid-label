import { useEffect, useId, useLayoutEffect, useMemo, useRef, useState } from 'react'
import { Check, ChevronDown } from 'lucide-react'
import { cx } from './util'
import { useMediaQuery } from './useMediaQuery'
import './Dropdown.css'

export interface DropdownOption {
  value: string
  label: string
  disabled?: boolean
}

/**
 * 드롭다운 (선택 전용 콤보박스) — 킷 `Select` 를 대신한다.
 *
 * 킷 Select 는 네이티브 `<select>` 다. 닫힌 모양은 CSS 로 우리 값(보더 gray-30 · 라운드 8 ·
 * 패딩 12/36)을 줄 수 있지만 **펼친 목록은 OS 가 그린다** — 항목 높이·라운드·hover·선택 표시를
 * 우리가 못 정한다. 조건이 아홉인 필터 패널에서 목록만 운영체제 모양으로 열리면 그 순간
 * 화면이 두 갈래로 갈린다. 그래서 목록을 직접 그린다.
 *
 * 대가: 네이티브가 공짜로 주던 것(키보드·모바일 휠 피커·보조기술 이름)을 직접 짜야 한다.
 * ARIA APG 의 **Select-Only Combobox** 패턴을 그대로 따른다 —
 *   · 트리거는 `role="combobox"` + `aria-expanded` + `aria-controls`
 *   · 포커스는 **트리거에 머물고**, 지금 가리키는 항목은 `aria-activedescendant` 로만 옮긴다
 *     (포커스를 항목으로 옮기면 목록을 닫을 때 되돌릴 자리를 놓친다)
 *   · 목록은 `role="listbox"`, 항목은 `role="option"` + `aria-selected`
 *
 * 키보드: ↓↑ 이동 · Home·End 양끝 · Enter·Space 확정 · Esc 취소 · Tab 닫고 통과 ·
 * 글자 입력하면 그 글자로 시작하는 항목으로 건너뛴다(네이티브가 하던 일).
 *
 * ★ **폰에서는 목록이 아래에서 올라온다** (바텀시트 · 2026-09-07 사용자 지시).
 * 좁은 폭에서 목록을 트리거 밑에 붙여 열면 두 가지가 걸린다 — 조건이 화면 아래쪽에 있으면
 * 목록이 화면 밖으로 나가고, 항목이 손가락 하한(44)이라 다섯만 넘어도 화면을 덮는다.
 * 아래에서 올라오면 **자리가 늘 같고**(화면 아래), 길면 시트 안에서 구른다.
 * 시트 안에서 하나를 누르면 곧바로 확정되고 닫힌다 — 「확인」을 따로 두면 걸음이 하나 는다.
 * 트리거의 생김새와 고르는 규칙은 두 폭이 같다. **갈리는 것은 목록이 서는 자리뿐이다.**
 *
 * ★ **넓은 화면에서 아래 자리가 모자라면 목록이 위로 열린다** (2026-09-14 사용자 결정).
 * 목록은 기본으로 트리거 밑에 붙는데, 판 바닥 가까이 선 드롭다운(재생기 맨 아래의 배속)은 그 밑에
 * 목록이 들어갈 자리가 없다. 넘친 것을 잘라 내는 판(카드 · 저작도구 판)이 목록 아래쪽을 잘라
 * 끝 항목을 못 골랐다. 판마다 자르기를 빼는 대신 **열 때마다 실제로 잰다** — 목록을 가두는
 * 판들과 창 중 가장 좁은 바닥까지 목록이 안 들어가고, 위쪽 자리가 더 넉넉하면 뒤집는다.
 *
 * 조건 줄에 이름표를 따로 세울 자리가 없을 때는 `label` 로 **트리거 안에 이름을 붙인다** —
 * 고른 값만 서 있으면 「전체」 두 개가 나란히 서서 무엇을 거르는 조건인지 화면에서 사라진다.
 *
 * variant
 *   default  보더 있는 폼 컨트롤 (필터 조건)
 *   sorting  보더 없는 글자만 (목록 위 정렬 기준). 폼이 아니라 목록에 얹히는 조작이라 면이 없다
 *   capsule  알약 (2026-09-02 추가). `default` 에서 **라운드와 폭 잡는 법만** 다르다 —
 *            모서리가 알약이고, 줄을 채우지 않고 고른 값 길이만큼만 선다. 기간 칩
 *            (`DateRangeFilter`)·캡슐 입력(`klid-input-capsule`)과 한 줄에 서는 조건 줄에 쓴다.
 *            반대로 한 줄에 각진 폼 컨트롤이 서 있으면 `default` 로 둔다 — 섞으면 같은 줄이
 *            두 모양으로 갈린다
 */
export function Dropdown({
  options,
  value,
  defaultValue,
  onChange,
  size = 'medium',
  variant = 'default',
  disabled,
  label,
  placeholder = '선택',
  className,
  'aria-label': ariaLabel,
}: {
  options: readonly DropdownOption[]
  /** 넘기면 제어형. 안 넘기면 `defaultValue` 로 시작하는 비제어형 */
  value?: string
  defaultValue?: string
  onChange?: (value: string) => void
  size?: 'small' | 'medium' | 'large'
  variant?: 'default' | 'sorting' | 'capsule'
  disabled?: boolean
  /**
   * 트리거 안에 붙는 조건 이름 (「상태」 「다운로드」). 이름표를 밖에 세울 자리가 없는
   * 조건 줄에서 쓴다 — 값보다 한 톤 옅게, 값 앞에 선다. 보조기술은 `aria-label` 이 읽는다
   */
  label?: string
  /** 고른 값이 목록에 없을 때만 보인다. 값이 `''` 인 항목이 있으면 그 라벨이 이긴다 */
  placeholder?: string
  className?: string
  /** 라벨을 밖(FilterPanel.Field)에서 달기 때문에 필수 */
  'aria-label': string
}) {
  const [inner, setInner] = useState(defaultValue ?? '')
  const current = value ?? inner

  const [open, setOpen] = useState(false)
  const [active, setActive] = useState(-1)
  /** 목록을 트리거 위로 연다 (위 ★). 열 때마다 새로 잰다 */
  const [above, setAbove] = useState(false)
  /** 폰이면 목록이 아래에서 올라온다 (위 ★). 기준점은 design.md §9 의 767 */
  const sheet = useMediaQuery('(max-width: 767px)')

  const rootRef = useRef<HTMLDivElement>(null)
  const triggerRef = useRef<HTMLButtonElement>(null)
  const listRef = useRef<HTMLUListElement>(null)
  /** 연속으로 친 글자를 모아 둔다. 1초 쉬면 비운다 (네이티브 select 와 같은 셈) */
  const typed = useRef({ text: '', timer: 0 })

  const id = useId()
  const listId = `${id}-list`
  const optionId = (i: number) => `${id}-opt-${i}`

  const selectedIndex = useMemo(
    () => options.findIndex((o) => o.value === current),
    [options, current],
  )
  const selected = selectedIndex >= 0 ? options[selectedIndex] : undefined

  /** 못 고르는 항목은 건너뛰며 다음 자리를 찾는다. 끝에서 멈춘다 (순환하지 않는다 — 순환하면
      목록의 끝을 손끝으로 알 수 없다) */
  const step = (from: number, dir: 1 | -1) => {
    for (let i = from + dir; i >= 0 && i < options.length; i += dir) {
      if (!options[i].disabled) return i
    }
    return from
  }
  const edge = (dir: 1 | -1) =>
    dir === 1 ? step(-1, 1) : step(options.length, -1)

  const openList = (at?: number) => {
    if (disabled) return
    setActive(at ?? (selectedIndex >= 0 ? selectedIndex : edge(1)))
    setOpen(true)
  }
  const close = (focusTrigger = true) => {
    setOpen(false)
    setActive(-1)
    if (focusTrigger) triggerRef.current?.focus()
  }
  const commit = (i: number) => {
    const opt = options[i]
    if (!opt || opt.disabled) return
    if (value === undefined) setInner(opt.value)
    onChange?.(opt.value)
    close()
  }

  /* 바깥을 누르면 닫는다. click 이 아니라 pointerdown 이라야 — 누른 자리에서 곧바로 닫혀야
     그 클릭이 아래 요소에 그대로 닿는다 (click 을 기다리면 한 번은 닫기에만 쓰인다) */
  useEffect(() => {
    if (!open || sheet) return
    const onDown = (e: PointerEvent) => {
      if (!rootRef.current?.contains(e.target as Node)) close(false)
    }
    document.addEventListener('pointerdown', onDown)
    return () => document.removeEventListener('pointerdown', onDown)
  }, [open, sheet])

  /* 아래 자리가 모자라면 위로 연다 (위 ★). 목록이 **아래로 선 첫 모습**을 그리기 전에 재서,
     뒤집어야 하면 화면에 나오기 전에 뒤집는다 — 한 번 아래로 떴다가 튀어 오르지 않는다.
     목록을 가두는 판은 **세로로 넘친 것을 자르는 조상**이다. 그 판들과 창 중 가장 좁은 위·아래
     끝이 목록이 쓸 수 있는 자리다. 트리거 ↔ 목록 사이도 그린 값을 재서 쓴다 (숫자를 따로 적지 않는다) */
  useLayoutEffect(() => {
    if (!open || sheet) {
      setAbove(false)
      return
    }
    const trigger = triggerRef.current
    const list = listRef.current
    if (!trigger || !list) return
    let roomTop = 0
    let roomBottom = window.innerHeight
    for (let el = rootRef.current?.parentElement; el; el = el.parentElement) {
      if (getComputedStyle(el).overflowY === 'visible') continue
      const r = el.getBoundingClientRect()
      roomTop = Math.max(roomTop, r.top)
      roomBottom = Math.min(roomBottom, r.bottom)
    }
    const t = trigger.getBoundingClientRect()
    const l = list.getBoundingClientRect()
    const need = l.height + (l.top - t.bottom)
    const below = roomBottom - t.bottom
    setAbove(below < need && t.top - roomTop > below)
  }, [open, sheet])

  /* 시트가 열려 있는 동안 뒤 화면이 구르지 않게 잠근다 — 시트를 밀었는데 뒤가 따라 움직이면
     무엇을 구르고 있는지 알 수 없다. 창(모달)이 이미 잠가 둔 자리에서도 되돌릴 값을 제 것으로
     들고 있다가 그대로 돌려주므로 겹쳐 열려도 어긋나지 않는다 */
  useEffect(() => {
    if (!open || !sheet) return
    const prev = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    return () => {
      document.body.style.overflow = prev
    }
  }, [open, sheet])

  /* 가리키는 항목이 목록 밖으로 나가면 끌어온다. 포커스가 트리거에 있어 브라우저가 대신
     스크롤해 주지 않는다 — activedescendant 패턴이 직접 져야 하는 몫이다 */
  useEffect(() => {
    if (!open || active < 0) return
    listRef.current
      ?.querySelector(`#${CSS.escape(optionId(active))}`)
      ?.scrollIntoView({ block: 'nearest' })
  })

  useEffect(() => () => window.clearTimeout(typed.current.timer), [])

  const typeahead = (ch: string) => {
    window.clearTimeout(typed.current.timer)
    typed.current.text += ch.toLowerCase()
    typed.current.timer = window.setTimeout(() => {
      typed.current.text = ''
    }, 1000)

    const q = typed.current.text
    const from = (open ? active : selectedIndex) + 1
    /* 지금 자리 **다음**부터 한 바퀴 돈다. 같은 글자를 거듭 치면 그 글자로 시작하는 항목들을
       차례로 훑게 된다 (네이티브 select 의 동작) */
    for (let n = 0; n < options.length; n += 1) {
      const i = (from + n) % options.length
      const o = options[i]
      if (!o.disabled && o.label.toLowerCase().startsWith(q)) {
        if (open) setActive(i)
        else openList(i)
        return
      }
    }
  }

  const onKeyDown = (e: React.KeyboardEvent) => {
    if (disabled) return
    switch (e.key) {
      case 'ArrowDown':
      case 'ArrowUp': {
        e.preventDefault()
        const dir = e.key === 'ArrowDown' ? 1 : -1
        if (!open) openList()
        else setActive((i) => step(i, dir))
        return
      }
      case 'Home':
      case 'End': {
        e.preventDefault()
        const at = edge(e.key === 'Home' ? 1 : -1)
        if (!open) openList(at)
        else setActive(at)
        return
      }
      case 'Enter':
      case ' ':
        e.preventDefault()
        if (open) commit(active)
        else openList()
        return
      case 'Escape':
        if (open) {
          e.preventDefault()
          close()
        }
        return
      case 'Tab':
        /* 막지 않는다 — 목록만 닫고 포커스는 다음 컨트롤로 흘려보낸다.
           고르던 값은 버린다 (Esc 와 같다). 확정은 Enter 로만 */
        if (open) close(false)
        return
      default:
        if (e.key.length === 1 && !e.altKey && !e.ctrlKey && !e.metaKey) {
          e.preventDefault()
          typeahead(e.key)
        }
    }
  }

  return (
    <div
      ref={rootRef}
      className={cx('klid-dropdown', className)}
      data-size={size}
      data-variant={variant}
      data-open={open || undefined}
      data-sheet={sheet || undefined}
    >
      <button
        ref={triggerRef}
        type="button"
        className="trigger"
        role="combobox"
        aria-label={ariaLabel}
        aria-controls={listId}
        aria-expanded={open}
        aria-activedescendant={open && active >= 0 ? optionId(active) : undefined}
        disabled={disabled}
        onClick={() => (open ? close() : openList())}
        onKeyDown={onKeyDown}
      >
        {label && (
          <span className="label" aria-hidden>
            {label}
          </span>
        )}
        <span className="value" data-placeholder={selected ? undefined : true}>
          {selected?.label ?? placeholder}
        </span>
        <ChevronDown aria-hidden />
      </button>

      {/* 시트일 때 뒤를 덮는 면. 눌러서 닫는 길이기도 하다 (바깥을 눌러 닫던 자리를 대신한다).
          `hidden` 으로 감춰 닫혔을 때는 손가락도 보조기술도 닿지 않는다 */}
      <div className="sheet-dim" hidden={!open || !sheet} onClick={() => close(false)} aria-hidden />

      {/* 이름 줄과 목록을 **한 판으로 묶는다.** 시트에서는 이 판이 아래에 붙는 상자가 되고,
          이름은 제자리에 남은 채 목록만 구른다. 넓은 화면에서는 판이 자리를 안 먹으므로
          (`display: contents`) 목록이 종전처럼 트리거 밑에 붙는다 — 묶기 전과 같은 모양이다 */}
      <div className="panel">
        {/* 시트에는 **무엇을 고르는 중인지**를 적는다. 덮개가 화면을 가려 트리거가 안 보이므로,
            이름이 없으면 목록만 덩그러니 올라온다 (조건이 아홉인 필터 창에서는 특히).
            글은 보조기술이 읽던 이름(`aria-label`)을 그대로 쓴다 — 새로 받지 않는다.
            목록의 이름은 `aria-label` 이 이미 잇고 있으므로 이 줄은 보조기술에서 뺀다 */}
        <p className="sheet-title" hidden={!open || !sheet} aria-hidden>
          {ariaLabel}
        </p>

        {/* 닫혀도 지우지 않고 `hidden` 으로 감춘다 — 트리거의 `aria-controls` 가 가리키는
            대상이 사라지면 보조기술이 관계를 잃는다 */}
        <ul
          ref={listRef}
          id={listId}
          className="list"
          role="listbox"
          aria-label={ariaLabel}
          hidden={!open}
          data-above={above || undefined}
        >
          {options.map((o, i) => (
            <li
              key={o.value}
              id={optionId(i)}
              role="option"
              className="option"
              aria-selected={i === selectedIndex}
              aria-disabled={o.disabled || undefined}
              data-active={i === active || undefined}
              /* 누르는 순간 트리거에서 포커스가 빠지지 않게 막는다 — activedescendant 패턴은
                 포커스가 트리거에 머무는 것이 전제다 */
              onPointerDown={(e) => e.preventDefault()}
              onClick={() => commit(i)}
              /* 손가락으로 훑는 동안 지나친 항목이 켜지면 고를 것을 잘못 짚은 것처럼 보인다 —
                 가리키는 표시는 마우스에만 둔다 */
              onPointerMove={(e) => e.pointerType === 'mouse' && !o.disabled && setActive(i)}
            >
              <span className="label">{o.label}</span>
              {i === selectedIndex && <Check aria-hidden />}
            </li>
          ))}
        </ul>
      </div>
    </div>
  )
}
