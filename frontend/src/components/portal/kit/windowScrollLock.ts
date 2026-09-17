import { useEffect } from 'react'

/**
 * **창이 사라질 때 문서의 스크롤 잠금을 되돌린다** (2026-09-16 · 실사고 수정).
 *
 * 킷(krds-react)의 창은 열릴 때 `document.body` 에 `scroll-no`(= `overflow: hidden`)를 붙이고
 * `#wrap` 에 `inert` 를 건다. 그런데 **되돌리는 일을 「열림 → 닫힘」 전이에서만** 한다 —
 * 정리 함수(cleanup)가 없어서, 창이 **열린 채 언마운트되면 잠금이 그대로 남는다.**
 *
 * 우리 화면은 대부분 그 형태다. 닫힌 창을 문서에 매달아 두면 킷이 `role="dialog"` 를 남겨
 * 보조기술이 「창이 둘」로 읽으므로, 화면들이 **닫을 때 아예 언마운트**한다(그 판단은 옳고
 * 되돌리지 않는다). 그래서 이 갈래가 실제로 매번 밟힌다.
 *
 * ★★**포털 임베드에서는 그 `body` 가 우리 것이 아니라 Host 문서의 것이다.** 즉 창을 한 번
 *   열었다 닫으면 **포털 페이지 전체가 새로고침 전까지 스크롤되지 않는다.** 2026-09-16 개발망
 *   실측: 증강 → 결과 확인 창을 닫은 뒤에도 `body.scroll-no` 가 남아 있었다.
 *
 * ⚠ **킷을 고치는 것이 아니라 우리 창 둘(`Modal`·`Dialog`)이 뒤를 치운다.** 킷은 외부
 *   의존이라 우리가 못 고치고, 호출하는 화면마다 치우게 하면 새 화면이 생길 때마다 빠진다.
 *
 * ★ 창이 겹쳐 열릴 수 있으므로 **세어서** 마지막 하나가 사라질 때만 되돌린다. 안 그러면 위에
 *   얹힌 창이 닫힐 때 아래 창의 잠금까지 풀려 뒤 화면이 구른다.
 *
 * ⚠ 인지·수용한 한계 — 마지막 창이 사라지는 그 순간 **Host 가 자기 창 때문에 잠가 둔 상태**라면
 *   그것까지 함께 풀린다. Host 창이 떠 있으면 우리 영역은 그 뒤에 가려 조작할 수 없으므로
 *   실제로 겹칠 일이 없다고 보고 단순한 쪽을 골랐다.
 */

/** 킷이 붙이는 잠금 표식. 이 값은 킷 구현을 따라간다 — 바꾸면 되돌리기가 조용히 멈춘다. */
const LOCK_CLASS = 'scroll-no'

/** 지금 서 있는 킷 창의 수. 모듈 하나를 공유하므로 창이 겹쳐도 한 곳에서 센다. */
let openWindows = 0

/** 시험이 세는 값을 초기화할 때만 쓴다(창이 열린 채 언마운트되는 상황을 반복 검증한다). */
export function resetWindowScrollLockCountForTest(): void {
  openWindows = 0
}

/**
 * 창이 열려 있는 동안 세고, **마지막 창이 사라지면** 문서에 남은 잠금을 되돌린다.
 *
 * 열림 → 닫힘 전이는 킷이 스스로 치우므로 여기서 한 번 더 지워도 결과가 같다(멱등).
 */
export function useWindowScrollLockRelease(open: boolean): void {
  useEffect(() => {
    if (!open) return
    openWindows += 1
    return () => {
      openWindows -= 1
      if (openWindows > 0) return
      document.body.classList.remove(LOCK_CLASS)
      // 킷이 창을 열며 뒤 화면을 조작 불가로 만든 자리도 함께 되돌린다(같은 갈래의 누수다).
      document.getElementById('wrap')?.removeAttribute('inert')
    }
  }, [open])
}
