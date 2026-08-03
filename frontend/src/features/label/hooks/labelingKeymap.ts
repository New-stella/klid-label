import { ToolType } from '../types';

/**
 * 라벨링 단축키 키맵 — 관리자 매뉴얼 Rev.1.1 확정셋 기준 (docs/v1-wiki 21 §21.9).
 *
 * 단일 진실 소스(single source of truth): useLabelingShortcuts 의 키 디스패치와
 * 문서/툴바 힌트가 모두 이 배열을 참조한다. 재배치 시 여기만 수정하면
 * `auditKeymapConflicts` 테스트가 충돌을 자동 검출한다.
 *
 * 재배치 배경(v1 도구 키 ↔ Rev.1.1 확정셋 충돌 해소):
 * - WASD = 프레임 첫/이전/끝/다음. 따라서 도구 SELECT 는 S 에서 **Esc** 로 이동.
 * - 평문 T = 라벨 표시/숨김. 따라서 SAM 추적(TRACK)은 T 에서 **Shift+T** 로 이동.
 * - 폴리곤 F(점추가)/Q(자동완료), 삭제 R(+Delete), 키포인트 K·SAM분할 G 유지.
 * - 1~9(라벨 선택)은 2026-08-03 확정으로 라벨 선택 모달 전용이 되어 이 전역 키맵에서 빠졌다.
 */
export type ShortcutKind = 'tool' | 'nav' | 'action';

export interface ShortcutBinding {
  /** 액션 식별자. 같은 액션에 여러 키(A/←, R/Del 등)를 붙여도 id 는 동일하다. */
  id: string;
  /** 정규화된 e.key(소문자). 문자 키는 물리 code 와 병행 매칭. */
  key: string;
  /** 물리 키 e.code — 한글 IME/키보드 레이아웃 무관 매칭용(문자 키에만 부여). */
  code?: string;
  ctrl?: boolean;
  shift?: boolean;
  kind: ShortcutKind;
  /** kind==='tool' 인 경우 대상 도구(포털 게이팅 판단에도 사용). */
  tool?: ToolType;
  /** 문서/UI 힌트용 한글 설명. */
  label: string;
}

// ⚠ 1~9 라벨 선택은 **전역 키맵에서 제거**됐다(2026-08-03) — 좌측 상시 라벨 패널이 폐지되면서
//    전역 1~9 는 아무 시각 피드백 없이 다음 도형의 라벨을 바꾸는 조용한 상태 변경이 된다.
//    이 기능은 라벨 선택 모달(LabelPickerModal) 안으로 이전했고, 모달이 자체 리스너로 처리한다
//    (전역 훅은 모달이 열리면 hasOpenModalDialog 로 모든 단축키를 차단하므로 충돌하지 않는다).
export const SHORTCUT_KEYMAP: readonly ShortcutBinding[] = [
  // ── 도구 ────────────────────────────────────────────────────────
  { id: 'tool.bbox', key: 'b', code: 'KeyB', kind: 'tool', tool: ToolType.BBOX, label: 'BBOX 도구' },
  { id: 'tool.polygon', key: 'p', code: 'KeyP', kind: 'tool', tool: ToolType.POLYGON, label: 'Polygon 도구' },
  { id: 'tool.samSegment', key: 'g', code: 'KeyG', kind: 'tool', tool: ToolType.SAM_SEGMENT, label: 'AI 분할' },
  { id: 'tool.keypoint', key: 'k', code: 'KeyK', kind: 'tool', tool: ToolType.KEYPOINT, label: '스켈레톤' },
  { id: 'tool.track', key: 't', code: 'KeyT', shift: true, kind: 'tool', tool: ToolType.TRACK, label: 'AI 추적' },
  { id: 'tool.select', key: 'escape', kind: 'tool', tool: ToolType.SELECT, label: '선택 도구(닫기)' },

  // ── 프레임 이동 (WASD + 화살표 호환) ─────────────────────────────
  { id: 'frame.first', key: 'w', code: 'KeyW', kind: 'nav', label: '첫 프레임' },
  { id: 'frame.prev', key: 'a', code: 'KeyA', kind: 'nav', label: '이전 프레임' },
  { id: 'frame.last', key: 's', code: 'KeyS', kind: 'nav', label: '끝 프레임' },
  { id: 'frame.next', key: 'd', code: 'KeyD', kind: 'nav', label: '다음 프레임' },
  { id: 'frame.prev', key: 'arrowleft', kind: 'nav', label: '이전 프레임(←)' },
  { id: 'frame.next', key: 'arrowright', kind: 'nav', label: '다음 프레임(→)' },

  // ── 액션 ────────────────────────────────────────────────────────
  { id: 'label.toggleVisibility', key: 't', code: 'KeyT', kind: 'action', label: '라벨 표시/숨김' },
  // F/Q 폴리곤 점추가/자동완료 — OverlayLayer imperative handle(addPointAtPointer/completePolygon)로
  // 배선 완료. LabelingPage 가 CanvasShell ref 를 통해 마우스와 동일한 폴리곤 로직을 호출한다.
  { id: 'polygon.addPoint', key: 'f', code: 'KeyF', kind: 'action', label: '폴리곤 점 추가' },
  { id: 'polygon.complete', key: 'q', code: 'KeyQ', kind: 'action', label: '폴리곤 자동완료' },
  { id: 'label.delete', key: 'r', code: 'KeyR', kind: 'action', label: '객체 삭제' },
  { id: 'label.delete', key: 'delete', kind: 'action', label: '객체 삭제(Del)' },
  { id: 'label.delete', key: 'backspace', kind: 'action', label: '객체 삭제(Backspace)' },
  { id: 'edit.toggle', key: 'e', code: 'KeyE', kind: 'action', label: '편집 모드 토글' },
  // US 키보드에서 '+' 는 Shift+'=', '_' 는 Shift+'-' 로 입력되므로 shift 를 명시해야 매칭된다
  // (shift 누락 시 matches() 의 shift 비교로 영영 매칭 실패 — 회귀). 무수식 '='/'-' 별칭도 유지.
  { id: 'zoom.in', key: '+', shift: true, kind: 'action', label: '확대' },
  { id: 'zoom.in', key: '=', kind: 'action', label: '확대(=)' },
  { id: 'zoom.out', key: '-', kind: 'action', label: '축소' },
  { id: 'zoom.out', key: '_', shift: true, kind: 'action', label: '축소(_)' },
  { id: 'edit.save', key: 's', ctrl: true, kind: 'action', label: '저장' },
  { id: 'edit.undo', key: 'z', ctrl: true, kind: 'action', label: '실행 취소' },
  { id: 'edit.redo', key: 'z', ctrl: true, shift: true, kind: 'action', label: '재실행' },
  // ── 복사/붙여넣기 (Phase 4) ──────────────────────────────────────
  // Ctrl+C: 선택 라벨 복사 / Ctrl+Shift+C: 프레임 전체 복사
  { id: 'clipboard.copy', key: 'c', code: 'KeyC', ctrl: true, kind: 'action', label: '선택 복사' },
  { id: 'clipboard.copyAll', key: 'c', code: 'KeyC', ctrl: true, shift: true, kind: 'action', label: '전체 복사' },
  // Ctrl+V / Ctrl+Shift+V: 현재 프레임에 붙여넣기(별칭)
  { id: 'clipboard.paste', key: 'v', code: 'KeyV', ctrl: true, kind: 'action', label: '붙여넣기' },
  { id: 'clipboard.paste', key: 'v', code: 'KeyV', ctrl: true, shift: true, kind: 'action', label: '붙여넣기(전체)' },
];

/**
 * 특수(비문자) 키의 사람 표기 — 툴바 툴팁/문서 힌트용.
 * 단문자 키는 대문자화(b→B)로 처리하고, 여기 있는 키만 별도 라벨을 쓴다.
 */
const KEY_DISPLAY: Record<string, string> = {
  escape: 'Esc',
  delete: 'Del',
  backspace: 'Backspace',
  arrowleft: '←',
  arrowright: '→',
  arrowup: '↑',
  arrowdown: '↓',
};

/** 단일 바인딩을 사람 표기('Shift+T','Ctrl+S','Esc','B')로 포맷. */
function formatBinding(b: ShortcutBinding): string {
  const parts: string[] = [];
  if (b.ctrl) parts.push('Ctrl');
  if (b.shift) parts.push('Shift');
  const k = b.key.toLowerCase();
  parts.push(KEY_DISPLAY[k] ?? (k.length === 1 ? k.toUpperCase() : k));
  return parts.join('+');
}

/**
 * 액션 id 의 대표 단축키를 사람 표기로 반환 — DarkToolbar 툴팁/문서 힌트가 키맵과 100% 일치하도록
 * SHORTCUT_KEYMAP 단일 출처에서 파생한다. 하드코딩 툴팁(오표기) 근절용.
 *
 * 별칭 규칙: 같은 id 에 바인딩이 여럿이면(예: label.delete = r/delete/backspace)
 * 이름있는 특수키(Esc/Del 등, KEY_DISPLAY 등재)를 대표로, 없으면 선언 순서상 첫 바인딩을 쓴다.
 * 단 nav(프레임 이동)는 WASD 주키가 화살표 별칭(←/→)에 가려지지 않도록 **문자키를 우선**한다.
 * 미등록 id 는 빈 문자열.
 */
export function formatBindingKeys(id: string): string {
  const all = SHORTCUT_KEYMAP.filter((b) => b.id === id);
  if (all.length === 0) return '';
  // nav: A/D/W/S(단문자) 우선 — 화살표 별칭보다 주키를 대표로 노출.
  if (all[0].kind === 'nav') {
    const letter = all.find((b) => b.key.length === 1);
    return formatBinding(letter ?? all[0]);
  }
  const named = all.find((b) => b.key.toLowerCase() in KEY_DISPLAY);
  return formatBinding(named ?? all[0]);
}

/** 수식키 + 키 조합 서명 — 충돌 판정 및 매칭 정규화 기준. */
export function comboSignature(b: Pick<ShortcutBinding, 'key' | 'ctrl' | 'shift'>): string {
  return `${b.ctrl ? 'C' : ''}${b.shift ? 'S' : ''}:${b.key.toLowerCase()}`;
}

/**
 * 키맵 충돌 감사 — 같은 (수식키+키) 조합에 서로 다른 액션(id)이 매핑되면 충돌.
 * 같은 액션에 여러 키를 붙이는 것(별칭)은 충돌이 아니다.
 * @returns 충돌 목록(없으면 빈 배열)
 */
export function auditKeymapConflicts(
  map: readonly ShortcutBinding[] = SHORTCUT_KEYMAP,
): Array<{ signature: string; ids: string[] }> {
  const bySignature = new Map<string, Set<string>>();
  for (const b of map) {
    const sig = comboSignature(b);
    const set = bySignature.get(sig) ?? new Set<string>();
    set.add(b.id);
    bySignature.set(sig, set);
  }
  const conflicts: Array<{ signature: string; ids: string[] }> = [];
  for (const [signature, ids] of bySignature) {
    if (ids.size > 1) conflicts.push({ signature, ids: [...ids].sort() });
  }
  return conflicts;
}

/**
 * 조회 헬퍼 — 정규화된 key(+선택 수식키)로 바인딩 검색. 테스트/문서 용도.
 * 수식키 미지정 시 수식키 없는 바인딩만 매칭한다.
 */
export function findBinding(
  key: string,
  mods: { ctrl?: boolean; shift?: boolean } = {},
): ShortcutBinding | undefined {
  const target = comboSignature({ key, ctrl: mods.ctrl, shift: mods.shift });
  return SHORTCUT_KEYMAP.find((b) => comboSignature(b) === target);
}
