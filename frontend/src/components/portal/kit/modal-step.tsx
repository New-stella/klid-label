import { Button } from 'krds-react'

/**
 * 창이 주는 **걸음 하나**. 글과 갈 곳(또는 할 일)만 넘긴다 — 크기·색은 창이 갖는다.
 *
 * 두 갈래(`Modal` · `Dialog`)가 같은 타입을 쓴다. 걸음은 창의 성격이 아니라 **사용자가
 * 고르는 것**이라, 창이 무엇이든 「글 · 갈 곳 · 잠김 · 무게」 넷으로 똑같이 적힌다.
 * 갈리는 것은 그 걸음이 **서는 자리**뿐이고(모달 오른쪽 끝 · 다이얼로그 가운데)
 * 그건 창이 정한다 (krds-theme.css § 창은 두 갈래다).
 */
export type Step = {
  /** 버튼에 쓰는 글 */
  label: string
  /** **다른 화면으로 가는 걸음**이면 주소를 준다 — 링크 성질(a)을 살린다 */
  href?: string
  /** 이 화면에서 **하는 일**이면 여기에 */
  onClick?: () => void
  /** 누르면 창이 닫히기만 하는 걸음 (`취소`·`머무르기`) */
  close?: boolean
  /** 조건을 채워야 누를 수 있는 걸음 */
  disabled?: boolean
  /**
   * **되돌릴 수 없는 걸음**은 빨강으로 선다 (회원 탈퇴). 자리는 그대로 오른쪽 끝이다 —
   * 자리는 고정이고 무게는 색이 진다.
   */
  tone?: 'danger'
  /** 누른 뒤 답을 기다리는 중 — 잠기고 글 앞에 도는 고리가 선다 */
  busy?: boolean
}

/**
 * 걸음 하나를 버튼으로. **크기는 md(44) 고정이다** — 창의 폭이나 갈래와 무관하게 하나다
 * (2026-09-07 사용자 지시 · design.md §3 컨트롤 사다리).
 *
 * ★ `close` 인 걸음은 **창을 우리가 직접 닫는다.** 킷 `Modal.Close` 를 쓰지 않는다
 * (krds-react 1.1.1 실측 2026-09-02) — 열림을 밖에서 쥔 창(`open` prop 을 넘긴 controlled 창)
 * 에서는 그 버튼이 `onOpenChange` 를 부르지 않아 **창이 닫히지 않는다.** 우리 창은 전부
 * controlled 라 `close: true` 걸음이 전건 먹통이었다. 열림을 쥔 쪽이 우리이므로 닫는 것도
 * 우리가 한다.
 */
export function StepButton({
  step,
  variant,
  onClose,
}: {
  step: Step
  variant?: 'secondary'
  onClose: () => void
}) {
  const className =
    [step.tone === 'danger' && 'klid-btn-danger', step.busy && 'klid-btn-busy'].filter(Boolean).join(' ') ||
    undefined
  if (step.href) {
    return (
      <Button as="a" href={step.href} size="medium" variant={variant} className={className}>
        {step.label}
      </Button>
    )
  }
  return (
    <Button
      size="medium"
      variant={variant}
      className={className}
      disabled={step.disabled || step.busy}
      aria-busy={step.busy || undefined}
      onClick={() => {
        step.onClick?.()
        if (step.close) onClose()
      }}
    >
      {step.label}
    </Button>
  )
}
