import { useState } from 'react';
import { ChevronDown, ChevronRight, Trash2 } from 'lucide-react';
import { useLabelStore } from '../../store/labelStore';
import type { LabelObject } from '../../api/types';

export function ObjectTree() {
  const frames = useLabelStore((s) => s.frames);
  const currentFrame = useLabelStore((s) => s.currentFrame);
  const selectedId = useLabelStore((s) => s.selectedId);
  const selectObject = useLabelStore((s) => s.selectObject);
  const removeObject = useLabelStore((s) => s.removeObject);

  const objects = frames[currentFrame] ?? [];

  // Group by labelCode
  const groups: Record<string, LabelObject[]> = {};
  for (const obj of objects) {
    if (!groups[obj.labelCode]) groups[obj.labelCode] = [];
    groups[obj.labelCode].push(obj);
  }

  const [collapsed, setCollapsed] = useState<Record<string, boolean>>({});

  const toggleGroup = (code: string) => {
    setCollapsed((prev) => ({ ...prev, [code]: !prev[code] }));
  };

  if (objects.length === 0) {
    return (
      <div className="p-4 text-xs text-gray-400 text-center">
        이 프레임에 객체가 없습니다
      </div>
    );
  }

  return (
    <div className="overflow-y-auto flex-1 text-sm">
      {Object.entries(groups).map(([code, items]) => {
        const isCollapsed = collapsed[code] ?? false;
        const sample = items[0];

        return (
          <div key={code}>
            {/* Group header */}
            <button
              onClick={() => toggleGroup(code)}
              className="w-full flex items-center gap-1.5 px-3 py-1.5 hover:bg-gray-700 text-gray-200 text-xs font-semibold"
            >
              {isCollapsed ? <ChevronRight size={12} /> : <ChevronDown size={12} />}
              <span
                className="w-2.5 h-2.5 rounded-full shrink-0"
                style={{ backgroundColor: sample.color }}
              />
              {sample.labelName}
              <span className="ml-auto text-gray-400">({items.length})</span>
            </button>

            {/* Items */}
            {!isCollapsed &&
              items.map((obj, idx) => {
                const isSelected = selectedId === obj.id;
                return (
                  <div
                    key={obj.id}
                    className={[
                      'flex items-center gap-2 pl-7 pr-2 py-1 cursor-pointer text-xs group',
                      isSelected
                        ? 'bg-blue-600/30 text-white'
                        : 'text-gray-300 hover:bg-gray-700',
                    ].join(' ')}
                    onClick={() => selectObject(obj.id)}
                  >
                    <span>{obj.createdBy === 'auto' ? '🤖' : '✏️'}</span>
                    <span className="flex-1 truncate">
                      {obj.labelName} #{idx + 1}
                    </span>
                    <span className="text-gray-500 text-xs">{obj.type}</span>
                    <button
                      onClick={(e) => {
                        e.stopPropagation();
                        removeObject(obj.id);
                      }}
                      className="opacity-0 group-hover:opacity-100 text-gray-400 hover:text-red-400 transition-opacity"
                      aria-label="삭제"
                    >
                      <Trash2 size={12} />
                    </button>
                  </div>
                );
              })}
          </div>
        );
      })}
    </div>
  );
}
