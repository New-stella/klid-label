import { EmptyState } from '@/components/common/EmptyState';

import type { DiffType, LabelDiff } from '../types';

interface DiffViewerProps {
  diffs: LabelDiff[];
  /** 변경 0건일 때 제목. 미지정 시 두 버전 비교 기준 문구. */
  emptyTitle?: string;
  /** 변경 0건일 때 설명. 미지정 시 두 버전 비교 기준 문구. */
  emptyMessage?: string;
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
 * 라벨 변경 사항을 색상 분리하여 표시. (두 버전 비교 / 버전 ↔ 현재 작업본 비교 공용)
 * - ADDED: green (추가됨)
 * - MODIFIED: yellow (모양/위치 변경)
 * - REMOVED: red (삭제됨)
 *
 * 색상만으로 종류를 전달하지 않도록 [추가]/[수정]/[삭제] 텍스트를 함께 표기한다(a11y).
 */
export function DiffViewer({
  diffs,
  // 기본값 = 두 버전 비교 기준 문구(기존 호출부 무변경). 비교 축이 다르면 호출부가 덮어쓴다. [req: R4]
  emptyTitle = '변경된 라벨이 없습니다',
  emptyMessage = '두 버전이 동일합니다.',
}: DiffViewerProps) {
  if (diffs.length === 0) {
    return <EmptyState title={emptyTitle} message={emptyMessage} />;
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
            <div className="text-caption">
              <span className="font-medium">이전:</span> {formatShape(d.before)}
            </div>
          )}
          {d.after && (
            <div className="text-caption">
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
  if (shape.type === 'KEYPOINT') {
    return `KEYPOINT ${shape.keypoints.length}관절`;
  }
  return `MASK ${shape.width ?? '-'}x${shape.height ?? '-'}`;
}
