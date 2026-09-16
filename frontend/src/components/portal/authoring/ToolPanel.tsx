import type { ReactNode } from 'react'
import { cx, glued } from '../kit/util'
import './ToolPanel.css'

/* 저작도구 화면에서만 쓰는 조각이라 스토리북 부품 목록에 두지 않고 화면 곁에 둔다 (2026-09-14 사용자 지시) */

/**
 * 도구 판 — 편집 화면 옆 칸에 서는 **작은 묶음 하나**. 이름(왼쪽) · 오른쪽 한 자리 · 내용 · 딸린 안내.
 * 라벨링 편집기의 그리기 도구 · 보기 · 객체 목록 · 객체 속성 · 이미지 조절이 같은 판을 쓴다.
 *
 *   <ToolPanel title="그리기 도구"><ToolList … /></ToolPanel>
 *   <ToolPanel title="이미지 조절" aside={<Button size="small" variant="text">초기화</Button>}
 *     note="조절값은 저장하지 않습니다.">…</ToolPanel>
 *   <ToolPanel title="객체 목록" surface={false} aside="0개 객체">…</ToolPanel>
 *
 * - 구획 카드(`.klid-section-card` · 패딩 24 · 라운드 16)를 쓰지 않는 까닭 — 옆 칸이 190~290 이라
 *   패딩 24 를 두르면 도구 이름과 단축키가 한 줄에 못 선다. 여기는 **패딩 12 · 라운드 8** 이다
 *   (§5 패딩 ≥ 라운드 × 1.25 · §7 내용 상자 8).
 * - **이름은 사다리 밖의 이름표다** (design.md §2 「사다리 밖」). 도구 칸의 묶음 이름은 그 묶음을
 *   부르기만 하는 글이라 제목 사다리(20 · 17)를 따르지 않는다 — 층 3 은 15 · 층 4 는 13 으로 선다.
 * - 오른쪽 자리에 글만 꽂으면 보조 글(13 · gray-60 · 숫자 폭 고정)로 선다 — 건수 자리다.
 *   건수는 배지로 두르지 않는다 (design.md §4 「수치는 배지 없이 글자만」).
 * - `surface={false}` 는 면을 끈다 — 옆 칸 바닥에 바로 서는 묶음(객체 목록)이나 판 안의 판.
 */
export function ToolPanel({
  title,
  id,
  level = 3,
  aside,
  note,
  surface = true,
  children,
  className,
}: {
  title: string
  /** 이름 요소의 id. 판을 `aria-labelledby` 로 이 이름에 잇는다 */
  id?: string
  /** 이름 층. 3 = 옆 칸의 묶음(15) · 4 = 묶음 안의 묶음(13) */
  level?: 3 | 4
  /** 이름 줄 오른쪽 끝 (건수 · 작은 걸음 하나) */
  aside?: ReactNode
  /** 판 맨 아래 딸린 안내. 문장이면 마침표를 찍는다 (UX-writing.md §2) */
  note?: ReactNode
  /** 면(흰 바탕 · gray-20 선 · 라운드 8)을 갖는다 */
  surface?: boolean
  children?: ReactNode
  className?: string
}) {
  const Heading = level === 4 ? 'h4' : 'h3'
  return (
    <section
      className={cx('klid-tool-panel', className)}
      data-level={level}
      data-surface={surface || undefined}
      aria-labelledby={id}
    >
      <div className="klid-tool-panel-head">
        <Heading id={id} className="klid-tool-panel-title">
          {title}
        </Heading>
        {aside && <div className="klid-tool-panel-aside">{aside}</div>}
      </div>
      {children}
      {note && <p className="klid-tool-panel-note">{glued(note)}</p>}
    </section>
  )
}

/**
 * 도구 판 안의 한 줄 — **이름(왼쪽) · 조작 하나(오른쪽)**. 이벤트 어노테이션의 후보 줄(근거 후보 · 추가).
 * 누르는 조작 자체가 줄이면(보기 판의 회전 · 화면 맞춤 · 그리드 표시) 이것이 아니라 `ToolAction` 을 쓴다.
 */
export function ToolRow({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="klid-tool-row">
      <span className="klid-tool-row-label">{label}</span>
      {children}
    </div>
  )
}
