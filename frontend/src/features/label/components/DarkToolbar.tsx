// SCR-LABEL-001 좌측 세로 도구바 (mock 정합 — 아이콘 only, w-14).
//
// 도구: 선택(S) / 바운딩박스(B) / 폴리곤(P) / SAM분할(G) / SAM추적(T)
//       / [구분선] / 삭제(Del) / 실행취소(Ctrl+Z) / [구분선] / 저장(Ctrl+S)

import {
  MousePointer2,
  Pentagon,
  RotateCcw,
  Route,
  Save,
  Sparkles,
  Square,
  Trash2,
} from 'lucide-react';

import { cn } from '@/lib/cn';
import { useLabelStore } from '@/stores/useLabelStore';

import { ToolType } from '../types';

interface DarkToolbarProps {
  onSave: () => void;
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
}

interface DividerItem {
  kind: 'divider';
}

type Item = ToolItem | ActionItem | DividerItem;

export function DarkToolbar({ onSave }: DarkToolbarProps) {
  const activeTool = useLabelStore((s) => s.activeTool);
  const setActiveTool = useLabelStore((s) => s.setActiveTool);
  const undo = useLabelStore((s) => s.undo);
  const removeLabel = useLabelStore((s) => s.removeLabel);
  const selectedId = useLabelStore((s) => s.selectedLabelId);

  const handleDelete = () => {
    if (selectedId) removeLabel(selectedId);
  };

  const items: Item[] = [
    { kind: 'tool', tool: ToolType.SELECT, icon: MousePointer2, label: '선택', shortcut: 'S' },
    { kind: 'tool', tool: ToolType.BBOX, icon: Square, label: '바운딩박스', shortcut: 'B' },
    { kind: 'tool', tool: ToolType.POLYGON, icon: Pentagon, label: '폴리곤', shortcut: 'P' },
    { kind: 'tool', tool: ToolType.SAM_SEGMENT, icon: Sparkles, label: 'SAM 분할', shortcut: 'G' },
    { kind: 'tool', tool: ToolType.TRACK, icon: Route, label: 'SAM 추적', shortcut: 'T' },
    { kind: 'divider' },
    { kind: 'action', icon: Trash2, label: '삭제', shortcut: 'Del', action: handleDelete },
    { kind: 'action', icon: RotateCcw, label: '실행취소', shortcut: 'Ctrl+Z', action: undo },
    { kind: 'divider' },
    { kind: 'action', icon: Save, label: '저장', shortcut: 'Ctrl+S', action: onSave },
  ];

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
        const Icon = item.icon;
        const isActive = item.kind === 'tool' && activeTool === item.tool;
        const handleClick =
          item.kind === 'action' ? item.action : () => setActiveTool(item.tool);

        return (
          <div key={idx} className="relative group">
            <button
              type="button"
              onClick={handleClick}
              aria-label={item.label}
              aria-pressed={isActive}
              className={cn(
                'w-10 h-10 rounded-lg flex items-center justify-center transition-colors',
                isActive
                  ? 'bg-blue-600 text-white'
                  : 'text-gray-300 hover:bg-gray-700 hover:text-white',
              )}
            >
              <Icon size={18} />
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
