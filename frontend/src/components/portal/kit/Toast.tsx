import { useEffect, type ReactNode } from 'react'
import { createPortal } from 'react-dom'
import { Alert } from './Alert'
import './Toast.css'

export type ToastTone = 'success' | 'info' | 'warning' | 'danger'

export interface ToastItem {
  id: string | number
  tone: ToastTone
  /** 알림 글. 한두 문장 */
  message: ReactNode
  /** 굵은 첫 줄 — 대개 없이 글만 선다 */
  title?: string
  /** 이 알림만 머무는 시간을 따로 줄 때(ms). `null` 이면 머문다 — 화면 견본의 처음부터 떠 있는 알림 */
  duration?: number | null
}

/** 저절로 사라지기까지 기본 시간 — 한두 문장을 두 번 읽을 틈 */
const DEFAULT_DURATION = 5000

/**
 * 토스트 알림 — 조작이 끝난 **바로 그 순간의 결과**를 화면 아래 가운데 잠깐 띄웠다가 치운다.
 * 「저장했습니다」 · 「1건 이상 찍어 주세요」 처럼 **화면 상태로 남지 않는 한마디**에 쓴다
 * (저작도구 업로드 영상 마킹 2026-09-14 도입).
 *
 *   <Toaster items={toasts} onDismiss={dismiss} />
 *
 * 화면 안 안내 띠(`Alert`)와 가르는 기준 — 그 사정이 **화면에 계속 남아 있으면 띠**, 누른 순간 한 번만
 * 알리고 끝나면 토스트다. 잠김 · 빈 목록 · 일부만 쓰임처럼 화면이 그 상태로 머무는 것은 띠로 둔다.
 *
 * - **생김새는 안내 띠를 빌리되, 면 · 선은 성격과 상관없이 알림(회색) 한 벌이다.** 성격(tone)은
 *   **표식 글리프와 색으로만** 말한다 (2026-09-14) — 떠 있는 한마디라 면까지 물들면 화면 위에서 튄다.
 *   토스트가 더하는 것은 떠 있는 층(그림자)과 자리다.
 * - 자리는 **화면 아래 가운데**다. 위는 포털 머리 막대와 탭이 차지하고, 누른 걸음(저장 · 완료)은 대개
 *   아래쪽에 있어 눈이 가까운 쪽에 뜬다. 여러 개면 위로 쌓인다 (새 것이 맨 아래).
 * - 닫기(×)를 두지 않는다 — 저절로 사라지는 한마디라 치울 일이 없다 (안내 띠와 같은 규칙).
 * - `duration` 이 지나면 `onDismiss` 를 부른다. `null` 이면 머문다 — 화면 견본에서 모습을 볼 때만 쓴다.
 * - 창(`Modal` · `Dialog`)보다 위 층이다 — 창을 닫는 순간 뜨는 결과가 딤 뒤로 숨지 않는다.
 */
export function Toaster({
  items,
  onDismiss,
  duration = DEFAULT_DURATION,
}: {
  items: readonly ToastItem[]
  onDismiss?: (id: ToastItem['id']) => void
  /** 저절로 사라지기까지(ms). `null` 이면 머문다 */
  duration?: number | null
}) {
  if (items.length === 0 || typeof document === 'undefined') return null
  /* body 로 띄운다 — 헤더처럼 층을 새로 여는 조상 안에 두면 고정 자리가 그 조상에 갇힌다 */
  return createPortal(
    <div className="klid-toaster">
      {items.map((t) => (
        <ToastCard
          key={t.id}
          item={t}
          duration={t.duration !== undefined ? t.duration : duration}
          onDismiss={onDismiss}
        />
      ))}
    </div>,
    document.body,
  )
}

function ToastCard({
  item,
  duration,
  onDismiss,
}: {
  item: ToastItem
  duration: number | null
  onDismiss?: (id: ToastItem['id']) => void
}) {
  useEffect(() => {
    if (duration === null || !onDismiss) return
    const timer = window.setTimeout(() => onDismiss(item.id), duration)
    return () => window.clearTimeout(timer)
  }, [duration, onDismiss, item.id])

  return (
    <div className="klid-toast">
      <Alert tone={item.tone} title={item.title}>
        {item.message}
      </Alert>
    </div>
  )
}
