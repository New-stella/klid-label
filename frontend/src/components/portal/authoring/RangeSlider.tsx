import type { CSSProperties } from 'react'
import { cx } from '../kit/util'
import './RangeSlider.css'

/* 저작도구 화면에서만 쓰는 조각이라 스토리북 부품 목록에 두지 않고 화면 곁에 둔다 (2026-09-14 사용자 지시) */

/**
 * 연속 값 막대 — 한 줄 막대 위 손잡이를 끌어 값을 고른다.
 * 영상 재생 위치 · 이미지 밝기·대비·투명도처럼 **눈금 이름이 없는 연속 값**을 쓴다.
 * (눈금마다 이름이 서는 단계 막대는 생성형 AI 심각도가 제 모양을 갖는다)
 *
 *   <RangeSlider aria-label="재생 위치" min={0} max={229} value={t} onChange={setT} />
 *   <RangeSlider label="밝기" valueText="0" min={-100} max={100} value={b} onChange={setB} />
 *
 * - 모습은 생성형 AI 심각도 막대와 같다 — 막대 4 · 손잡이 20 · 지나온 길 primary-50 · 남은 길 gray-20.
 *   같은 「끌어서 고르는 막대」가 화면마다 다른 굵기로 서지 않게 한다.
 * - **누르는 자리는 44** 다. 보이는 막대는 4 지만 세로로 터치 하한을 넘어야 한다.
 * - `label` 을 주면 막대 위에 이름(왼쪽) · 값(오른쪽) 줄이 선다. 안 주면 막대만 서므로
 *   `aria-label` 을 반드시 준다.
 */
export function RangeSlider({
  min = 0,
  max = 100,
  step = 1,
  value,
  onChange,
  label,
  valueText,
  disabled,
  className,
  'aria-label': ariaLabel,
}: {
  min?: number
  max?: number
  step?: number
  value: number
  onChange?: (next: number) => void
  /** 막대 위 이름. 주면 이름 · 값 줄이 선다 */
  label?: string
  /** 이름 줄 오른쪽에 보이는 값. 보조기술도 이 글로 읽는다 */
  valueText?: string
  disabled?: boolean
  className?: string
  'aria-label'?: string
}) {
  const at = max === min ? 0 : ((value - min) / (max - min)) * 100
  const input = (
    <input
      type="range"
      min={min}
      max={max}
      step={step}
      value={value}
      disabled={disabled}
      aria-label={label ? undefined : ariaLabel}
      aria-valuetext={valueText}
      onChange={(e) => onChange?.(Number(e.currentTarget.value))}
    />
  )
  return (
    /* 채워진 길이를 변수로 건넨다 — 막대는 하나인데 지나온 길과 남은 길의 색이 달라야 지금
       어디까지 왔는지가 보인다. `input` 하나로 그리려면 배경 그라데이션의 경계를 옮기는 수밖에 없다 */
    <div
      className={cx('klid-range', className)}
      style={{ '--at': `${at}%` } as CSSProperties}
    >
      {label ? (
        <label className="klid-range-field">
          <span className="klid-range-head">
            <span className="klid-range-label">{label}</span>
            {valueText !== undefined && <span className="klid-range-value">{valueText}</span>}
          </span>
          {input}
        </label>
      ) : (
        input
      )}
    </div>
  )
}
