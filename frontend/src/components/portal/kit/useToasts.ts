import { useCallback, useState } from 'react'
import type { ToastItem } from './Toast'

/**
 * 토스트 목록 — 화면이 `push` 로 띄우고 `Toaster` 가 `dismiss` 로 치운다.
 * 처음부터 떠 있어야 하는 견본(화면 스토리의 상태)은 `initial` 로 넘긴다.
 */
export function useToasts(initial: readonly Omit<ToastItem, 'id'>[] = []) {
  const [items, setItems] = useState<ToastItem[]>(() => initial.map((t, i) => ({ ...t, id: `init-${i}` })))
  const push = useCallback((t: Omit<ToastItem, 'id'>) => {
    setItems((xs) => [...xs, { ...t, id: `${Date.now()}-${Math.random()}` }])
  }, [])
  const dismiss = useCallback((id: ToastItem['id']) => {
    setItems((xs) => xs.filter((x) => x.id !== id))
  }, [])
  return { items, push, dismiss }
}
