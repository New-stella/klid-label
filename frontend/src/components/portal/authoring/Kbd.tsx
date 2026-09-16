import { Fragment } from 'react'
import { cx } from '../kit/util'
import './Kbd.css'

/* 저작도구 화면에서만 쓰는 조각이라 스토리북 부품 목록에 두지 않고 화면 곁에 둔다 (2026-09-14 사용자 지시) */

/**
 * 키 이름표 — 키보드 키 하나를 작은 네모 안에 적는다 (`B` · `Esc` · `Ctrl`).
 * 도구 목록 줄 끝의 단축키와 단축키 도움말 표가 같은 모양을 쓴다.
 *
 *   <Kbd>Esc</Kbd>
 *   <KeyCombo keys={['Ctrl', 'Shift', 'Z']} />
 *
 * - **키 하나에 이름표 하나**다. 「Ctrl + Shift + Z」 를 한 네모에 몰아 적으면 폭이 늘어나
 *   옆 글을 밀고, 어디까지가 한 키인지도 안 갈린다 (저작도구 원본 도움말에서 실제로 넘쳤다).
 * - 한 글자 키도 네모로 선다 — 폭 하한을 높이와 같게 둔다. 글자 수가 달라도 줄 끝이 들쭉날쭉하지 않다.
 * - 서체는 본문과 같다. 브라우저 기본(고정폭)을 두면 이 네모 안의 글자만 다른 서비스처럼 읽힌다.
 */
export function Kbd({ children, className }: { children: string; className?: string }) {
  return <kbd className={cx('klid-kbd', className)}>{children}</kbd>
}

/**
 * 함께 누르는 키 — 키마다 이름표 하나, 사이에 작은 「+」.
 * 키가 하나뿐이면 이름표 하나로 선다.
 * HTML 이 정한 대로 바깥 `kbd` 가 안쪽 `kbd` 들을 감싼다 (한 번에 누르는 한 입력이라는 뜻).
 */
export function KeyCombo({ keys, className }: { keys: readonly string[]; className?: string }) {
  if (keys.length === 1) return <Kbd className={className}>{keys[0]}</Kbd>
  return (
    <kbd className={cx('klid-kbd-combo', className)}>
      {keys.map((k, i) => (
        <Fragment key={`${k}-${i}`}>
          {i > 0 && <span className="klid-kbd-plus">+</span>}
          <Kbd>{k}</Kbd>
        </Fragment>
      ))}
    </kbd>
  )
}
