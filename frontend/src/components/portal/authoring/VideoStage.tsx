import type { ReactNode } from 'react'
import { LoaderCircle, TriangleAlert } from 'lucide-react'
import { EmptyState } from '../kit/EmptyState'
import { cx } from '../kit/util'
import './VideoStage.css'

/* 저작도구 화면에서만 쓰는 조각이라 스토리북 부품 목록에 두지 않고 화면 곁에 둔다 (2026-09-14 사용자 지시) */

export type VideoStageStatus = 'ready' | 'loading' | 'buffering' | 'unavailable'

/**
 * 영상 자리 — 16:9 판에 영상(데모에서는 정지 화면)을 앉히고, 영상이 아직 없거나 끊겼을 때
 * **무엇을 기다리는지 그 자리에서** 말한다. 저작도구 업로드 영상 마킹의 재생 무대가 쓴다.
 *
 *   <VideoStage status="buffering" message="불러오는 중" media={<img … />} />
 *
 * 영상이 아닐 때는 빈 화면 안내(`EmptyState` sm)가 판을 채운다 — 아이콘 위, 글 아래.
 * 기다리는 두 상태는 도는 고리, 재생할 수 없음은 경고 삼각형(느낌표)이다.
 *
 * - `ready` — 영상만 선다.
 * - `loading` — 영상 대신 안내 판. 고리가 돈다. 영상이 오기 전에도 판은 같은 크기를 지킨다
 *   (비워 두면 아래 조작부가 위로 튀었다가 영상이 오면 다시 밀린다).
 * - `buffering` — 멈춘 장면을 흐리게 덮고 그 위에 같은 안내를 띄운다. 고리가 돈다.
 *   장면이 비쳐야 어디서 끊겼는지 안다.
 * - `unavailable` — `loading` 과 같은 판. 고리 대신 경고 삼각형 — 더 기다려도 오지 않는다.
 *
 * 글은 화면이 꽂는다 — 상태마다 부품을 따로 두지 않는다.
 */
export function VideoStage({
  status = 'ready',
  media,
  message,
  className,
}: {
  status?: VideoStageStatus
  /** 영상 (또는 정지 화면). `ready` · `buffering` 에서만 그린다 */
  media?: ReactNode
  /** `ready` 가 아닐 때 안내 판에 서는 글 */
  message?: string
  className?: string
}) {
  const showMedia = status === 'ready' || status === 'buffering'
  const waiting = status === 'loading' || status === 'buffering'
  return (
    <div className={cx('klid-video-stage', className)} data-status={status} aria-busy={waiting || undefined}>
      {showMedia && media}
      {status !== 'ready' && message && (
        <div className="klid-video-stage-message" role="status">
          <EmptyState size="sm" icon={status === 'unavailable' ? TriangleAlert : LoaderCircle} title={message} />
        </div>
      )}
    </div>
  )
}
