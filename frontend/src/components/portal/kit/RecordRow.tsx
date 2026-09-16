import type { ReactNode } from 'react'
import { TriangleAlert } from 'lucide-react'
import { cx } from './util'
import './RecordRow.css'

/**
 * 줄 카드 — 내가 낸 건 하나를 **카드 한 장**으로 세운다. 저작도구 증강 요청 현황이 쓴다.
 *
 * ```
 *  ┌────────────────────────────────────────────────────────────┐
 *  │ 제목  [배지]                               요청 2026-09-11 │
 *  │ 본문 (칩 · 실패 사유 …)                     도착 2026-09-11 │
 *  │                                                [걸음]      │
 *  └────────────────────────────────────────────────────────────┘
 *
 *  factsBelow (저작도구 업로드 자산 · 증강 요청 목록 — 2026-09-14 사용자 지시)
 *  ┌────────────────────────────────────────────────────────────┐
 *  │ 제목  [배지]                          [오류 문구]  [⋮]    │
 *  │ 안내 · 본문                                                │
 *  │ 일시 | 일시                                  [걸음] [걸음] │
 *  └────────────────────────────────────────────────────────────┘
 *
 *  factsBelow + actionRow (저작도구 업로드 자산 — 2026-09-14 사용자 지시)
 *  ┌────────────────────────────────────────────────────────────┐
 *  │ 제목  [배지]                                  [오류 문구]  │
 *  │ 안내 · 본문                                                │
 *  │ 일시 | 일시                                                │
 *  │ [삭제] [보조] [보조]                          [걸음] [걸음] │
 *  └────────────────────────────────────────────────────────────┘
 * ```
 *
 * - 왼쪽은 **무엇인가**(제목 · 배지 · 본문), 오른쪽은 **언제 · 무엇을 할 수 있나**(사실값 · 걸음)다.
 *   표로 늘어놓기엔 한 건의 내용(조건 칩 · 사유 문장)이 칸에 안 들어가 줄마다 높이가 달라지는 자리다.
 * - `selected` 는 그 건을 **골라 아래에 펼친 것**이다 — 테가 코발트로 짙어지고 면이 옅게 깔린다.
 *   얹었을 때는 면만 옅게 깔린다.
 * - 좁은 폭에서는 오른쪽 사실값 · 걸음이 본문 아래로 내려와 왼쪽에 선다 (factsBelow 짜임은 PC 전용이라 제외).
 * - 제목은 자르지 않는다 — 파일 이름은 뒤쪽(날짜 · 판)이 갈리는 자리라 말줄임하면 서로 구분이 안 된다.
 */
export function RecordRow({
  title,
  badge,
  hint,
  children,
  facts,
  action,
  selected,
  titleSize = 'medium',
  factsInline,
  factsBelow,
  actionNote,
  menu,
  actionRow,
  actionStart,
  className,
}: {
  title: ReactNode
  /** 제목 바로 옆 상태 배지 */
  badge?: ReactNode
  /** 제목 아래 한 줄 — 지금 이 건이 어디쯤인지 (「프레임을 뽑는 중입니다」) */
  hint?: ReactNode
  /** 제목 아래 본문 */
  children?: ReactNode
  /** 오른쪽 위 사실값 — 한 줄에 하나 */
  facts?: readonly ReactNode[]
  /** 오른쪽 아래 걸음 */
  action?: ReactNode
  /** 골라서 펼친 건 */
  selected?: boolean
  /** 제목 글 크기 — 기본은 본문 글급, `large` 는 한 칸 위 (업로드 자산 목록) */
  titleSize?: 'medium' | 'large'
  /** 사실값을 한 줄에 옆으로 늘어놓고 사이를 세로선으로 가른다 (업로드 자산 목록 — 올린 일시 | 만료) */
  factsInline?: boolean
  /**
   * 사실값을 오른쪽이 아니라 **왼쪽 맨 아래**(본문 다음)에 세운다 — 제목 → 안내 → 본문 → 일시.
   * 오른쪽은 위에 오류 문구 · 더보기, 아래 끝에 걸음이다 (저작도구 업로드 자산 · 증강 요청 목록)
   */
  factsBelow?: boolean
  /** 오른쪽 위 — 그 건의 오류 문구. 글만 넘기면 경고 표식을 붙여 위험 색 글자로 세운다. 더보기가 있으면 그 왼쪽 */
  actionNote?: ReactNode
  /** 오른쪽 위 끝 — 더보기 · 삭제처럼 걸음이 몇 개든 **자리가 고정**인 조작 */
  menu?: ReactNode
  /**
   * 걸음을 **맨 아래 제 줄**에 세운다 — 왼쪽 끝 `actionStart` · 오른쪽 끝 `action`.
   * 줄마다 걸음 줄 높이가 같아야 목록에서 버튼이 한 선에 선다. 걸음이 하나도 없는 줄은 끈다 (빈 줄이 남는다).
   * factsBelow 짜임에서만 받는다 (저작도구 업로드 자산)
   */
  actionRow?: boolean
  /** 맨 아래 걸음 줄 왼쪽 — 받기 같은 보조 걸음 (`actionRow` 일 때) */
  actionStart?: ReactNode
  className?: string
}) {
  const factList = facts?.length ? (
    <ul className="klid-record-row-facts" data-inline={factsInline || undefined}>
      {facts.map((f, i) => (
        <li key={i}>
          {/* 한 줄일 때는 값 사이를 공용 세로선으로 가른다 */}
          {factsInline && i > 0 && <span className="klid-meta-div" aria-hidden />}
          {f}
        </li>
      ))}
    </ul>
  ) : null
  const sideFacts = factsBelow ? null : factList
  const ownRow = factsBelow && actionRow
  const top =
    actionNote || menu ? (
      <div className="klid-record-row-top">
        {actionNote && (
          <p className="klid-record-row-note">
            <TriangleAlert aria-hidden />
            {actionNote}
          </p>
        )}
        {menu && <div className="klid-record-row-menu">{menu}</div>}
      </div>
    ) : null

  return (
    /* 바깥은 폭을 재는 그릇, 안쪽이 칸을 나누는 격자다 — 격자는 제 폭을 스스로 잴 수 없다 */
    <div className={cx('klid-record-row', className)} data-selected={selected || undefined}>
      <div
        className="klid-record-row-grid"
        data-facts-below={factsBelow || undefined}
        data-action-row={ownRow || undefined}
      >
        <div className="klid-record-row-main">
          <div className="klid-record-row-head">
            <span className="klid-record-row-title" data-size={titleSize === 'large' ? 'large' : undefined}>
              {title}
            </span>
            {badge}
          </div>
          {(hint || children) && (
            <div className="klid-record-row-body">
              {hint && <p className="klid-record-row-hint">{hint}</p>}
              {children}
            </div>
          )}
          {factsBelow && factList}
          {ownRow && actionStart && <div className="klid-record-row-action-start">{actionStart}</div>}
        </div>
        {(top || sideFacts || action) && (
          <div className="klid-record-row-side">
            {top}
            {sideFacts}
            {action && <div className="klid-record-row-action">{action}</div>}
          </div>
        )}
      </div>
    </div>
  )
}
