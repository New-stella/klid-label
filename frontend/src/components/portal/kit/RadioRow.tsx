import type { ComponentProps, ReactNode } from 'react'
import { Radio, RadioGroup } from 'krds-react'
import './RadioRow.css'

/** 킷 1.1.1 타입에 children 이 빠져 있다. 런타임은 children 을 라벨로 렌더한다 */
const RadioItem = Radio as unknown as (
  p: ComponentProps<typeof Radio> & { children?: ReactNode },
) => ReactNode

/**
 * 단일선택 조건 한 줄 — 선택지를 접지 않고 **바닥에 펴서** 라디오로 받는다.
 *
 * 언제 이걸 쓰나 (design.md §4 "모양이 곧 뜻이다" 의 세 번째 갈래):
 *   · 채움 세그먼트(ChipGroup single) — 갈래가 둘·셋이고 **서로 대등**할 때. 붙어 있어 하나만
 *     켜진다고 말한다.
 *   · 라디오 줄(이것) — 선택지에 `전체`·`선택 안 함` 처럼 **조건을 안 거는 상태가 섞여** 있거나,
 *     갈래가 넷 이상이라 세그먼트로 붙이면 한 덩어리가 화면 폭을 가로지를 때.
 *     세그먼트는 갈래가 동등하다고 말하는 모양이라 `전체` 가 나머지와 같은 값처럼 읽히는데,
 *     라디오는 목록 중 하나를 고르는 모양이라 그 자리가 기본값으로 자연스럽게 앉는다.
 *   · 드롭다운 — 값이 열 개를 넘거나(연도·지역) 값 자체보다 고른 결과가 중요할 때.
 *
 * 소제목을 달지 않는 자리라 묶음에 이름을 준다 — 폼 라벨은 span 이어서 라디오 묶음과
 * 이어지지 않는다 (design.md §2).
 *
 * 치수(글자 14 · 원 18 · 점 8)는 FilterPanel 이 패널 안에서 준다 — 킷 medium 기본은 글자 15 ·
 * 원 20 이라 같은 패널의 라벨·칩·입력(14)보다 한 급 커서 라디오 줄만 혼자 크게 읽힌다.
 * 패널 밖에서 쓰면 킷 기본값이 나온다.
 *
 * 못 고르는 낱개는 `disabled` 로 표시한다 — 아직 열리지 않은 선택지를 **지우지 않고** 남길 때
 * 쓴다. 지우면 앞으로 생길 기능이 있다는 사실까지 사라지므로, 사정을 라벨에 함께 적고 잠근다
 * (생성형 AI 의 `이미지 기반 영상 (준비 중)`). KWCAG 는 비활성 요소를 대비 예외로 둔다.
 *
 * 쓰는 곳: 학습데이터 검색(데이터 유형 · 증강 포함 여부) · 생성형 AI(재난 유형 · 생성 방식).
 */
export function RadioRow<T extends string>({
  label,
  name,
  value,
  onChange,
  options,
}: {
  /** 보조기술이 읽을 묶음 이름 */
  label: string
  /** 라디오 묶음 이름 (name 속성). 화면 안에서 겹치면 두 묶음이 한 묶음이 된다 */
  name: string
  value: T
  onChange: (next: T) => void
  options: readonly { value: T; label: string; disabled?: boolean }[]
}) {
  return (
    <div className="klid-radio-row" role="group" aria-label={label}>
      <RadioGroup name={name} value={value} onChange={(v) => onChange(v as T)}>
        {options.map((o) => (
          <RadioItem key={o.value} value={o.value} disabled={o.disabled}>
            {o.label}
          </RadioItem>
        ))}
      </RadioGroup>
    </div>
  )
}
