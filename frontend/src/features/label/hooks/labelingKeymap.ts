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
 * - 폴리곤 F(점추가)/Q(자동완료), 삭제 R(+Delete), 키포인트 K·SAM분할 G·1~9 라벨 유지.
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

const DIGIT_BINDINGS: ShortcutBinding[] = Array.from({ length: 9 }, (_, i) => ({
  id: 'label.digit',
  key: String(i + 1),
  kind: 'action' as const,
  label: `라벨 ${i + 1}번 선택`,
}));

export const SHORTCUT_KEYMAP: readonly ShortcutBinding[] = [
  // ── 도구 ────────────────────────────────────────────────────────
  { id: 'tool.bbox', key: 'b', code: 'KeyB', kind: 'tool', tool: ToolType.BBOX, label: 'BBOX 도구' },
  { id: 'tool.polygon', key: 'p', code: 'KeyP', kind: 'tool', tool: ToolType.POLYGON, label: 'Polygon 도구' },
  { id: 'tool.samSegment', key: 'g', code: 'KeyG', kind: 'tool', tool: ToolType.SAM_SEGMENT, label: 'SAM 분할' },
  { id: 'tool.keypoint', key: 'k', code: 'KeyK', kind: 'tool', tool: ToolType.KEYPOINT, label: '키포인트' },
  { id: 'tool.track', key: 't', code: 'KeyT', shift: true, kind: 'tool', tool: ToolType.TRACK, label: 'SAM 추적' },
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
  // ⚠️ 후속 배선 대기(Phase 2 descope): F/Q 폴리곤 점추가/자동완료는 OverlayLayer 의 폴리곤
  // 편집 상태가 컴포넌트 내부에 캡슐화돼 있어 LabelingPage 에서 단순 prop 배선이 불가하다.
  // OverlayLayer state 리프팅/imperative handle 구조 리팩터가 선행돼야 실동작한다.
  // 키맵 항목은 유지하되(문서/충돌감사 일관성), LabelingPage 는 아직 onPolygon* 핸들러를
  // 전달하지 않으므로 현재는 no-op 이다. 상세는 .claude-plan.md Phase 2 하단 참조.
  { id: 'polygon.addPoint', key: 'f', code: 'KeyF', kind: 'action', label: '폴리곤 점 추가(후속 배선 대기)' },
  { id: 'polygon.complete', key: 'q', code: 'KeyQ', kind: 'action', label: '폴리곤 자동완료(후속 배선 대기)' },
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
  { id: 'edit.undo', key: 'z', ctrl: true, kind: 'action', label: '실행취소' },
  { id: 'edit.redo', key: 'z', ctrl: true, shift: true, kind: 'action', label: '재실행' },
  ...DIGIT_BINDINGS,
];

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
