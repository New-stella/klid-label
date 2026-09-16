import { Pagination, type PaginationProps } from 'krds-react'
import { useMediaQuery } from './useMediaQuery'

/**
 * 목록 아래 페이지 넘김 줄. 킷 Pagination 을 그대로 쓰되 **휴대폰에서 번호 수만 줄인다.**
 *
 * 킷은 767 이하에서 줄을 두 줄로 접는다 — 위에 `이전`·`다음`, 아래에 번호. 그것을 한 줄로
 * 되돌린 것이 krds-theme.css 의 규칙이고(§ Pagination), 한 줄에 담으려면 폭을 두 군데서
 * 줄여야 한다. 화살표만 남기는 것은 CSS 가 하고, **번호를 줄이는 것은 여기서 한다** —
 * 몇 개를 그릴지는 부품이 정하는 값이라 CSS 가 손댈 수 없다.
 *
 * 넓은 화면  1 … 6 7 8 … 13   (현재 페이지 앞뒤로 하나씩)
 * 휴대폰     1 … 7 … 13       (현재 페이지만)
 *
 * 390 실측: 줄 폭 343 에 넓은 화면 구성 그대로면 448 이 되어 넘친다. 앞뒤를 접으면 298 이다.
 * 첫 렌더는 넓은 화면 모습이다 (useMediaQuery 주석) — 기준점 767 은 design.md §9 의 값이다.
 *
 * 부르는 쪽이 직접 `siblingCount` 를 주면 그 값이 이긴다.
 *
 * **번호를 눌러도 화면이 맨 위로 튀지 않는다.** 킷은 번호·다음을 `href="#"` 링크로 그려서
 * 누르는 순간 브라우저가 문서 맨 위로 이동한다 — 목록만 바뀌길 기대한 사용자는 보던 자리를
 * 잃는다. 줄 전체에서 링크 눌림을 받아 그 이동만 막는다 (2026-09-01 화면 검토 지시).
 */
export function PageNav({ onClick, ...props }: PaginationProps) {
  const phone = useMediaQuery('(max-width: 767px)')
  return (
    <Pagination
      siblingCount={phone ? 0 : 1}
      {...props}
      onClick={(e) => {
        if ((e.target as HTMLElement).closest('a')) e.preventDefault()
        onClick?.(e)
      }}
    />
  )
}
