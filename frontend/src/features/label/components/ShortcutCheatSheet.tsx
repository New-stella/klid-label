// R4 — 단축키 치트시트 모달.
//
// SHORTCUT_KEYMAP(단일 출처)을 kind별(도구/프레임 이동/액션)로 그룹핑해 표로 렌더한다.
// 키 표기는 formatBindingKeys(대표키) 규칙과 일관 — 같은 id 의 별칭(R/Del/Backspace 등)은
// 대표 1행으로만 노출해 중복 행을 방지한다. 하드코딩 단축키 표기 근절(키맵 파생).
//
// 공통 Modal(포커스트랩·ESC·백드롭 내장)을 재사용한다.

import { Modal } from '@/components/common/Modal';

import { SHORTCUT_KEYMAP, formatBindingKeys, type ShortcutKind } from '../hooks/labelingKeymap';

export interface ShortcutCheatSheetProps {
  open: boolean;
  onClose: () => void;
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
function rowsForKind(kind: ShortcutKind): CheatRow[] {
  const seen = new Set<string>();
  const rows: CheatRow[] = [];
  for (const b of SHORTCUT_KEYMAP) {
    if (b.kind !== kind) continue;
    if (seen.has(b.id)) continue;
    seen.add(b.id);
    rows.push({ id: b.id, keys: formatBindingKeys(b.id), label: b.label });
  }
  return rows;
}

// SHORTCUT_KEYMAP 은 런타임 불변이므로 그룹/행 파생을 모듈 스코프에서 1회만 계산한다
// (부모 LabelingPage 의 빈번한 리렌더마다 재계산 방지). 빈 그룹은 사전 제외.
const CHEAT_GROUPS = GROUPS.map((g) => ({ ...g, rows: rowsForKind(g.kind) })).filter(
  (g) => g.rows.length > 0,
);

/**
 * SHORTCUT_KEYMAP 기반 단축키 도움말 모달. `?`(shift+/) 로 토글된다(useLabelingShortcuts).
 */
export function ShortcutCheatSheet({ open, onClose }: ShortcutCheatSheetProps) {
  return (
    <Modal
      open={open}
      onClose={onClose}
      title="단축키 도움말"
      description="입력창 포커스 중에는 단축키가 동작하지 않습니다. ? 키로 이 도움말을 여닫습니다."
      size="lg"
    >
      <div className="grid grid-cols-1 gap-6 sm:grid-cols-3">
        {CHEAT_GROUPS.map((g) => {
          return (
            <section key={g.kind} aria-labelledby={`cheat-group-${g.kind}`}>
              <h3
                id={`cheat-group-${g.kind}`}
                className="mb-2 text-sub font-semibold text-gray-500"
              >
                {g.title}
              </h3>
              <table className="w-full border-collapse text-sm">
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
                        <kbd className="rounded border border-gray-300 bg-gray-50 px-1.5 py-0.5 font-mono text-xs text-gray-700">
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
    </Modal>
  );
}
