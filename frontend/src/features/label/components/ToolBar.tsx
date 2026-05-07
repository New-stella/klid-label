import { useLabelStore } from '@/stores/useLabelStore';
import { cn } from '@/lib/cn';

import { ToolType } from '../types';

interface ToolButtonProps {
  tool: ToolType;
  label: string;
  shortcut: string;
}

const tools: ToolButtonProps[] = [
  { tool: ToolType.SELECT, label: '선택', shortcut: 'S' },
  { tool: ToolType.BBOX, label: 'BBox', shortcut: 'B' },
  { tool: ToolType.POLYGON, label: 'Polygon', shortcut: 'P' },
  { tool: ToolType.PAN, label: '팬', shortcut: 'H' },
  { tool: ToolType.TRACK, label: 'Track', shortcut: 'T' },
  { tool: ToolType.MASK_BRUSH, label: '브러시', shortcut: 'M' },
  { tool: ToolType.MASK_ERASER, label: '지우개', shortcut: 'X' },
];

/**
 * 라벨링 도구 선택 바.
 */
export function ToolBar() {
  const active = useLabelStore((s) => s.activeTool);
  const setActiveTool = useLabelStore((s) => s.setActiveTool);

  return (
    <div className="flex items-center gap-1 rounded border border-border bg-white p-1" role="toolbar" aria-label="라벨링 도구">
      {tools.map((t) => (
        <button
          key={t.tool}
          type="button"
          onClick={() => setActiveTool(t.tool)}
          aria-pressed={active === t.tool}
          aria-label={`${t.label} (${t.shortcut})`}
          className={cn(
            'rounded px-3 py-1 text-sub',
            active === t.tool
              ? 'bg-primary text-white'
              : 'text-primary hover:bg-bgLight',
          )}
        >
          {t.label}
          <span className="ml-1 text-xs opacity-70">[{t.shortcut}]</span>
        </button>
      ))}
    </div>
  );
}
