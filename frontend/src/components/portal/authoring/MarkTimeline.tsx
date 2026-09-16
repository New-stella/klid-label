import type { CSSProperties } from 'react'
import { cx } from '../kit/util'
import './MarkTimeline.css'

/* 저작도구 화면에서만 쓰는 조각이라 스토리북 부품 목록에 두지 않고 화면 곁에 둔다 (2026-09-14 사용자 지시) */

/**
 * 마킹 지점 줄 — 영상 한 편의 길이를 가로 막대 하나로 두고, **찍어 둔 지점마다 세로 눈금**을 세운다.
 * 저작도구의 업로드 영상 마킹이 「어디서 프레임을 뽑을지」를 영상 위에서 한눈에 보이는 자리다.
 *
 *   <MarkTimeline marks={[0, 300, 600]} total={6870} onSeek={seek}
 *     markLabel={(f) => `F${f} (${frameToTime(f)})`} />
 *
 * - 눈금 자리는 **지점 ÷ 전체 길이**다. 단위(프레임 · 초)는 화면이 정하고 둘을 같은 단위로 넘긴다.
 * - 머리 줄은 이름(왼쪽) · 건수(오른쪽)다. 막대만 두면 눈금이 몇 개인지 세어 봐야 알 수 있다.
 * - `onSeek` 을 주면 눈금이 **누르는 자리**가 된다 (그 지점으로 재생 위치를 옮긴다).
 *   보이는 선은 2 지만 누르는 폭은 16 이다 — 선 굵기로 겨누게 하면 못 누른다.
 *   안 주면 눈금은 그림일 뿐이라 보조기술에서 숨기고, 건수 한 줄이 그 내용을 말한다.
 * - 재생 위치 **손잡이**는 이 줄이 갖지 않는다 — 바로 아래 재생 줄(PlaybackBar)의 막대가 이미 말한다.
 *   한 화면에 손잡이가 둘이면 어느 쪽을 끌어야 하는지 갈린다.
 *   대신 `position` 을 주면 **누를 수 없는 재생 머리 선**이 선다 — 지금 위치가 어느 눈금 근처인지를
 *   같은 가로 자 위에서 읽게 한다 (저작도구 원본 마킹 화면).
 * - `selected` 를 주면 그 눈금이 굵고 짙어진다 — 옆 목록에서 고른 지점과 같은 지점이다.
 */
export function MarkTimeline({
  marks,
  total,
  label = '마킹 지점',
  markLabel = String,
  onSeek,
  position,
  selected,
  className,
}: {
  /** 찍어 둔 지점들 (`total` 과 같은 단위) */
  marks: readonly number[]
  /** 전체 길이 (마지막 지점이 아니라 영상 끝) */
  total: number
  /** 머리 줄 이름 */
  label?: string
  /** 눈금 하나를 부르는 말 (보조기술 · 얹었을 때 뜨는 말) */
  markLabel?: (mark: number) => string
  /** 눈금을 누르면 그 지점으로 옮긴다. 없으면 눈금은 그림으로만 선다 */
  onSeek?: (mark: number) => void
  /** 재생 머리 선 자리 (`total` 과 같은 단위). 없으면 선이 서지 않는다 */
  position?: number
  /** 고른 눈금 */
  selected?: number | null
  className?: string
}) {
  const at = (m: number) => ({ '--at': `${total > 0 ? (m / total) * 100 : 0}%` }) as CSSProperties
  return (
    <div className={cx('klid-mark-timeline', className)}>
      <div className="klid-mark-timeline-head">
        <span className="klid-mark-timeline-label">{label}</span>
        <span className="klid-mark-timeline-count">{marks.length}건</span>
      </div>
      <div
        className="klid-mark-timeline-track"
        role={onSeek ? 'group' : undefined}
        aria-label={onSeek ? label : undefined}
      >
        {/* 눈금이 서는 판 — 막대 안쪽으로 들여 둔다. 0 과 끝 지점의 눈금이 막대 테두리에 걸쳐
            반쪽만 보이지 않게 한다 */}
        <div className="klid-mark-timeline-rail">
          {marks.map((m) =>
            onSeek ? (
              <button
                key={m}
                type="button"
                className="klid-mark-timeline-tick"
                style={at(m)}
                data-selected={selected === m || undefined}
                aria-pressed={selected === m}
                aria-label={`${markLabel(m)} 지점으로 이동`}
                title={markLabel(m)}
                onClick={() => onSeek(m)}
              />
            ) : (
              <span
                key={m}
                className="klid-mark-timeline-tick"
                style={at(m)}
                data-selected={selected === m || undefined}
                aria-hidden
              />
            ),
          )}
          {position !== undefined && (
            <span className="klid-mark-timeline-playhead" style={at(position)} aria-hidden />
          )}
        </div>
      </div>
    </div>
  )
}
