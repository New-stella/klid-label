import type { ReactNode } from 'react'
import { cx } from './util'
import './KeyValueList.css'

export type KeyValueItem = {
  label: string
  value: ReactNode
}

/** 라벨·값을 담는 세 가지 꼴 (아래 컴포넌트 주석 참조) */
export type KeyValueLayout = 'rows' | 'cards' | 'stack'

/**
 * 라벨·값 묶음 — 같은 짝(라벨 + 값)을 **세 가지 꼴**로 담는다. 담는 것은 같고 읽는 방식이 다르다.
 *
 *   layout="rows" — 판 한 장 (design.md §5 「두 칸짜리 정보 표」)
 *   ┌──────────────────────────┐      값을 **차례로 읽어 내려가는** 자리.
 *   │  Accuracy         94.2%  │      오른쪽 끝선이 하나라 자릿수·단위가 달라도
 *   │ ──────────────────────── │      눈이 한 줄로 내려오며 다 읽는다.
 *   │  F1-Score          0.91  │      좁은 화면·항목이 많은 자리에 맞는다.
 *   └──────────────────────────┘
 *
 *   layout="cards" — 카드 여러 장
 *   ┌────────┐┌────────┐┌────────┐   값끼리 **크기를 견주는** 자리.
 *   │Accuracy││F1-Score││Precision│   라벨 위 · 값 아래로 쌓아 숫자가 먼저 읽히고,
 *   │  94.2% ││  0.91  ││  0.89   │   넉 장이 나란히 서서 서로 비교된다.
 *   └────────┘└────────┘└────────┘   넓은 화면·항목이 서넛인 자리에 맞는다.
 *
 *   layout="stack" — 면 없이 쌓기 (학습데이터 상세 개요의 라벨·값과 같은 글꼴)
 *   파일명                             **좁은 칸에서 긴 값을 되보여 주는** 자리.
 *   실종자추적_v0.7_20260910.mp4       라벨이 위에 서서 값이 칸 폭을 다 쓴다 —
 *                                      판 꼴은 라벨이 폭을 나눠 가져 긴 값이 오른쪽에서
 *   올린 일시                          잘게 접힌다. 선 · 면을 두지 않는다 (편집기 옆 칸처럼
 *   2026-09-11 10:49                   이미 칸 안에 놓이는 자리). `surface` 는 받지 않는다.
 *
 * KRDS 에 대응 컴포넌트가 없다. 값끼리 크기를 견주되 **아이콘 칩과 캡션까지 거느리는** 자리는
 * StatCard 를 쓴다 — 그쪽은 대시보드 KPI 라 강조 카드(card-soft)다.
 *
 * 면은 두 꼴 모두 공통 구획 카드(.klid-section-card)를 쓴다 (design.md §5 — 흰 면 · gray-20
 * 보더 · 라운드 16). 면을 갖는 것이 rows 는 판, cards 는 카드 한 장 한 장이다.
 * 판 안에 이미 다른 면이 있어 두 겹이 되는 자리에서는 `surface={false}` 로 면을 끈다.
 * 흰 면 안에서 **「지금 값이면 이렇게 된다」를 되보여 주는 요약 상자**는 `surface="muted"` 다
 * (2026-09-11 추가 — 저작도구 업로드 영상 마킹의 간격·뽑힐 프레임 요약). 옅은 회색 면만 깔고
 * 보더·구분선을 두지 않는다 — 흰 카드 안에서 또 흰 판을 세우면 면이 두 겹이 되고, 선을 그으면
 * 두 줄짜리 요약이 표로 읽힌다. rows 꼴에서만 갈린다.
 */
export function KeyValueList({
  items,
  ariaLabel,
  layout = 'rows',
  emphasis = 'fact',
  surface = true,
  divided = true,
  className,
}: {
  items: KeyValueItem[]
  /** 소제목을 달지 않는 자리에서도 보조기술이 읽을 이름은 준다 (design.md §2) */
  ariaLabel?: string
  /** 판 한 장(rows · 기본) · 카드 여러 장(cards) · 면 없이 쌓기(stack) */
  layout?: KeyValueLayout
  /**
   * 값의 무게 (rows 꼴에서만 갈린다).
   *   fact (기본) — **되보여 주는 사실.** 라벨과 같은 15 로 서고 굵기·색으로만 구분한다
   *   figure       — **견주는 수치.** 라벨보다 두 단 큰 20 으로 서서 값이 먼저 읽힌다
   * 이 판이 담는 것은 대개 아이디·날짜·이름·코드처럼 **되보여 주는 사실**이고, 그 자리에서
   * 값이 20 이면 아직 적어야 할 입력칸보다 커서 다 끝난 값이 화면에서 제일 크게 읽힌다.
   * 그래서 기본이 fact 다 (2026-09-07 — 종전 기본값 figure 를 관리자 창 여덟 곳이 그대로
   * 받아 값만 20 으로 서 있었다). figure 는 **값끼리 크기를 견주는** 자리에만 준다
   * (성능 지표처럼 한 판 안에서 숫자를 서로 대보는 자리)
   */
  emphasis?: 'figure' | 'fact'
  /**
   * 면(흰 배경 · 보더 · 라운드)을 갖는다. 이미 면 안에 놓이는 자리에서만 끈다.
   * `'muted'` 는 흰 면 안의 요약 상자 — 옅은 회색 면만 깐다 (rows 꼴 전용)
   */
  surface?: boolean | 'muted'
  /**
   * 줄 사이 구분선 (rows 꼴 · 면 없음에서만 갈린다). `false` 면 선을 걷고 줄끼리 8 로 붙인다 —
   * 창 안처럼 위에 이미 구획 선이 있어 세 줄 남짓한 요약에 줄 선까지 그으면 표로 읽히는 자리
   * (2026-09-15 추가 — 저작도구 마킹 완료 확인 창)
   */
  divided?: boolean
  className?: string
}) {
  const cards = layout === 'cards'
  const rows = layout === 'rows'
  const muted = rows && surface === 'muted'
  const undivided = rows && surface === false && !divided
  return (
    <dl
      className={cx(
        'klid-kv-list',
        `klid-kv-${layout}`,
        rows && surface === true && 'klid-section-card',
        className,
      )}
      aria-label={ariaLabel}
      data-emphasis={rows ? emphasis : undefined}
      data-surface={muted ? 'muted' : undefined}
      data-divided={undivided ? 'false' : undefined}
    >
      {items.map((item) => (
        <div key={item.label} className={cx('row', cards && surface === true && 'klid-section-card')}>
          <dt>{item.label}</dt>
          <dd>{item.value}</dd>
        </div>
      ))}
    </dl>
  )
}
