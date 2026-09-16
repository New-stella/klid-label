import { useState } from 'react'
import { Badge } from 'krds-react'
import { ChevronDown, Eye, EyeOff, Lock, LockOpen, Trash2 } from 'lucide-react'
import { EmptyState } from '../kit/EmptyState'
import { IconButton } from '../kit/IconButton'
import { cx } from '../kit/util'
import './ObjectList.css'

/* 저작도구 화면에서만 쓰는 조각이라 스토리북 부품 목록에 두지 않고 화면 곁에 둔다 (2026-09-14 사용자 지시) */

export interface ObjectListItem {
  id: string
  /** 줄 이름 (「사람 #1」) */
  name: string
  /** 트랙 ID — 저작도구가 준 값 그대로(숫자가 아닐 수도 있다). 없으면 「T:—」 */
  trackId?: number | string
  /** 만든 방식 */
  source: 'manual' | 'auto' | 'interp'
  /** 도형 종류 표기 (「BBOX」) */
  shape: string
  hidden?: boolean
  locked?: boolean
}

export interface ObjectListGroup {
  label: string
  /** 라벨 색 — 무리 머리의 색 점. CSS 색 값(토큰) */
  color: string
  items: readonly ObjectListItem[]
}

/* 만든 방식 배지 — 수동(사람이 그림) · 자동(자동 생성) · 보간(앞뒤 프레임 사이를 이어 채움).
   코발트는 쓰지 않는다 — 고른 줄의 면(primary-5)과 배지 면이 같아 배지가 사라진다 */
const SOURCE: Record<ObjectListItem['source'], { label: string; color: 'gray' | 'success' | 'point' }> = {
  manual: { label: '수동', color: 'gray' },
  auto: { label: '자동', color: 'success' },
  interp: { label: '보간', color: 'point' },
}

/**
 * 객체 목록 — 지금 프레임의 객체를 **라벨별 무리**로 묶어 줄로 세운다. 라벨링 편집기 속성 칸 객체 탭이 쓴다.
 *
 * ```
 *  ▾ ● 사람 (2)
 *    T:3 | BBOX            👁 🔒 🗑
 *    사람 #1 [수동]
 *    ─────────────────────────
 *    T:— | BBOX            👁 🔒 🗑
 *    사람 #2 [자동]
 * ```
 *
 * - 무리 머리를 누르면 접고 편다. 색 점은 라벨 색, 괄호 수는 그 라벨의 객체 수다.
 * - 줄과 줄 사이는 가로 구분선이다. 색은 무리 머리의 라벨 색 점 하나만 쓴다 — 줄마다 트랙 색을 또
 *   얹으면 목록 안의 색이 두 갈래가 되어 어느 색이 무엇인지 헷갈린다 (2026-09-14 결정).
 * - 한 객체가 두 줄이다 — 윗줄은 트랙 번호 | 형태 (세로선) · 도구, 아랫줄은 이름 · 만든 방식 배지.
 *   트랙 번호(T:3)는 여러 프레임에 걸친 같은 객체를 잇는 번호다. 없으면 「T:—」.
 * - 이름을 누르면 그 객체를 고른다. 고른 줄은 면이 강조된다.
 * - 눈 · 자물쇠 · 휴지통(「객체 삭제」)은 늘 보인다 (2026-09-14 결정). 잠긴 객체는 지울 수 없다 (휴지통이 잠긴다).
 *   도구는 공용 아이콘 버튼(sm)이고 사이 4 는 편집기 도구 줄과 같다.
 * - `disabled` 는 저장 중 — 고르기 · 지우기가 모두 막힌다.
 */
export function ObjectList({
  groups,
  selectedId,
  onSelect,
  onToggleHidden,
  onToggleLocked,
  onDelete,
  disabled,
  empty = '이 프레임에 객체가 없습니다',
  className,
}: {
  groups: readonly ObjectListGroup[]
  selectedId?: string | null
  onSelect?: (id: string) => void
  onToggleHidden?: (id: string) => void
  onToggleLocked?: (id: string) => void
  onDelete?: (id: string) => void
  disabled?: boolean
  /** 객체가 없을 때 안내 */
  empty?: string
  className?: string
}) {
  const [folded, setFolded] = useState<Record<string, boolean>>({})
  if (groups.every((g) => g.items.length === 0)) {
    return <EmptyState size="xs" title={empty} className={className} />
  }
  return (
    <div className={cx('klid-object-list', className)}>
      {groups.map((g) => {
        const open = !folded[g.label]
        return (
          <div key={g.label} className="klid-object-group">
            <button
              type="button"
              className="klid-object-group-head"
              aria-expanded={open}
              onClick={() => setFolded((f) => ({ ...f, [g.label]: open }))}
            >
              <ChevronDown className="chevron" aria-hidden />
              <span className="dot" style={{ backgroundColor: g.color }} aria-hidden />
              <span className="name">{g.label}</span>
              <span className="count">({g.items.length})</span>
            </button>
            {open && (
              <ul className="klid-object-rows">
                {g.items.map((o) => {
                  const on = selectedId === o.id
                  return (
                    <li key={o.id} className="klid-object-row" data-selected={on || undefined} data-hidden={o.hidden || undefined}>
                      <div className="lines">
                        {/* 트랙 번호 | 형태 — 배지 없이 글자로, 공용 세로선으로 가른다 */}
                        <div className="line facts">
                          {/* 손을 올리면 무엇인지 말한다 — 없을 때는 「미부여」 (저작도구 원본 목록과 같은 설명) */}
                          <span
                            className="track"
                            title={o.trackId != null ? `트랙 ID ${o.trackId}` : '트랙 ID 미부여'}
                          >
                            T:{o.trackId ?? '—'}
                          </span>
                          <span className="klid-meta-div" aria-hidden />
                          <span className="shape">{o.shape}</span>
                        </div>
                        <div className="line">
                          <button
                            type="button"
                            className="name"
                            aria-pressed={on}
                            disabled={disabled}
                            onClick={() => onSelect?.(o.id)}
                          >
                            {o.name}
                          </button>
                          <Badge variant="light" color={SOURCE[o.source].color} className="klid-badge-tint klid-badge-square">
                            {SOURCE[o.source].label}
                          </Badge>
                        </div>
                      </div>
                      {/* 숨김 · 잠금은 켜지면 글리프가 바뀌고 코발트로 선다 (눌림 채움은 목록에서 너무 무겁다) */}
                      <span className="tools">
                        <IconButton
                          size="sm"
                          tone={o.hidden ? 'primary' : 'default'}
                          aria-label={o.hidden ? `${o.name} 표시` : `${o.name} 숨기기`}
                          onClick={() => onToggleHidden?.(o.id)}
                        >
                          {o.hidden ? <EyeOff aria-hidden /> : <Eye aria-hidden />}
                        </IconButton>
                        <IconButton
                          size="sm"
                          tone={o.locked ? 'primary' : 'default'}
                          aria-label={o.locked ? `${o.name} 잠금 해제` : `${o.name} 잠금`}
                          onClick={() => onToggleLocked?.(o.id)}
                        >
                          {o.locked ? <Lock aria-hidden /> : <LockOpen aria-hidden />}
                        </IconButton>
                        <IconButton
                          size="sm"
                          tone="danger"
                          aria-label={`${o.name} 객체 삭제`}
                          title="객체 삭제"
                          disabled={disabled || o.locked}
                          onClick={() => onDelete?.(o.id)}
                        >
                          <Trash2 aria-hidden />
                        </IconButton>
                      </span>
                    </li>
                  )
                })}
              </ul>
            )}
          </div>
        )
      })}
    </div>
  )
}
