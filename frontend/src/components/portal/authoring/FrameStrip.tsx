import { cx } from '../kit/util'
import './FrameStrip.css'

/* 저작도구 화면에서만 쓰는 조각이라 스토리북 부품 목록에 두지 않고 화면 곁에 둔다 (2026-09-14 사용자 지시) */

export interface FrameStripItem {
  /** 그림 경로 */
  src: string
  /** 낱장 아래 붙는 짧은 이름 (프레임 번호 등) */
  label: string
  /** 보조기술이 읽을 이름. 없으면 `label` 을 읽는다 */
  name?: string
}

/**
 * 프레임 줄 — 영상에서 뽑은 낱장을 **가로 한 줄로 늘어놓고 지금 장을 고른다.**
 * 라벨링 편집기의 캔버스 아래 줄이 쓴다.
 *
 *   <FrameStrip items={frames} current={index} onSelect={setIndex} label="프레임 목록" />
 *
 * - 낱장은 16:9 · 폭 76 으로 고정한다. 장수가 늘면 줄이 옆으로 밀린다 — 폭을 줄여 다 넣으면
 *   23장만 되어도 그림을 알아볼 수 없다.
 * - 고른 장은 **코발트 테 2 + 번호 굵게**다. 테만 두면 색으로만 갈리므로 번호도 함께 바뀐다 (WCAG 1.4.1).
 * - 막대는 늘 보이는 포털 스크롤바(`klid-scrollbar`)다 — 낱장이 전부 누를 수 있는 버튼이라
 *   Tab 으로도 닿지만, 몇 장이 더 있는지는 막대가 말해 준다.
 */
export function FrameStrip({
  items,
  current,
  onSelect,
  label,
  className,
}: {
  items: readonly FrameStripItem[]
  /** 지금 고른 장의 자리 번호 */
  current: number
  onSelect?: (index: number) => void
  /** 줄 전체의 이름 (보조기술용) */
  label: string
  className?: string
}) {
  return (
    <ol className={cx('klid-frame-strip', 'klid-scrollbar', className)} aria-label={label}>
      {items.map((item, i) => (
        <li key={`${item.label}-${i}`}>
          <button
            type="button"
            className="klid-frame-strip-item"
            aria-current={i === current ? 'true' : undefined}
            aria-label={item.name ?? item.label}
            onClick={() => onSelect?.(i)}
          >
            {/* 이름은 버튼이 말한다 — 그림은 비워 같은 말을 두 번 읽지 않게 한다 */}
            <img className="thumb" src={item.src} alt="" loading="lazy" />
            <span className="index" aria-hidden>
              {item.label}
            </span>
          </button>
        </li>
      ))}
    </ol>
  )
}
