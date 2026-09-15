// 포털 라벨링 — 단축키 도움말 창.
//
// <h3>모양 — 포털 저작도구 화면이 정본</h3>
// 포털 화면(KLID_Portal `AuthoringLabelingView` 의 단축키 도움말)과 같다 — 가장 넓은 창(lg) 안에
// 안내 띠 하나, 그 아래 세 묶음(도구 · 프레임 이동 · 액션)이 나란히 선다. 「닫기」 버튼은 두지 않는다.
//
// <h3>키는 원본 키맵이 정본이다</h3>
// 어떤 키가 무엇을 하는지는 `SHORTCUT_KEYMAP` 단일 출처에서 뽑는다(관제판 도움말과 같은 규칙 —
// 같은 id 의 별칭은 대표 한 줄, 포털에 없는 도구는 뺀다). 키 표기도 `formatBindingKeys` 그대로다.
// 화면에 서는 **하는 일 이름**만 포털 화면 문구로 바꾼 자리가 있다(아래 `PORTAL_LABEL`).

import { Alert, Modal } from '@portal/components/custom';
import { ShortcutTable, type ShortcutItem } from '@portal/pages/workspace/authoring/ShortcutTable';
import {
  formatBindingKeys,
  SHORTCUT_KEYMAP,
  type ShortcutKind,
} from '@/features/label/hooks/labelingKeymap';
import { PORTAL_HIDDEN_TOOLS } from '@/features/label/types';

const GROUPS: ReadonlyArray<{ kind: ShortcutKind; title: string }> = [
  { kind: 'tool', title: '도구' },
  { kind: 'nav', title: '프레임 이동' },
  { kind: 'action', title: '액션' },
];

/**
 * 하는 일 이름 중 포털 화면 문구가 다른 자리 — 가운뎃점 나열 · 띄어쓰기 · 「토글」 같은 영문 조어
 * (UX-writing.md). 키맵의 이름은 관제판도 쓰므로 거기서 고치지 않고 여기서만 바꿔 보인다.
 */
const PORTAL_LABEL: Record<string, string> = {
  'label.toggleVisibility': '라벨 표시·숨김',
  'polygon.complete': '폴리곤 자동 완료',
  'edit.toggle': '편집 모드 전환',
  'edit.redo': '다시 실행',
};

/** 「Ctrl+Shift+Z」 → ['Ctrl', 'Shift', 'Z']. 키 자체가 「+」 인 조합(「Shift++」)도 깨지지 않게 앞에서부터 뗀다. */
function splitKeys(formatted: string): string[] {
  const keys: string[] = [];
  let rest = formatted;
  for (const mod of ['Ctrl+', 'Shift+']) {
    if (rest.startsWith(mod) && rest.length > mod.length) {
      keys.push(mod.slice(0, -1));
      rest = rest.slice(mod.length);
    }
  }
  keys.push(rest);
  return keys;
}

/** 포털에서 도는 단축키만, 묶음별로. 키맵은 실행 중 바뀌지 않아 모듈에서 한 번만 센다. */
const PORTAL_GROUPS = GROUPS.map((g) => {
  const seen = new Set<string>();
  const items: ShortcutItem[] = [];
  for (const b of SHORTCUT_KEYMAP) {
    if (b.kind !== g.kind) continue;
    if (b.tool && PORTAL_HIDDEN_TOOLS.includes(b.tool)) continue;
    if (seen.has(b.id)) continue;
    seen.add(b.id);
    items.push({ keys: splitKeys(formatBindingKeys(b.id)), label: PORTAL_LABEL[b.id] ?? b.label });
  }
  return { title: g.title, items };
}).filter((g) => g.items.length > 0);

export function PortalShortcutHelp({
  open,
  onOpenChange,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
}) {
  return (
    <Modal size="lg" open={open} onOpenChange={onOpenChange} title="단축키 도움말">
      <Alert tone="info" live="none">
        입력창에 입력하는 중에는 단축키가 동작하지 않습니다. ? 키로 이 도움말을 열고 닫습니다.
      </Alert>
      <div className="klid-labeling-shortcuts">
        {PORTAL_GROUPS.map((group) => (
          <ShortcutTable key={group.title} title={group.title} items={group.items} />
        ))}
      </div>
    </Modal>
  );
}
