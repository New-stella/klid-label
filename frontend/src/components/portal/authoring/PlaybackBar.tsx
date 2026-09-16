import type { ReactNode } from 'react'
import { Pause, Play } from 'lucide-react'
import { IconButton } from '../kit/IconButton'
import { RangeSlider } from './RangeSlider'
import { cx } from '../kit/util'
import './PlaybackBar.css'

/* 저작도구 화면에서만 쓰는 조각이라 스토리북 부품 목록에 두지 않고 화면 곁에 둔다 (2026-09-14 사용자 지시) */

/**
 * 재생 줄 — **재생·멈춤 · 위치 막대**를 한 줄에 세우고, 막대 앞뒤에 그 화면의 말(시간 · 배속 등)을 꽂는다.
 * 저작도구의 영상 마킹(시간 · 배속)과 라벨링 편집기(프레임 번호)가 같은 줄을 쓴다.
 *
 *   <PlaybackBar playing={p} onToggle={toggle} position={t} max={229} onSeek={seek}
 *     lead="00:00 / 03:49" trail={<배속 칩 />} />
 *
 * - 막대는 남는 폭을 다 먹는다 — 앞뒤 말은 제 글자만큼만 쓴다.
 * - 앞뒤에 꽂는 글자는 숫자 폭을 고정한다(tabular) — 재생 중 숫자가 바뀔 때 막대가 흔들리지 않게.
 */
export function PlaybackBar({
  playing = false,
  onToggle,
  position,
  max,
  step = 1,
  onSeek,
  lead,
  trail,
  seekLabel = '재생 위치',
  seekValueText,
  className,
}: {
  playing?: boolean
  onToggle?: () => void
  /** 지금 위치 (초 · 프레임 등 화면이 정한 단위) */
  position: number
  max: number
  step?: number
  onSeek?: (next: number) => void
  /** 막대 앞 — 재생 버튼 바로 뒤 (지금 시간 등) */
  lead?: ReactNode
  /** 막대 뒤 — 줄 끝 (배속 · 프레임 번호 등) */
  trail?: ReactNode
  /** 막대의 이름 (보조기술용) */
  seekLabel?: string
  /** 막대 값을 읽어 줄 글 (예: 「1분 20초」) */
  seekValueText?: string
  className?: string
}) {
  return (
    <div className={cx('klid-playback', className)}>
      <IconButton size="lg" tone="primary" aria-label={playing ? '멈춤' : '재생'} onClick={onToggle}>
        {playing ? <Pause aria-hidden /> : <Play aria-hidden />}
      </IconButton>
      {lead && <div className="klid-playback-lead">{lead}</div>}
      <RangeSlider
        className="klid-playback-seek"
        aria-label={seekLabel}
        valueText={seekValueText}
        min={0}
        max={max}
        step={step}
        value={position}
        onChange={onSeek}
      />
      {trail && <div className="klid-playback-trail">{trail}</div>}
    </div>
  )
}
