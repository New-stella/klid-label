import type { ReactNode } from 'react'
import { cx } from './util'
import './EditorLayout.css'

/**
 * 편집 화면 틀 — 캔버스를 가운데 두고 **도구 칸 · 속성 칸 · 낱장 줄 · 재생 줄**이 둘러서는 판.
 * 칸 사이는 여백이 아니라 머리카락 선(gray-20)으로 끊는다. 라벨링 편집기가 쓴다.
 * 화면이 서는 틀이라 스토리북에서는 `레이아웃/편집기` 서랍에 있다 (2026-09-14 사용자 지시로 옮김).
 *
 * ```
 *  ┌──────────────────── header ────────────────────┐
 *  ├──────┬───────────── toolbar ──────────┬────────┤
 *  │ rail │                                │ panel  │
 *  │      │             stage              │        │
 *  ├──────┴────────────────────────────────┴────────┤
 *  │                     strip                      │
 *  ├────────────────────────────────────────────────┤
 *  │                     footer                     │
 *  └────────────────────────────────────────────────┘
 * ```
 *
 * - **도구 줄은 캔버스 폭만 쓴다.** 되돌리기 · 프레임 이동 · 확대는 캔버스에 거는 조작이라
 *   캔버스 바로 위에 붙고, 양옆 칸은 머리 줄 아래부터 끝까지 내려온다.
 *   단 **틀이 1200 보다 좁으면 도구 줄이 가로를 다 쓴다** — 포털 카드 안(좌측 메뉴를 편 폭 약 850)에서는
 *   캔버스 칸이 360 남짓이라 세 무리(되돌리기 · 프레임 이동 · 확대와 저장)가 한 줄에 못 서고 겹친다.
 * - 칸 폭은 도구 칸 200 · 속성 칸 290 으로 고정하고 캔버스가 남는 폭을 다 먹는다
 *   (도구 이름 + 단축키 한 줄 · 막대 이름 + 값 한 줄이 접히지 않는 가장 좁은 폭).
 * - 캔버스 바닥은 옅은 회색이다 — 흰 판 위에 흰 여백이 이어지면 그림이 어디서 끝나는지 안 보인다.
 *   넘치는 그림은 캔버스가 자른다.
 * - **편집기 높이는 캔버스가 정한다.** 도구 칸 · 속성 칸은 내용이 길어도 틀을 늘리지 않고 칸 안에서
 *   스크롤한다 (메타 입력처럼 긴 내용이 편집기를 늘여 캔버스가 화면 밖으로 밀려나지 않게).
 *   속성 칸의 탭은 `panelHead` 에 꽂는다 — 몸통이 스크롤해도 제자리에 남는다.
 * - 칸마다 안쪽 여백은 틀이 갖는다. 화면은 칸에 넣을 것만 꽂는다.
 */
export function EditorLayout({
  header,
  toolbar,
  rail,
  stage,
  panelHead,
  panel,
  strip,
  footer,
  label,
  railLabel,
  panelLabel,
  className,
}: {
  /** 맨 위 줄 — 닫기 · 파일 이름 · 저장 상태 · 도움말 (`EditorBar`) */
  header?: ReactNode
  /** 캔버스 위 도구 줄 (`EditorBar`) */
  toolbar?: ReactNode
  /** 왼쪽 도구 칸 */
  rail?: ReactNode
  /** 가운데 캔버스 */
  stage: ReactNode
  /** 오른쪽 속성 칸 머리 (탭 등) — 칸 안에서 스크롤해도 제자리에 남는다 */
  panelHead?: ReactNode
  /** 오른쪽 속성 칸 — 넘치면 칸 안에서 스크롤한다 */
  panel?: ReactNode
  /** 캔버스 아래 낱장 줄 */
  strip?: ReactNode
  /** 맨 아래 재생 줄 */
  footer?: ReactNode
  /** 편집 화면 전체의 이름 (보조기술용) */
  label: string
  railLabel?: string
  panelLabel?: string
  className?: string
}) {
  return (
    /* 바깥은 폭을 재는 그릇, 안쪽이 칸을 나누는 격자다 — 격자는 제 폭을 스스로 잴 수 없다 */
    <section className={cx('klid-editor-layout', className)} aria-label={label}>
      <div className="klid-editor-grid">
        {header && <div className="klid-editor-header">{header}</div>}
        {toolbar && <div className="klid-editor-toolbar">{toolbar}</div>}
        {rail && (
          <aside className="klid-editor-rail klid-scrollbar" aria-label={railLabel}>
            {rail}
          </aside>
        )}
        <div className="klid-editor-stage">{stage}</div>
        {panel && (
          <aside className="klid-editor-panel" aria-label={panelLabel}>
            {panelHead && <div className="klid-editor-panel-head">{panelHead}</div>}
            <div className="klid-editor-panel-body klid-scrollbar">{panel}</div>
          </aside>
        )}
        {strip && <div className="klid-editor-strip">{strip}</div>}
        {footer && <div className="klid-editor-footer">{footer}</div>}
      </div>
    </section>
  )
}

/**
 * 편집 화면의 한 줄 — **왼쪽 · 가운데 · 오른쪽** 세 자리. 가운데는 양끝 폭과 상관없이 줄 한가운데 선다.
 * 머리 줄(닫기 · 이름 | 저장 상태 | 도움말)과 도구 줄(되돌리기 | 프레임 이동 | 확대 · 저장)이 같이 쓴다.
 *
 *   <EditorBar start={<IconButton …/>} title="파일이름.mp4" center={<Badge …>저장됨</Badge>} end={…} />
 *
 * - `title` 은 왼쪽 자리 끝에 이름으로 선다 (15 · 600). 긴 파일 이름은 한 줄로 말줄임한다.
 * - 줄 안의 맨 글자(`/ 23` 같은 곁말)는 14 · gray-60 · 숫자 폭 고정으로 선다.
 */
export function EditorBar({
  start,
  title,
  center,
  end,
  className,
}: {
  start?: ReactNode
  /** 왼쪽 자리의 이름 (편집 중인 파일 이름 등) */
  title?: string
  center?: ReactNode
  end?: ReactNode
  className?: string
}) {
  return (
    <div className={cx('klid-editor-bar', className)}>
      <div className="klid-editor-bar-start">
        {start}
        {title && (
          <h2 className="klid-editor-bar-title" title={title}>
            {title}
          </h2>
        )}
      </div>
      <div className="klid-editor-bar-center">{center}</div>
      <div className="klid-editor-bar-end">{end}</div>
    </div>
  )
}
