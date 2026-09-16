import { KeyCombo } from './Kbd'
import { cx } from '../kit/util'
import './ShortcutTable.css'

/* 저작도구 화면에서만 쓰는 조각이라 스토리북 부품 목록에 두지 않고 화면 곁에 둔다 (2026-09-14 사용자 지시) */

export interface ShortcutItem {
  /** 함께 누르는 키. 하나면 `['B']` */
  keys: readonly string[]
  /** 그 키가 하는 일 */
  label: string
}

/**
 * 단축키 표 — 묶음 이름 아래 **키(왼쪽) · 하는 일(오른쪽)** 줄을 늘어놓는다.
 * 라벨링 편집기의 단축키 도움말 창이 도구 · 프레임 이동 · 액션 세 묶음으로 나란히 쓴다.
 *
 *   <ShortcutTable title="도구" items={[{ keys: ['B'], label: 'BBOX 도구' }]} />
 *
 * - **키 칸 폭은 그 표에서 가장 긴 조합이 정한다.** 줄마다 제 키 폭만큼만 쓰면 하는 일 글자의
 *   시작선이 줄마다 갈리고, 긴 조합(`Ctrl` + `Shift` + `Z`)이 글자를 밀어 겹친다 —
 *   저작도구 원본 도움말에서 실제로 조합이 이름을 넘어섰다.
 * - 키 하나에 이름표 하나다 (`KeyCombo`). 조합을 한 네모에 몰아 적지 않는다.
 * - 줄 사이는 머리카락 선이다 (§5 내부 리스트 gray-10). 줄마다 키 높이(24)가 같아 선 간격이 고르다.
 * - 짝은 `dl` 로 그린다 — 키가 이름(dt)이고 하는 일이 설명(dd)이다.
 * - `keysAt="end"` 는 **하는 일(왼쪽) · 키(오른쪽 끝선)** 로 뒤집는다 (2026-09-14 추가 — 업로드 영상
 *   마킹의 설정 기둥). 좁은 기둥 안에서 아래 요약 상자처럼 이름이 왼쪽 · 값이 오른쪽에 서야 한 기둥이
 *   같은 꼴로 읽힌다. 키끼리는 오른쪽 끝선에 붙어 조합 길이가 달라도 끝이 맞는다.
 */
export function ShortcutTable({
  title,
  items,
  keysAt = 'start',
  className,
}: {
  /** 묶음 이름 (도구 · 프레임 이동 등) */
  title: string
  items: readonly ShortcutItem[]
  /** 키가 서는 쪽 — start(왼쪽 · 기본) · end(오른쪽 끝선) */
  keysAt?: 'start' | 'end'
  className?: string
}) {
  return (
    <section className={cx('klid-shortcut-table', className)} data-keys-at={keysAt}>
      <h3 className="klid-shortcut-table-title">{title}</h3>
      <dl className="klid-shortcut-table-rows">
        {items.map((item) => (
          <div key={`${item.keys.join('+')}-${item.label}`} className="klid-shortcut-table-row">
            <dt>
              <KeyCombo keys={item.keys} />
            </dt>
            <dd>{item.label}</dd>
          </div>
        ))}
      </dl>
    </section>
  )
}
