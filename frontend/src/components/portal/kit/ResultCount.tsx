import { cx } from './util'
import './ResultCount.css'

/**
 * 목록 위 결과 건수 줄 ("총 128건").
 * KRDS 에 대응 컴포넌트가 없다. 목록형 화면이 공유한다 (공지사항·FAQ·활용사례·문의하기).
 * 아래 목록과 한 덩어리라 사이 간격은 컴포넌트 내부값 12 를 쓴다 (design.md §6).
 *
 * ★ `role="status"` 를 둔다 (design.md §15). 조건을 걸거나 검색어를 바꾸면 **결과가 몇 건으로
 * 좁혀졌는지가 이 줄에서만 바뀐다.** 눈으로는 숫자가 바뀌는 것이 보이지만, 표시만 갈아 두면
 * 소리로는 아무 말도 나오지 않아 검색이 먹은 건지 알 수 없다. status 는 하던 말을 끊지 않고
 * 뒤에 이어 읽어 준다 — 조건을 연달아 만지는 자리라 끊는 alert 는 맞지 않는다.
 */
export function ResultCount({ total, className }: { total: number; className?: string }) {
  return (
    <p className={cx('klid-result-count', className)} role="status">
      총 <strong>{total.toLocaleString()}</strong>건
    </p>
  )
}
