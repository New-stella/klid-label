import { ArrowUpRight, ChevronRight } from 'lucide-react'
import { cx } from './util'
import { href as withBase } from './basePath'
import './MoreLink.css'

/**
 * 섹션 우상단 "전체보기" 링크 — 포털의 **바로가기 모양은 이것 하나다.**
 * 표식은 둘뿐이고 하는 일이 정한다 (ArrowRight·화살표 문자 금지).
 *   · 기본 ChevronRight — 같은 목록을 **이어서 본다** (전체보기)
 *   · `jump` ArrowUpRight — **다른 탭으로 건너뛴다** (2026-08-24 사용자 결정 · 대시보드
 *     「다운로드 이력」 · 저작도구 「증강 요청 현황·결과」)
 * 라우터 도입 전이라 a 태그. 도입 시 Link 로 교체.
 *
 * href 를 주지 않으면 **링크가 아닌 글자**로만 나온다 — 카드처럼 바깥 전체가 이미 링크라
 * 안에 링크를 또 둘 수 없는 자리용이다. 모양은 링크일 때와 같다.
 * `onClick` 을 주면 **같은 모양의 버튼**이다 — 주소를 새로 띄우지 않고 화면 안에서 탭을
 * 옮기는 자리(저작도구 탭)용이다.
 */
export function MoreLink({
  href,
  onClick,
  jump = false,
  label = '전체보기',
  className,
}: {
  /** 없으면 링크가 아닌 글자로 나온다 (바깥이 이미 링크인 자리) */
  href?: string
  /** 화면 안에서 옮기는 자리. 주면 버튼으로 나온다 */
  onClick?: () => void
  /** 다른 탭으로 건너뛰는 링크 — 표식이 대각선 화살표로 바뀐다 */
  jump?: boolean
  label?: string
  className?: string
}) {
  const Mark = jump ? ArrowUpRight : ChevronRight
  const inner = (
    <>
      {label}
      <Mark aria-hidden />
    </>
  )
  if (onClick)
    return (
      <button type="button" className={cx('klid-more-link', className)} onClick={onClick}>
        {inner}
      </button>
    )
  if (!href) return <span className={cx('klid-more-link', className)}>{inner}</span>
  return (
    <a href={withBase(href)} className={cx('klid-more-link', className)}>
      {inner}
    </a>
  )
}
