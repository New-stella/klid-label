// SCR-LABEL-001 좌측 세로 도구바 (mock 정합 — 아이콘 only, w-14).
//
// 도구: 선택(Esc) / 바운딩박스(B) / 폴리곤(P) / SAM분할(G) / SAM추적(Shift+T) / 키포인트(K)
//       / [구분선] / 삭제(Del) / 실행취소(Ctrl+Z) / [구분선] / 저장(Ctrl+S)
// ★ 단축키 표기는 하드코딩하지 않고 SHORTCUT_KEYMAP(단일 출처)에서 formatBindingKeys 로 파생 —
//   키맵과 툴팁이 100% 일치(오표기 0)하도록 보장한다.

import {
  Loader2,
  Maximize2,
  MousePointer2,
  Pentagon,
  PersonStanding,
  RotateCcw,
  Route,
  ScanSearch,
  Save,
  Sparkles,
  Square,
  Trash2,
} from 'lucide-react';

import { cn } from '@/lib/cn';
import { useLabelStore } from '@/stores/useLabelStore';

import { formatBindingKeys } from '../hooks/labelingKeymap';
import { PORTAL_HIDDEN_TOOLS, ToolType } from '../types';

// 각 도구/액션 버튼의 단축키 툴팁은 SHORTCUT_KEYMAP 단일 출처에서 파생한다(하드코딩 오표기 근절).
// 키맵 id ↔ 툴바 항목 매핑. YOLO 오토라벨은 키맵 미등록이라 별도 고정 표기('Y').
const TOOL_KEYMAP_ID: Partial<Record<ToolType, string>> = {
  [ToolType.SELECT]: 'tool.select',
  [ToolType.BBOX]: 'tool.bbox',
  [ToolType.POLYGON]: 'tool.polygon',
  [ToolType.SAM_SEGMENT]: 'tool.samSegment',
  [ToolType.TRACK]: 'tool.track',
  [ToolType.KEYPOINT]: 'tool.keypoint',
};

interface DarkToolbarProps {
  onSave: () => void;
  /**
   * R17 이슈3 / ADR-013 — 포털 모드에서는 SAM2 분할/추적 도구를 미노출.
   * 포털은 데이터마트 영상 간편 라벨링 전용으로 오토라벨링(SAM2/YOLO)을 제공하지 않으며,
   * 내부 /frames/{id}/sam2-* 엔드포인트도 PORTAL 채널 403 이다.
   */
  portalMode?: boolean;
  /**
   * Phase 3 — YOLO 오토라벨 수동 트리거 핸들러. 미지정 시 버튼 미노출.
   * ADR-013 — 포털 모드에서는 항상 숨김(BE /frames/{id}/autolabel 도 PORTAL 채널 403).
   */
  onAutolabel?: () => void;
  /** YOLO 오토라벨 요청 진행 중 — 버튼 로딩/비활성 표시 + 중복 클릭 방지. */
  isAutolabeling?: boolean;
}

interface ToolItem {
  kind: 'tool';
  tool: ToolType;
  icon: React.ElementType;
  label: string;
  shortcut: string;
}

interface ActionItem {
  kind: 'action';
  icon: React.ElementType;
  label: string;
  shortcut: string;
  action: () => void;
  /** ADR-013 — 포털 모드에서 숨김 대상 액션(오토라벨 등). */
  portalHidden?: boolean;
  /** 진행 중 표시 — 스피너 + 비활성. */
  busy?: boolean;
}

interface DividerItem {
  kind: 'divider';
}

type Item = ToolItem | ActionItem | DividerItem;

export function DarkToolbar({
  onSave,
  portalMode = false,
  onAutolabel,
  isAutolabeling = false,
}: DarkToolbarProps) {
  const activeTool = useLabelStore((s) => s.activeTool);
  const setActiveTool = useLabelStore((s) => s.setActiveTool);
  const undo = useLabelStore((s) => s.undo);
  const removeLabel = useLabelStore((s) => s.removeLabel);
  const selectedId = useLabelStore((s) => s.selectedLabelId);
  // R3 — 수동 Fit(뷰 초기화): zoom=1·pan=0 으로 화면 맞춤 복귀.
  const resetView = useLabelStore((s) => s.resetView);

  const handleDelete = () => {
    if (selectedId) removeLabel(selectedId);
  };

  // Phase 9 — 포털에 SAM 분할/추적·키포인트 도구 제공(PORTAL_HIDDEN_TOOLS 현재 비어있음).
  // 오토라벨(YOLO) 액션만 포털 숨김 유지(ADR-013 — 데이터마트 영상 오토라벨 미제공).
  // (단축키 게이팅 useLabelingShortcuts 와 동일 정책 소스.)
  // 도구 단축키는 키맵에서 파생(TOOL_KEYMAP_ID). 액션 단축키도 키맵 id 로 파생.
  const toolShortcut = (tool: ToolType): string => {
    const id = TOOL_KEYMAP_ID[tool];
    return id ? formatBindingKeys(id) : '';
  };
  const allItems: Item[] = [
    { kind: 'tool', tool: ToolType.SELECT, icon: MousePointer2, label: '선택', shortcut: toolShortcut(ToolType.SELECT) },
    { kind: 'tool', tool: ToolType.BBOX, icon: Square, label: '바운딩박스', shortcut: toolShortcut(ToolType.BBOX) },
    { kind: 'tool', tool: ToolType.POLYGON, icon: Pentagon, label: '폴리곤', shortcut: toolShortcut(ToolType.POLYGON) },
    { kind: 'tool', tool: ToolType.SAM_SEGMENT, icon: Sparkles, label: 'SAM 분할', shortcut: toolShortcut(ToolType.SAM_SEGMENT) },
    { kind: 'tool', tool: ToolType.TRACK, icon: Route, label: 'SAM 추적', shortcut: toolShortcut(ToolType.TRACK) },
    { kind: 'tool', tool: ToolType.KEYPOINT, icon: PersonStanding, label: '키포인트', shortcut: toolShortcut(ToolType.KEYPOINT) },
    // Phase 3 — YOLO 오토라벨 수동 트리거(액션). 핸들러가 주어질 때만 노출, 포털 숨김(ADR-013).
    // YOLO 는 키맵 미등록(파이프라인 트리거)이라 표기는 고정 'Y'.
    ...(onAutolabel
      ? [
          {
            kind: 'action' as const,
            icon: ScanSearch,
            label: 'YOLO 오토라벨',
            shortcut: 'Y',
            action: onAutolabel,
            portalHidden: true,
            busy: isAutolabeling,
          },
        ]
      : []),
    { kind: 'divider' },
    { kind: 'action', icon: Trash2, label: '삭제', shortcut: formatBindingKeys('label.delete'), action: handleDelete },
    { kind: 'action', icon: RotateCcw, label: '실행취소', shortcut: formatBindingKeys('edit.undo'), action: undo },
    // R3 — 화면 맞춤(Fit): 프레임 전환 시 뷰 유지 정책과 짝을 이루는 수동 초기화 컨트롤(키맵 미배정).
    { kind: 'action', icon: Maximize2, label: '화면 맞춤', shortcut: '', action: resetView },
    { kind: 'divider' },
    { kind: 'action', icon: Save, label: '저장', shortcut: formatBindingKeys('edit.save'), action: onSave },
  ];
  const items: Item[] = allItems.filter((item) => {
    if (!portalMode) return true;
    // Phase 9 — 포털 숨김 도구는 PORTAL_HIDDEN_TOOLS(현재 비어있음)로만 관리. 오토라벨 액션만 portalHidden.
    if (item.kind === 'tool') return !PORTAL_HIDDEN_TOOLS.includes(item.tool);
    if (item.kind === 'action') return !item.portalHidden;
    return true;
  });

  return (
    <div
      className="flex flex-col items-center gap-1 p-2 bg-gray-800 border-r border-gray-700 w-14 shrink-0"
      role="toolbar"
      aria-label="라벨링 도구"
    >
      {items.map((item, idx) => {
        if (item.kind === 'divider') {
          return <div key={idx} className="w-8 h-px bg-gray-600 my-1" />;
        }
        const busy = item.kind === 'action' && item.busy === true;
        const Icon = busy ? Loader2 : item.icon;
        const isActive = item.kind === 'tool' && activeTool === item.tool;
        const handleClick =
          item.kind === 'action' ? item.action : () => setActiveTool(item.tool);

        return (
          <div key={idx} className="relative group">
            <button
              type="button"
              onClick={handleClick}
              disabled={busy}
              aria-label={item.label}
              // 단축키를 title 로도 노출 — 키맵 파생(오표기 0), 마우스 호버/스크린리더 힌트.
              title={item.shortcut ? `${item.label} (${item.shortcut})` : item.label}
              aria-pressed={isActive}
              aria-busy={busy}
              className={cn(
                'w-10 h-10 rounded-lg flex items-center justify-center transition-colors',
                isActive
                  ? 'bg-primary-600 text-white'
                  : 'text-gray-300 hover:bg-gray-700 hover:text-white',
                busy && 'opacity-60 cursor-not-allowed',
              )}
            >
              <Icon size={18} className={cn(busy && 'animate-spin')} />
            </button>
            {/* Tooltip — group-hover로 우측에 노출 */}
            <div className="absolute left-12 top-1/2 -translate-y-1/2 z-50 pointer-events-none opacity-0 group-hover:opacity-100 transition-opacity">
              <div className="bg-gray-900 text-white text-xs rounded px-2 py-1 whitespace-nowrap border border-gray-700 shadow-lg">
                {item.label}
                <span className="ml-2 text-gray-400">{item.shortcut}</span>
              </div>
            </div>
          </div>
        );
      })}
    </div>
  );
}
