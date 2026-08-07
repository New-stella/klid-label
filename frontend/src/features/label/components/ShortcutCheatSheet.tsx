// R4 — 단축키 치트시트 모달.
//
// SHORTCUT_KEYMAP(단일 출처)을 kind별(도구/프레임 이동/액션)로 그룹핑해 표로 렌더한다.
// 키 표기는 formatBindingKeys(대표키) 규칙과 일관 — 같은 id 의 별칭(R/Del/Backspace 등)은
// 대표 1행으로만 노출해 중복 행을 방지한다. 하드코딩 단축키 표기 근절(키맵 파생).
//
// 공통 Modal(포커스트랩·ESC·백드롭 내장)을 재사용한다.

import { Modal } from '@/components/common/Modal';

import { SHORTCUT_KEYMAP, formatBindingKeys, type ShortcutKind } from '../hooks/labelingKeymap';
import { PORTAL_HIDDEN_TOOLS } from '../types';

export interface ShortcutCheatSheetProps {
  open: boolean;
  onClose: () => void;
  /**
   * ADR-013 — 포털 모드면 미제공 도구(PORTAL_HIDDEN_TOOLS: SAM2 분할/추적·키포인트)의 단축키 안내를
   * 목록에서 제외한다. 툴바·키 디스패치를 막아놓고 안내만 남기면 포털 사용자가 존재하지 않는 기능을
   * 찾게 된다(누르면 무반응). 게이팅 판정은 세 곳 모두 PORTAL_HIDDEN_TOOLS 단일 소스를 공유한다.
   */
  portalMode?: boolean;
}

interface CheatRow {
  id: string;
  keys: string;
  label: string;
}

const GROUPS: ReadonlyArray<{ kind: ShortcutKind; title: string }> = [
  { kind: 'tool', title: '도구' },
  { kind: 'nav', title: '프레임 이동' },
  { kind: 'action', title: '액션' },
];

/** 한 kind 의 바인딩을 id 기준으로 중복 제거해 대표 행 목록으로 파생. */
function rowsForKind(kind: ShortcutKind, portalMode: boolean): CheatRow[] {
  const seen = new Set<string>();
  const rows: CheatRow[] = [];
  for (const b of SHORTCUT_KEYMAP) {
    if (b.kind !== kind) continue;
    // 포털 미제공 도구는 단축키 자체가 디스패치되지 않으므로 안내에서도 제외(useLabelingShortcuts 와 정합).
    if (portalMode && b.tool && PORTAL_HIDDEN_TOOLS.includes(b.tool)) continue;
    if (seen.has(b.id)) continue;
    seen.add(b.id);
    rows.push({ id: b.id, keys: formatBindingKeys(b.id), label: b.label });
  }
  return rows;
}

// SHORTCUT_KEYMAP 은 런타임 불변이므로 채널별 그룹/행 파생을 모듈 스코프에서 1회씩만 계산한다
// (부모 LabelingPage 의 빈번한 리렌더마다 재계산 방지). 빈 그룹은 사전 제외.
function buildGroups(portalMode: boolean) {
  return GROUPS.map((g) => ({ ...g, rows: rowsForKind(g.kind, portalMode) })).filter(
    (g) => g.rows.length > 0,
  );
}
const INTERNAL_CHEAT_GROUPS = buildGroups(false);
const PORTAL_CHEAT_GROUPS = buildGroups(true);

/**
 * 단축키 표 **본문만** — 감싸는 표면(모달 / 도구바 hover 패널)과 분리한다.
 *
 * ★표면이 둘이 된 이유: 사양은 도움말을 **좌측 도구바 맨 아래 버튼**에서 여는 것으로 규정하는데,
 *  기존 진입점은 헤더의 `?` 버튼(모달)이었다. 본문을 공유하지 않고 표면마다 표를 다시 만들면
 *  한쪽만 키맵 변경을 따라가 **두 화면의 안내가 갈린다** — SHORTCUT_KEYMAP 단일 출처 원칙이
 *  표면 단계에서 무너지는 것이라, 본문을 한 곳에 두고 표면이 이것을 담기만 한다.
 */
export function ShortcutCheatSheetContent({ portalMode = false }: { portalMode?: boolean }) {
  const groups = portalMode ? PORTAL_CHEAT_GROUPS : INTERNAL_CHEAT_GROUPS;
  return (
    <div className="grid grid-cols-1 gap-6 sm:grid-cols-3">
      {groups.map((g) => {
        return (
          <section key={g.kind} aria-labelledby={`cheat-group-${g.kind}`}>
            <h3
              id={`cheat-group-${g.kind}`}
              className="mb-2 text-sub font-semibold text-gray-500"
            >
              {g.title}
            </h3>
            <table className="w-full border-collapse text-body-md">
              <thead className="sr-only">
                <tr>
                  <th scope="col">키</th>
                  <th scope="col">기능</th>
                </tr>
              </thead>
              <tbody>
                {g.rows.map((r) => (
                  <tr key={r.id} className="border-b border-gray-100 last:border-0">
                    <td className="py-1.5 pr-3 align-top whitespace-nowrap">
                      <kbd className="rounded border border-gray-300 bg-gray-50 px-1.5 py-0.5 font-mono text-mono text-gray-700">
                        {r.keys}
                      </kbd>
                    </td>
                    <td className="py-1.5 text-gray-700">{r.label}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </section>
        );
      })}
    </div>
  );
}

/**
 * SHORTCUT_KEYMAP 기반 단축키 도움말 모달. `?`(shift+/) 로 토글된다(useLabelingShortcuts).
 */
export function ShortcutCheatSheet({ open, onClose, portalMode = false }: ShortcutCheatSheetProps) {
  return (
    <Modal
      open={open}
      onClose={onClose}
      title="단축키 도움말"
      description="입력창 포커스 중에는 단축키가 동작하지 않습니다. ? 키로 이 도움말을 여닫습니다."
      size="lg"
    >
      <ShortcutCheatSheetContent portalMode={portalMode} />
    </Modal>
  );
}
