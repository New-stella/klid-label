import type { CSSProperties } from 'react'
import { cx } from '../kit/util'
import './AnnotatedFrame.css'

/* 저작도구 화면에서만 쓰는 조각이라 스토리북 부품 목록에 두지 않고 화면 곁에 둔다 (2026-09-14 사용자 지시) */

/** 캔버스 좌표 — 그림 폭 · 높이를 각각 0~100 으로 본 값 */
export type FramePoint = readonly [number, number]

export interface FrameShape {
  id: string
  /** bbox 는 두 점(왼쪽 위 · 오른쪽 아래), polygon 은 꼭짓점 차례 */
  kind: 'bbox' | 'polygon'
  points: readonly FramePoint[]
  /** 라벨 색 — CSS 색 값(토큰) */
  color: string
  /** 도형 위 이름표 (「사람 #1」) */
  tag?: string
  selected?: boolean
  hidden?: boolean
}

/**
 * 라벨 캔버스 — 프레임 한 장 위에 **객체(사각형 · 다각형)**를 겹쳐 그린다. 라벨링 편집기의 가운데 칸이 쓴다.
 *
 *   <AnnotatedFrame src={…} alt="…" shapes={shapes} grid />
 *
 * - 그림은 칸 안에 **비율을 지켜 가운데** 선다. 도형 좌표는 그림 폭 · 높이의 백분율이라 칸 크기가 바뀌어도
 *   그림과 함께 따라간다.
 * - 도형은 라벨 색 테 + 같은 색 옅은 면이다. 골라진 도형은 테가 굵어지고 모서리 · 꼭짓점에 손잡이가 선다.
 *   이름표는 도형 왼쪽 위에 라벨 색 면으로 붙는다.
 * - `grid` 는 격자, `marquee` 는 영역 확대 모드에서 끄는 동안의 점선 사각형이다.
 * - `rotation` 은 보기만 돌린다 (라벨 좌표는 그대로).
 * - 그림 밝기 · 대비 · 작업 투명도는 `imageStyle`, 라벨 투명도는 `shapeOpacity` 로 준다.
 * - 선 · 면 · 손잡이는 그림이라 보조기술에서 숨긴다 — 객체 목록이 같은 내용을 글로 갖는다.
 */
export function AnnotatedFrame({
  src,
  alt,
  shapes = [],
  grid,
  marquee,
  rotation = 0,
  imageStyle,
  shapeOpacity = 1,
  className,
}: {
  src: string
  alt: string
  shapes?: readonly FrameShape[]
  grid?: boolean
  /** 영역 확대 점선 — 두 점 */
  marquee?: readonly [FramePoint, FramePoint]
  /** 0 · 90 · 180 · 270 */
  rotation?: number
  imageStyle?: CSSProperties
  shapeOpacity?: number
  className?: string
}) {
  const visible = shapes.filter((s) => !s.hidden)
  return (
    <div className={cx('klid-annotated-frame', className)}>
      <div
        className="klid-annotated-frame-box"
        data-sideways={rotation % 180 !== 0 || undefined}
        style={{ transform: rotation ? `rotate(${rotation}deg)` : undefined }}
      >
        <img src={src} alt={alt} style={imageStyle} />
        {/* 좌표계는 0~100 × 0~100 — 그림 비율대로 늘려 쓴다. 선 굵기는 늘어나지 않게 화면 픽셀로 고정한다 */}
        <svg
          className="klid-annotated-frame-layer"
          viewBox="0 0 100 100"
          preserveAspectRatio="none"
          aria-hidden
          style={{ opacity: shapeOpacity }}
        >
          {grid && (
            <g className="grid">
              {Array.from({ length: 9 }, (_, i) => (i + 1) * 10).map((v) => (
                <g key={v}>
                  <line x1={v} y1={0} x2={v} y2={100} />
                  <line x1={0} y1={v} x2={100} y2={v} />
                </g>
              ))}
            </g>
          )}
          {visible.map((s) => {
            const style = { '--shape-color': s.color } as CSSProperties
            if (s.kind === 'bbox') {
              const [[x1, y1], [x2, y2]] = s.points
              return (
                <rect
                  key={s.id}
                  className="shape"
                  data-selected={s.selected || undefined}
                  style={style}
                  x={Math.min(x1, x2)}
                  y={Math.min(y1, y2)}
                  width={Math.abs(x2 - x1)}
                  height={Math.abs(y2 - y1)}
                />
              )
            }
            return (
              <polygon
                key={s.id}
                className="shape"
                data-selected={s.selected || undefined}
                style={style}
                points={s.points.map((p) => p.join(',')).join(' ')}
              />
            )
          })}
          {marquee && (
            <rect
              className="marquee"
              x={Math.min(marquee[0][0], marquee[1][0])}
              y={Math.min(marquee[0][1], marquee[1][1])}
              width={Math.abs(marquee[1][0] - marquee[0][0])}
              height={Math.abs(marquee[1][1] - marquee[0][1])}
            />
          )}
        </svg>
        {/* 이름표 · 손잡이는 늘어나면 안 되는 모양이라 그림 위 HTML 로 얹는다 (자리만 백분율) */}
        <div className="klid-annotated-frame-marks" aria-hidden style={{ opacity: shapeOpacity }}>
          {visible.map((s) => {
            const xs = s.points.map((p) => p[0])
            const ys = s.points.map((p) => p[1])
            const handles: FramePoint[] =
              s.kind === 'bbox'
                ? [
                    [Math.min(...xs), Math.min(...ys)],
                    [Math.max(...xs), Math.min(...ys)],
                    [Math.min(...xs), Math.max(...ys)],
                    [Math.max(...xs), Math.max(...ys)],
                  ]
                : [...s.points]
            const color = { '--shape-color': s.color } as CSSProperties
            return (
              <div key={s.id}>
                {s.tag && (
                  <span
                    className="tag"
                    style={{ ...color, left: `${Math.min(...xs)}%`, top: `${Math.min(...ys)}%` }}
                  >
                    {s.tag}
                  </span>
                )}
                {s.selected &&
                  handles.map(([x, y], i) => (
                    <span key={i} className="handle" style={{ ...color, left: `${x}%`, top: `${y}%` }} />
                  ))}
              </div>
            )
          })}
        </div>
      </div>
    </div>
  )
}
