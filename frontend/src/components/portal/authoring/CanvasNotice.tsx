import type { ReactNode } from 'react'
import { Alert, type AlertTone } from '../kit/Alert'
import { cx } from '../kit/util'
import './CanvasNotice.css'

/* 저작도구 화면에서만 쓰는 조각이라 스토리북 부품 목록에 두지 않고 화면 곁에 둔다 (2026-09-14 사용자 지시) */

type Placement = 'top' | 'top-end' | 'bottom'

type Props =
  /** 그림을 받는 중 — 칸을 옅게 덮고 가운데 큰 고리. 보이는 글은 없다 (보조기술에만 `label`) */
  | { kind: 'loading'; label: string }
  /** 지금 보기 모드를 알리는 어두운 띠 (「회전 보기 90°」) */
  | { kind: 'tag'; placement: Placement; children: ReactNode }
  /** 칸 위에 떠 있는 안내 띠 — 공용 안내 띠(Alert)를 그대로 얹는다 */
  | { kind: 'alert'; placement: Placement; tone: AlertTone; title?: ReactNode; children?: ReactNode }

/**
 * 캔버스 안내 — 편집 칸(캔버스) **위에 겹쳐** 지금 무슨 일인지 말한다. 라벨링 편집기가 쓴다.
 *
 *   <CanvasNotice kind="loading" label="프레임 이미지를 불러오는 중" />
 *   <CanvasNotice kind="tag" placement="top-end">회전 보기 90° · 편집이 잠깁니다</CanvasNotice>
 *
 * - 칸 전체를 덮는 하나(loading)와, 칸 가장자리에 떠 있는 둘(tag · alert)이 있다.
 *   저장 중은 여기가 아니라 포털 작은 창(`Dialog`)이 맡는다 (2026-09-14 사용자 지시).
 * - 덮는 하나는 칸 안 조작을 막는다 — 그 사이 누른 것이 반영되지 않는다는 것을 모양으로 말한다.
 *   떠 있는 둘은 조작을 막지 않는다 (띠 밖을 그대로 누를 수 있다).
 * - 글은 화면이 꽂는다. 이 부품은 캔버스 칸(`position: relative`) 안에 둔다.
 */
export function CanvasNotice(props: Props & { className?: string }) {
  if (props.kind === 'loading') {
    return (
      <div className={cx('klid-canvas-notice', props.className)} data-kind="loading" role="status" aria-busy="true">
        <span className="ring" aria-hidden />
        <span className="sr-only">{props.label}</span>
      </div>
    )
  }
  if (props.kind === 'tag') {
    return (
      <div className={cx('klid-canvas-notice', props.className)} data-kind="tag" data-placement={props.placement}>
        <span className="tag" role="status">
          {props.children}
        </span>
      </div>
    )
  }
  return (
    <div className={cx('klid-canvas-notice', props.className)} data-kind="alert" data-placement={props.placement}>
      <Alert tone={props.tone} title={props.title}>
        {props.children}
      </Alert>
    </div>
  )
}
