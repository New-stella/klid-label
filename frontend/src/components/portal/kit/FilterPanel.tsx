import { useId, useState, type FormEvent, type ReactNode } from 'react'
import { ChevronDown, X } from 'lucide-react'
import { cx } from './util'
import './FilterPanel.css'

/**
 * 검색·필터 패널 — 목록 화면 맨 위에서 조건을 받는 표면.
 * KRDS 에 대응 컴포넌트가 없다. 공지사항(한 줄 검색)과 학습데이터 검색(여러 줄 폼)이 공유한다.
 *
 * layout
 *   row    한 줄 검색바. 첫 필드가 남는 폭을 먹고 버튼이 오른쪽에 붙는다 (공지사항)
 *   stack  라벨을 컨트롤 위에 얹고 필드를 세로로 쌓는다. 조건이 서넛일 때
 *   rows   한 줄(Line)에 필드를 최대 둘까지 놓고 줄마다 구분선. 조건이 많을 때
 *
 * 치수 기준: design.md §5 표면(흰 면 · slate 보더 · 라운드 16 · 패딩 24) ·
 * §6 세로 리듬(필드 사이 20 · 필드→버튼 줄 20).
 *
 * **안에 넣는 컨트롤은 sm(36) 으로 준다.** §4 가 "칩 md(34)는 버튼 sm(36)과 같은 줄에 놓아도
 * 어긋나지 않는다. 필터바에서 칩과 버튼을 섞을 때 이 정렬을 전제로 한다"고 정한 자리라,
 * 킷 기본값(lg 52)을 그대로 두면 칩(34)과 18px 벌어져 줄이 제각각으로 보인다.
 * 실행 버튼(actions)만 한 급 위 md(44)로 세워 조건과 실행을 가른다.
 */
export function FilterPanel({
  layout = 'stack',
  surface = 'card',
  id,
  actions,
  disabled,
  onSubmit,
  children,
  className,
  'aria-label': ariaLabel,
}: {
  layout?: 'row' | 'stack' | 'rows'
  /**
   * 패널이 딛고 선 바닥. 기본은 제 흰 면을 가진 카드(`card`)다.
   *
   * `bare` 는 **이미 흰 면 위에 놓일 때** 쓴다 (휴대폰의 조건 창 · FilterSheet). 창이 이미
   * 면이라 그 위에 또 면을 그리면 표면이 두 겹으로 보이고(design.md §7 라운드 위계),
   * 창 여백 안에 패널 여백이 겹쳐 좌우가 두 번 들여쓰인다. 면·테·라운드·안쪽 여백만 벗고
   * **줄 짜임과 세로 리듬은 그대로 간다** — 넓은 화면에서 보던 것과 같은 순서·같은 간격이어야
   * 폭이 바뀌어도 같은 조건판으로 읽힌다.
   */
  surface?: 'card' | 'bare'
  /**
   * `<form>` 의 id. 실행 버튼이 **폼 밖에** 설 때 이어 준다 (`<button form={id}>`).
   * 창에서는 실행 줄이 아랫동(`Modal.Footer`)으로 나가 폼 밖에 서므로, 그 버튼이 이 폼을
   * 제출하려면 이름으로 이어야 한다. 넓은 화면에서는 버튼이 폼 안에 있어 안 쓰이지만,
   * 화면이 같은 버튼 묶음을 두 자리에서 쓰므로 늘 걸어 두고 자리에 따라 저절로 이어지게 한다.
   */
  id?: string
  /** 검색·초기화 등 버튼 줄. row 는 첫 필드 오른쪽, 나머지는 아래 가운데 */
  actions?: ReactNode
  /**
   * 조건 칸을 통째로 잠근다 (2026-08-24 추가 — 생성형 AI 의 「진행 중」).
   *
   * 잠글 때만 칸 묶음을 `<fieldset>` 으로 바꿔 `disabled` 를 건다. **흐리게 만들고 클릭만 막는
   * 방식을 쓰지 않는 이유** — 그러면 탭으로는 여전히 들어가지고, 보조기술도 잠긴 줄 모른다.
   * fieldset 의 disabled 는 안의 모든 입력을 브라우저가 잠가 초점 자체가 안 간다.
   * 안 잠글 때는 `<div>` 그대로다 — 다른 화면의 마크업은 바뀌지 않는다.
   * ※ 실행 줄(actions)은 이 밖에 있다. 버튼을 잠그는 조건은 화면마다 다르므로 화면이 정한다.
   */
  disabled?: boolean
  onSubmit?: (e: FormEvent<HTMLFormElement>) => void
  children: ReactNode
  className?: string
  /** 소제목을 달지 않는 영역이라 라벨은 필수 (design.md §2) */
  'aria-label': string
}) {
  return (
    <form
      id={id}
      className={cx('klid-filter-panel', className)}
      data-layout={layout}
      data-surface={surface === 'card' ? undefined : surface}
      aria-label={ariaLabel}
      onSubmit={onSubmit ?? ((e) => e.preventDefault())}
    >
      {disabled ? (
        <fieldset className="fields" disabled>
          {children}
        </fieldset>
      ) : (
        <div className="fields">{children}</div>
      )}
      {actions && <div className="actions">{actions}</div>}
    </form>
  )
}

/** 필드 한 칸. 라벨과 컨트롤을 묶어 리듬을 맞춘다. 라벨은 어느 layout 에서나 컨트롤 위 */
function Field({
  label,
  hint,
  note,
  desc,
  grow,
  children,
  className,
}: {
  label: string
  /**
   * 라벨 옆 보조 설명 ("다중 선택" 등). **괄호는 여기서 씌운다** — 넘기는 쪽은 알맹이만 적는다.
   * 라벨과 힌트를 가르는 것이 굵기 500/400 하나뿐이라(크기·색·자간이 같다) 13px 한글에서는
   * 둘이 한 문장처럼 붙어 읽혔다. 크기를 낮추면 같은 줄에서 밑선이 어긋나고, 색을 낮추면
   * 대비가 미달한다(gray-40 은 흰 면에서 2.56:1 — 2026-08-08 에 gray-50 으로 올린 이력).
   * 남은 수단이 부호라 괄호로 가른다 (2026-08-19 확정).
   */
  hint?: string
  /**
   * 라벨 옆 **한 문장** (2026-08-21 추가 — 생성형 AI 장면 설명).
   * `hint` 와 갈래가 다르다: `hint` 는 라벨을 수식하는 낱말(`필수` · `다중 선택`)이라 괄호로
   * 묶지만, `note` 는 그 칸의 사정을 말하는 **온전한 문장**이라 괄호에 넣으면 문장이 갇힌다.
   * 마침표로 끝나는 문장이라 라벨과 한 덩어리로 읽힐 걱정도 없어, 가르는 것은 간격 12 하나면 된다
   * (`hint` 가 괄호를 쓰게 된 사정은 위 참조 — 낱말끼리는 굵기 차이만으로는 안 갈렸다).
   * 값이 길면 라벨 아래로 접힌다.
   */
  note?: string
  /**
   * 라벨 **아래** 한 줄 설명 (2026-09-14 추가 — 저작도구 증강 요청 · 자유 지시문).
   * `hint`·`note` 는 라벨 옆이라 문장이 길면 두 줄로 접혀 라벨과 엉킨다 — 긴 문장은 여기에 둔다.
   * 글 모양은 `note` 와 같다 (라벨과 같은 크기 · 굵기만 낮춤)
   */
  desc?: string
  /** 한 줄에 둘이 올 때 남는 폭을 먹는 쪽 (rows 전용) */
  grow?: boolean
  children: ReactNode
  className?: string
}) {
  return (
    <div className={cx('klid-filter-field', grow && 'grow', className)}>
      <span className="label">
        {label}
        {hint && <span className="hint">({hint})</span>}
        {note && <span className="note">{note}</span>}
      </span>
      {desc && <p className="desc">{desc}</p>}
      <div className="control">{children}</div>
    </div>
  )
}

/**
 * 한 줄 (rows 전용). 조건 칸을 담고 아래에 구분선을 남긴다.
 * 조건이 많은 화면에서 여백만으로는 조건의 경계가 안 읽혀 선을 하나 긋는다.
 * 칸 폭은 내용이 정하고, 남는 폭은 `grow` 를 단 한 칸이 먹는다 — 그래야 한 줄에 조건이
 * 둘일 때와 셋일 때가 같은 규칙으로 서고, 줄마다 다른 셈을 하지 않는다.
 */
function Line({ children, className }: { children: ReactNode; className?: string }) {
  return <div className={cx('klid-filter-line', className)}>{children}</div>
}

/**
 * 접이식 구획 (rows 전용) — "상세 검색"처럼 자주 안 쓰는 조건을 접어두는 자리.
 *
 * 상자로 감싸지 않는다. 필터 패널은 조건을 받는 한 표면이고 이건 그 표면의 아래쪽 구획일 뿐이라,
 * 안에 또 상자를 그리면 표면이 두 겹으로 보인다 (design.md §7 라운드 위계는 상자가 겹칠 때
 * 쓰는 규칙이지 겹치라는 뜻이 아니다). 경계는 줄 구분선과 토글 한 줄로 충분하다.
 *
 * 접힌 상태에서 `hint` 로 안에 무엇이 있는지 미리 알린다 — 예고 없는 빈 줄은 눌러볼 이유를 안 준다.
 * 내용은 지우지 않고 `hidden` 으로 감춘다. 지우면 접었다 펼 때 입력값이 날아간다.
 */
function Disclosure({
  label,
  hint,
  defaultOpen = false,
  children,
  className,
}: {
  label: string
  /** 접혀 있을 때만 보이는, 안에 든 조건 이름들 */
  hint?: string
  /** 처음에 펼쳐 둔다 (라벨링 메타 목록처럼 대부분 펼친 채 쓰고 몇 개만 접는 자리) */
  defaultOpen?: boolean
  children: ReactNode
  className?: string
}) {
  const [open, setOpen] = useState(defaultOpen)
  const id = useId()

  return (
    <div className={cx('klid-filter-disclosure', className)} data-open={open || undefined}>
      <button
        type="button"
        className="trigger"
        aria-expanded={open}
        aria-controls={id}
        onClick={() => setOpen((v) => !v)}
      >
        {/* 화살표는 이름 **앞**에 붙인다 (2026-08-07 조정). 토글은 줄 전체를 먹으므로 화살표를
            오른쪽 끝에 두면 이름과 화면 폭만큼(1280 에서 1700 넘게) 떨어져, 무엇을 여닫는
            화살표인지가 눈으로 안 이어진다. 붙여 두면 둘이 한 덩어리로 읽힌다 */}
        <ChevronDown aria-hidden />
        <span className="name">{label}</span>
        {!open && hint && <span className="hint">{hint}</span>}
      </button>
      <div id={id} className="panel" hidden={!open}>
        {children}
      </div>
    </div>
  )
}

export interface SelectedCondition {
  /** 목록 키 */
  key: string
  /** 칩에 적히는 값. 조건 이름이 아니라 **고른 값**이다 (`침수`, `2022~2024`) */
  label: ReactNode
  /** 보조기술이 읽을 문장. 값만으로는 어느 조건인지 모르므로 조건 이름을 붙인다 */
  removeLabel: string
  onRemove: () => void
}

/**
 * 선택 요약 줄 (rows 전용) — 지금 걸려 있는 조건을 낱개 칩으로 패널 아래에 모은다.
 *
 * 조건이 아홉 갈래로 흩어져 있고 그중 넷은 접혀 있다. 무엇을 골랐는지 확인하려면 줄마다
 * 눈을 옮기고 접힌 구획까지 펴 봐야 한다. 한 줄로 모으면 그 훑기가 한 번에 끝난다.
 * 칩마다 ×를 달아 여기서 바로 하나씩 뗀다 — 초기화(전부 떼기)와는 갈래가 다른 조작이다.
 *
 * 걸린 조건이 없으면 줄을 통째로 내지 않는다. 빈 라벨만 남으면 "조건이 있는데 못 찾는 것"처럼
 * 읽히고, 검색 전 첫 화면에서 패널만 한 줄 길어진다.
 */
function Summary({
  items,
  label = '선택한 조건',
  actions,
  className,
}: {
  items: readonly SelectedCondition[]
  label?: string
  /**
   * 지금 조건을 다루는 버튼 (저장·초기화). 줄 오른쪽 끝에 붙는다.
   * 검색 실행은 여기 두지 않는다 — 조건을 **다루는** 일과 조건으로 **찾는** 일은 갈래가 다르다.
   */
  actions?: ReactNode
  className?: string
}) {
  if (items.length === 0) return null

  return (
    <div className={cx('klid-filter-summary', className)}>
      <span className="label">
        {label}
        <span className="count">{items.length}</span>
      </span>
      <ul>
        {items.map((item) => (
          <li key={item.key}>
            <button type="button" onClick={item.onRemove} aria-label={item.removeLabel}>
              {item.label}
              <X aria-hidden />
            </button>
          </li>
        ))}
      </ul>
      {actions && <div className="actions">{actions}</div>}
    </div>
  )
}

/** 좁은 필드 두 개를 한 줄에 놓는 자리 (stack 전용). 모바일에서는 한 줄씩 */
function Row({ children, className }: { children: ReactNode; className?: string }) {
  return <div className={cx('klid-filter-row', className)}>{children}</div>
}

FilterPanel.Field = Field
FilterPanel.Line = Line
FilterPanel.Disclosure = Disclosure
FilterPanel.Summary = Summary
FilterPanel.Row = Row
