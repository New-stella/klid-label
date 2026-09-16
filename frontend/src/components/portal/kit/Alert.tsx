import type { ReactNode } from 'react'
import { CircleCheck, CircleX, Info, TriangleAlert } from 'lucide-react'
import { cx, glued } from './util'
import './Alert.css'

export type AlertTone = 'info' | 'primary' | 'success' | 'warning' | 'danger'

/** tone 이 정하는 표식. 화면이 고르지 않는다 — 같은 성격의 띠는 어느 화면에서나 같은 글리프다 */
const TONE_MARK = {
  info: Info,
  primary: Info,
  success: CircleCheck,
  warning: TriangleAlert,
  danger: CircleX,
} as const

/**
 * 인라인 알림 — 폼 결과·주의·안내를 그 자리에서 알리는 띠.
 *
 * 생김새는 **왼쪽 표식 + 타이틀 + 서브텍스트** 한 꼴이다 (2026-08-25 확정).
 * tone 은 **면 5 · 선 20 · 표식 50** 세 단으로 얹는다 (design.md §8).
 *
 * tone 은 **무슨 일인가**로 고른다: danger 실패·warning 주의·success 완료·info 곁들이는 설명 ·
 * **primary 지금 이 화면의 사정**(2026-08-19 추가). info(회색)와 primary(코발트)는 둘 다
 * "나쁜 일이 아니다" 지만 무게가 다르다 — 회색은 배경으로 물러나는 설명이고, 코발트는 지금
 * 화면이 왜 이런 상태인지를 말해 사용자가 읽고 넘어가야 하는 띠다.
 * KRDS 에 대응 컴포넌트가 없다 (킷 CriticalAlert 는 페이지 전체를 덮는 다른 물건).
 * 로그인 실패·가입 완료·탈퇴 경고·만료 공지·문의 답변 등 15곳이 쓴다.
 *
 * **★ 문구가 다르다고 띠를 새로 만들지 않는다.** 화면은 이 띠에 글만 꽂는다 —
 * `title`(굵은 첫 줄, 없으면 안 선다) · children(서브텍스트). 굵기·색·표식·간격은 띠가 갖는다.
 * 종전에는 화면마다 `<b>`+`.tail` / `<strong>`+`<span>` / `<h4>`+`<p>` 로 갈라 그렸고,
 * 세 곳의 굵기·줄간이 서로 달랐다.
 *
 * 표식은 tone 에서 자동으로 나온다. 그 자리에 다른 글리프가 서야 하는 자리만 `icon` 으로 바꾼다
 * (생성형 AI 대기 줄의 모래시계 — "실패가 아니라 기다리는 중" 을 성격 글리프가 못 말한다).
 *
 * 보조기술 알림(live)은 tone 에서 자동으로 정한다 — 직접 role 을 적다 빠뜨리는 일을 막는다.
 *   danger·warning → role="alert"  (즉시 읽어줌)
 *   success·info   → role="status" (하던 말 끝나고 읽어줌)
 * 동작 결과가 아니라 늘 떠 있는 안내문이면 live="none" 으로 끈다 (불필요한 낭독 방지).
 */
export function Alert({
  tone = 'info',
  live,
  icon,
  title,
  children,
  className,
}: {
  tone?: AlertTone
  /** 기본은 tone 에서 파생. 정적 안내문은 'none' */
  live?: 'alert' | 'status' | 'none'
  /** tone 이 정한 표식 대신 세울 글리프 */
  icon?: ReactNode
  /** 굵은 첫 줄. 없으면 서브텍스트만 선다 */
  title?: ReactNode
  /** 서브텍스트. 제목 한 줄로 끝나는 띠(「삭제에 실패했습니다.」)는 비운다 */
  children?: ReactNode
  className?: string
}) {
  const resolved = live ?? (tone === 'danger' || tone === 'warning' ? 'alert' : 'status')
  const Mark = TONE_MARK[tone]
  return (
    <div
      className={cx('klid-alert', className)}
      data-tone={tone}
      role={resolved === 'none' ? undefined : resolved}
    >
      <span className="klid-alert-mark" aria-hidden>
        {icon ?? <Mark />}
      </span>
      <span className="klid-alert-body">
        {title ? <b className="klid-alert-title">{glued(title)}</b> : null}
        {glued(children)}
      </span>
    </div>
  )
}

/**
 * 서브텍스트 한 줄 — 문장마다 낱줄로 세울 때 문장 하나씩 감싼다 (2026-09-11).
 *
 *   <Alert title="…">
 *     <Alert.Line>첫 문장입니다.</Alert.Line>
 *     <Alert.Line>둘째 문장입니다.</Alert.Line>
 *   </Alert>
 *
 * 손 줄바꿈(`<br>`)이 아니라 **문장 단위 조각**이다 — 문장 끝에서만 줄이 갈리고 문장 안은
 * 브라우저가 접으므로, 폭이 바뀌어도 끊은 자리가 어긋나지 않는다 (UX-writing.md §6).
 */
function AlertLine({ children }: { children: ReactNode }) {
  return <span className="klid-alert-line">{children}</span>
}
Alert.Line = AlertLine
