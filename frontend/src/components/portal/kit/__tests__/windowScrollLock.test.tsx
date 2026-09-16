/**
 * 포털 창(킷 `Modal`·`Dialog`)이 **열린 채 사라져도 문서 스크롤 잠금을 남기지 않는다.**
 *
 * 2026-09-16 개발망 실사고 — 증강 → 「결과 확인」 창을 닫은 뒤에도 `body.scroll-no` 가 남아
 * **포털(Host) 페이지 전체가 새로고침 전까지 스크롤되지 않았다.** 킷이 잠금을 「열림 → 닫힘」
 * 전이에서만 되돌리는데 우리 화면들은 닫을 때 **언마운트**해 그 전이가 없기 때문이다.
 *
 * 이 파일이 고정하는 것:
 *  1. 열린 채 언마운트되면 잠금이 풀린다 (임베드에서 Host 페이지가 죽지 않는다).
 *  2. 창이 겹쳐 있으면 **마지막 하나**가 사라질 때만 푼다 (위 창을 닫았다고 아래 창의 뒤가
 *     구르면 안 된다).
 *  3. 킷이 뒤 화면에 건 `inert` 도 같은 갈래라 함께 되돌린다.
 */
import { describe, expect, it, beforeEach } from 'vitest'
import { render } from '@testing-library/react'

import { Dialog } from '../Dialog'
import { Modal } from '../Modal'
import { resetWindowScrollLockCountForTest } from '../windowScrollLock'

beforeEach(() => {
  resetWindowScrollLockCountForTest()
  document.body.classList.remove('scroll-no')
  document.getElementById('wrap')?.remove()
})

describe('포털 창 — 문서 스크롤 잠금 되돌리기', () => {
  it('★창이_열린_채_사라져도_문서_스크롤_잠금이_남지_않는다', () => {
    const view = render(
      <Modal open onOpenChange={() => {}} title="증강 결과 확인">
        <p>결과</p>
      </Modal>,
    )

    // 킷이 열면서 문서를 잠근다 — 여기까지는 정상이다.
    expect(document.body.classList.contains('scroll-no')).toBe(true)

    // 화면들은 닫을 때 «언마운트» 한다(닫힌 창을 매달아 두면 창이 둘로 읽히기 때문).
    view.unmount()

    expect(document.body.classList.contains('scroll-no')).toBe(false)
  })

  it('★창이_겹쳐_있으면_마지막_하나가_사라질_때만_푼다', () => {
    const below = render(
      <Modal open onOpenChange={() => {}} title="아래 창">
        <p>아래</p>
      </Modal>,
    )
    const above = render(<Dialog open onOpenChange={() => {}} title="위 창" desc="확인" />)

    // 위 창만 사라졌다 — 아래 창이 아직 서 있으므로 잠금은 유지돼야 한다.
    above.unmount()
    expect(document.body.classList.contains('scroll-no')).toBe(true)

    below.unmount()
    expect(document.body.classList.contains('scroll-no')).toBe(false)
  })

  it('★킷이_뒤_화면에_건_inert_도_함께_되돌린다', () => {
    const wrap = document.createElement('div')
    wrap.id = 'wrap'
    document.body.appendChild(wrap)

    const view = render(<Dialog open onOpenChange={() => {}} title="자산을 삭제할까요?" desc="되돌릴 수 없습니다." />)
    expect(wrap.hasAttribute('inert')).toBe(true)

    view.unmount()
    expect(wrap.hasAttribute('inert')).toBe(false)
  })
})
