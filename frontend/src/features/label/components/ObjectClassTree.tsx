// SCR-LABEL-001 우측 상단 객체 트리 (mock 정합 — 분류별 그룹화 + 펼치기 + bbox/polygon 표시).

import { useMemo, useState } from 'react';
import { ChevronDown, ChevronRight, Trash2 } from 'lucide-react';

import { cn } from '@/lib/cn';
import { useLabelStore } from '@/stores/useLabelStore';

import { getLabelColor, getLabelDisplayName } from '../labelColors';
import type { Label } from '../types';
import { trackIdToColor } from '../utils/trackColor';

interface ObjectClassTreeProps {
  labels: Label[];
}

export function ObjectClassTree({ labels }: ObjectClassTreeProps) {
  const selectedId = useLabelStore((s) => s.selectedLabelId);
  const selectLabel = useLabelStore((s) => s.selectLabel);
  const removeLabel = useLabelStore((s) => s.removeLabel);

  const [collapsed, setCollapsed] = useState<Record<string, boolean>>({});

  const groups = useMemo(() => {
    const map = new Map<string, Label[]>();
    for (const l of labels) {
      const key = l.className || 'UNKNOWN';
      const arr = map.get(key) ?? [];
      arr.push(l);
      map.set(key, arr);
    }
    return Array.from(map.entries());
  }, [labels]);

  if (groups.length === 0) {
    return (
      <div className="p-4 text-xs text-gray-400 text-center">
        이 프레임에 객체가 없습니다
      </div>
    );
  }

  const toggleGroup = (key: string) =>
    setCollapsed((prev) => ({ ...prev, [key]: !prev[key] }));

  return (
    <div className="overflow-y-auto flex-1 text-sm">
      {groups.map(([className, items]) => {
        const isCollapsed = collapsed[className] ?? false;
        const color = getLabelColor(className);
        const displayName = getLabelDisplayName(className);

        return (
          <div key={className}>
            <button
              type="button"
              onClick={() => toggleGroup(className)}
              className="w-full flex items-center gap-1.5 px-3 py-1.5 hover:bg-gray-700 text-gray-200 text-xs font-semibold"
            >
              {isCollapsed ? <ChevronRight size={12} /> : <ChevronDown size={12} />}
              <span
                className="w-2.5 h-2.5 rounded-full shrink-0"
                style={{ backgroundColor: color }}
              />
              {displayName}
              <span className="ml-auto text-gray-400">({items.length})</span>
            </button>

            {!isCollapsed &&
              items.map((obj, idx) => {
                const isSelected = selectedId === obj.id;
                const shapeType = obj.shape?.type ?? '-';
                const isAuto = obj.source !== 'MANUAL';
                const objNumber = obj.trackId ?? idx + 1;
                const barColor = trackIdToColor(obj.trackId);
                return (
                  <div
                    key={obj.id}
                    className={cn(
                      'flex items-stretch gap-2 pl-3 pr-2 py-1 text-xs group',
                      isSelected
                        ? 'bg-blue-600/30 text-white'
                        : 'text-gray-300 hover:bg-gray-700',
                    )}
                  >
                    <span
                      data-testid="label-color-bar"
                      aria-hidden="true"
                      className="inline-block w-1 shrink-0 rounded-sm"
                      style={{ backgroundColor: barColor }}
                    />
                    <button
                      type="button"
                      onClick={() => selectLabel(obj.id)}
                      className="flex-1 flex items-center gap-2 text-left"
                      aria-label={`${displayName} #${objNumber} 선택`}
                    >
                      <span aria-hidden>{isAuto ? '🤖' : '✏️'}</span>
                      <span className="flex-1 truncate">
                        {displayName} #{objNumber}
                      </span>
                      <span className="text-gray-500 text-xs uppercase">{shapeType}</span>
                    </button>
                    <button
                      type="button"
                      onClick={(e) => {
                        e.stopPropagation();
                        removeLabel(obj.id);
                      }}
                      className="opacity-0 group-hover:opacity-100 text-gray-400 hover:text-red-400 transition-opacity"
                      aria-label="객체 삭제"
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
