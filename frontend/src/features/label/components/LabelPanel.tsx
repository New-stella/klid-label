import { useMemo } from 'react';

import { cn } from '@/lib/cn';
import { useLabelStore } from '@/stores/useLabelStore';

import type { Label } from '../types';
import { resolveLabelDisplayName } from '../utils/labelDisplayName';
import { trackIdToColor } from '../utils/trackColor';

interface LabelPanelProps {
  labels: Label[];
}

/**
 * 라벨 트리 좌측 패널 — 클래스별 그룹 + 객체 카운트.
 *
 * Phase 5: 라벨 행에 좌측 4px 컬러 바(trackId 해시 기반) + trackId 가 있으면 `#{id}` 텍스트 표시.
 */
export function LabelPanel({ labels }: LabelPanelProps) {
  const selectedId = useLabelStore((s) => s.selectedLabelId);
  const selectLabel = useLabelStore((s) => s.selectLabel);

  const grouped = useMemo(() => {
    const map = new Map<string, Label[]>();
    for (const l of labels) {
      const arr = map.get(l.className) ?? [];
      arr.push(l);
      map.set(l.className, arr);
    }
    return Array.from(map.entries());
  }, [labels]);

  return (
    <aside
      className="flex h-full w-60 flex-col gap-2 overflow-y-auto border-r border-border bg-white p-3"
      aria-label="라벨 트리"
    >
      <h3 className="text-sub font-semibold text-primary">라벨</h3>
      {grouped.length === 0 && <p className="text-sub text-neutral">라벨 없음</p>}
      {grouped.map(([cls, items]) => (
        <div key={cls} className="flex flex-col gap-1">
          <div className="flex items-center justify-between text-sub text-primary">
            {/* 표시명은 공용 함수 — 마스터 등록명(cls) 그대로. */}
            <span>{resolveLabelDisplayName(cls)}</span>
            <span className="text-caption text-neutral">({items.length})</span>
          </div>
          <ul className="flex flex-col gap-0.5 pl-3">
            {items.map((item) => {
              const barColor = trackIdToColor(item.trackId);
              return (
                <li key={item.id} className="flex items-stretch gap-1">
                  <span
                    data-testid="label-color-bar"
                    aria-hidden="true"
                    className="inline-block w-1 shrink-0 rounded-sm"
                    style={{ backgroundColor: barColor }}
                  />
                  <button
                    type="button"
                    onClick={() => selectLabel(item.id)}
                    className={cn(
                      'w-full rounded px-2 py-0.5 text-left text-caption',
                      selectedId === item.id
                        ? 'bg-primary text-white'
                        : 'text-neutral hover:bg-bgLight',
                    )}
                  >
                    #{String(item.id ?? '').slice(0, 8)} · {item.shape?.type ?? '-'}
                    {item.lblSrcCd === 'INTERPOLATED' ? (
                      <span className="ml-1" aria-label="보간 라벨">🔗</span>
                    ) : (
                      item.source !== 'MANUAL' && <span className="ml-1">🤖</span>
                    )}
                    {item.trackId && (
                      <span className="ml-1 text-[10px] text-gray-500">#{item.trackId}</span>
                    )}
                  </button>
                </li>
              );
            })}
          </ul>
        </div>
      ))}
    </aside>
  );
}
