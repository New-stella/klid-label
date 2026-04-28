import { MousePointer2, Square, Pentagon, Trash2, RotateCcw, Save } from 'lucide-react';
import { useLabelStore, type Tool } from '../../store/labelStore';

interface Props {
  onSave: () => void;
}

interface ToolButton {
  tool?: Tool;
  icon: React.ElementType;
  label: string;
  shortcut: string;
  action?: () => void;
  isDivider?: false;
}

interface DividerItem {
  isDivider: true;
}

type Item = ToolButton | DividerItem;

export function Toolbar({ onSave }: Props) {
  const tool = useLabelStore((s) => s.tool);
  const setTool = useLabelStore((s) => s.setTool);
  const removeSelected = useLabelStore((s) => s.removeSelected);
  const undo = useLabelStore((s) => s.undo);

  const items: Item[] = [
    { tool: 'select', icon: MousePointer2, label: '선택', shortcut: 'V' },
    { tool: 'bbox', icon: Square, label: '바운딩박스', shortcut: 'B' },
    { tool: 'polygon', icon: Pentagon, label: '폴리곤', shortcut: 'P' },
    { isDivider: true },
    { icon: Trash2, label: '삭제', shortcut: 'Del', action: removeSelected },
    { icon: RotateCcw, label: '실행취소', shortcut: 'Ctrl+Z', action: undo },
    { isDivider: true },
    { icon: Save, label: '저장', shortcut: 'Ctrl+S', action: onSave },
  ];

  return (
    <div className="flex flex-col items-center gap-1 p-2 bg-gray-800 border-r border-gray-700 w-14">
      {items.map((item, idx) => {
        if ('isDivider' in item && item.isDivider) {
          return <div key={idx} className="w-8 h-px bg-gray-600 my-1" />;
        }
        const btn = item as ToolButton;
        const Icon = btn.icon;
        const isActive = btn.tool && tool === btn.tool;
        const handleClick = btn.action ?? (btn.tool ? () => setTool(btn.tool as Tool) : undefined);

        return (
          <div key={idx} className="relative group">
            <button
              onClick={handleClick}
              className={[
                'w-10 h-10 rounded-lg flex items-center justify-center transition-colors',
                isActive
                  ? 'bg-blue-600 text-white'
                  : 'text-gray-300 hover:bg-gray-700 hover:text-white',
              ].join(' ')}
              aria-label={btn.label}
            >
              <Icon size={18} />
            </button>
            {/* Tooltip */}
            <div className="absolute left-12 top-1/2 -translate-y-1/2 z-50 pointer-events-none opacity-0 group-hover:opacity-100 transition-opacity">
              <div className="bg-gray-900 text-white text-xs rounded px-2 py-1 whitespace-nowrap border border-gray-700 shadow-lg">
                {btn.label}
                <span className="ml-2 text-gray-400">{btn.shortcut}</span>
              </div>
            </div>
          </div>
        );
      })}
    </div>
  );
}
