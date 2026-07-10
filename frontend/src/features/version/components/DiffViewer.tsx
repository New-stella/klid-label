import { EmptyState } from '@/components/common/EmptyState';

import type { DiffType, LabelDiff } from '../types';

interface DiffViewerProps {
  diffs: LabelDiff[];
}

const typeStyle: Record<DiffType, string> = {
  ADDED: 'bg-success/10 border-l-4 border-success text-success',
  MODIFIED: 'bg-warning/10 border-l-4 border-warning text-warning',
  REMOVED: 'bg-danger/10 border-l-4 border-danger text-danger',
};

const typeLabel: Record<DiffType, string> = {
  ADDED: '추가',
  MODIFIED: '수정',
  REMOVED: '삭제',
};

/**
 * 두 버전 간 라벨 변경 사항을 색상 분리하여 표시.
 * - ADDED: green (추가됨)
 * - MODIFIED: yellow (모양/위치 변경)
 * - REMOVED: red (삭제됨)
 */
export function DiffViewer({ diffs }: DiffViewerProps) {
  if (diffs.length === 0) {
    return <EmptyState title="변경된 라벨이 없습니다" message="두 버전이 동일합니다." />;
  }

  return (
    <ul className="flex flex-col gap-2">
      {diffs.map((d) => (
        <li
          key={`${d.type}-${d.objectId}-${d.frameId}`}
          data-testid={`diff-row-${d.type}-${d.objectId}`}
          className={`flex flex-col gap-1 rounded p-3 ${typeStyle[d.type]}`}
        >
          <div className="flex items-center gap-2 text-sub font-medium">
            <span>[{typeLabel[d.type]}]</span>
            <span>프레임 {d.frameId}</span>
            <code className="font-mono">{d.objectId}</code>
          </div>
          {d.before && (
            <div className="text-xs">
              <span className="font-medium">이전:</span> {formatShape(d.before)}
            </div>
          )}
          {d.after && (
            <div className="text-xs">
              <span className="font-medium">이후:</span> {formatShape(d.after)}
            </div>
          )}
        </li>
      ))}
    </ul>
  );
}

function formatShape(shape: LabelDiff['before'] | LabelDiff['after']): string {
  if (!shape) return '-';
  if (shape.type === 'BBOX') {
    return `BBOX (${shape.left},${shape.top})~(${shape.right},${shape.bottom})`;
  }
  if (shape.type === 'POLYGON') {
    return `POLYGON ${shape.points.length / 2}점`;
  }
  return `MASK ${shape.width ?? '-'}x${shape.height ?? '-'}`;
}
