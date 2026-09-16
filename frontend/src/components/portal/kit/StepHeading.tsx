import type { ReactNode } from 'react'
import type { LucideIcon } from 'lucide-react'
import { cx, glued } from './util'
import './StepHeading.css'

export interface StepHeadingProps {
  /** 이름 위 한 줄 — 몇 번째 걸음인지 (예: 2단계) */
  overline?: ReactNode
  /** 이 걸음의 이름 */
  title: ReactNode
  /** 이름 아래 한 줄. 이름만으로 무엇을 하는 자리인지 가리기 어려울 때 둔다 */
  desc?: ReactNode
  /**
   * 크기는 층이 정한다 (design.md §2 제목 사다리).
   * lg = 풀폭 페이지 제목(30 · 기본) · md = 구획 제목(20) · sm = 구획 안 묶음 제목(17)
   */
  size?: 'lg' | 'md' | 'sm'
  /** 제목이 서는 층. 안 주면 lg·md 는 h2, sm 은 h3 */
  as?: 'h1' | 'h2' | 'h3' | 'h4'
  /** 제목 요소의 id. 구획을 `aria-labelledby` 로 이 제목에 이을 때 준다 */
  id?: string
  /** 이름 앞 표식. 글자 곁에 서므로 보조기술에는 숨긴다 */
  icon?: LucideIcon
  /**
   * 이름 옆 짧은 말 — **무엇에 대한 구획인지** 가리키는 이름표(대상 파일명 등).
   * 설명(`desc`)과 갈래가 다르다: 설명은 문장이고 이쪽은 이름과 한 줄에 서는 이름표다
   */
  note?: ReactNode
  /** 오른쪽 끝 한 자리 (건수 · 걸음 · 짧은 사정 한 줄) */
  aside?: ReactNode
  className?: string
}

/**
 * 단계 제목 — 머리말(몇 번째 걸음) 위, 이름 아래로 서는 한 벌.
 * 순서 있는 문서를 여러 화면으로 나눠 놓은 자리(이용 가이드)에서 **지금 어느 걸음인지**를
 * 제목 자체가 말한다.
 *
 * `StepTrail` 과 갈린다. 그쪽은 다섯 걸음을 나란히 늘어놓고 그 사이를 옮겨 다니는 줄이고,
 * 이쪽은 지금 걸음 하나만 제목으로 세운다 — 옮겨 가는 길은 본문 끝의 `StepPager` 가 맡는다.
 *
 * ★ 구획 제목도 이 부품이 맡는다 (2026-09-14 — 같은 짜임을 따로 만든 구획 제목 줄을 걷고 옵션으로 흡수).
 *   `size="md"`·`"sm"` 로 층을 내리고, 이름 옆 짧은 말(`note`) · 오른쪽 끝 자리(`aside`)를 꽂는다.
 *   오른쪽 자리는 설명이 있으면 설명 줄에, 없으면 이름과 가운데를 맞춘다.
 *
 * 면을 갖지 않는다. 맨바닥에 서는 글이라 좌우 8 로 들여 다른 맨바닥 줄과 한 선에 선다
 * (design.md §6). 흰 면(`.klid-section-card`) 안에서는 면의 안쪽 여백이 기준선이라 들이지 않는다.
 * 위아래 여백은 갖지 않는다 — 제목과 그 아래 판 사이는 화면이 정한다.
 */
export function StepHeading({
  overline,
  title,
  desc,
  size = 'lg',
  as,
  id,
  icon: Icon,
  note,
  aside,
  className,
}: StepHeadingProps) {
  const Title = as ?? (size === 'sm' ? 'h3' : 'h2')
  return (
    <div
      className={cx('klid-step-heading', className)}
      data-size={size}
      data-desc={desc ? true : undefined}
      data-aside={aside ? true : undefined}
    >
      <div className="name">
        {overline && <p className="overline">{overline}</p>}
        <div className="title-row">
          <Title id={id} className="title">
            {Icon && <Icon aria-hidden />}
            {title}
          </Title>
          {note && <span className="note">{note}</span>}
        </div>
        {desc && <p className="desc">{size === 'lg' ? desc : glued(desc)}</p>}
      </div>
      {aside && <div className="aside">{aside}</div>}
    </div>
  )
}
