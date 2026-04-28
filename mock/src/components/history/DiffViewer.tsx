import { useFetch } from '../../api/queries';
import type { LabelObject } from '../../api/types';
import { Skeleton } from '../ui/Skeleton';
import { Badge } from '../ui/Badge';

interface DiffViewerProps {
  from?: string;
  to?: string;
  videoId: string;
}

interface DiffResult {
  added: LabelObject[];
  removed: LabelObject[];
  modified: LabelObject[];
  from?: string;
  to?: string;
}

function bboxSummary(obj: LabelObject): string {
  if (obj.bbox) {
    const { x, y, w, h } = obj.bbox;
    return `x:${Math.round(x)} y:${Math.round(y)} w:${Math.round(w)} h:${Math.round(h)}`;
  }
  if (obj.points && obj.points.length >= 2) {
    const [px, py] = obj.points;
    return `pt(${Math.round(px ?? 0)}, ${Math.round(py ?? 0)}) …${Math.floor(obj.points.length / 2)}점`;
  }
  return '좌표 없음';
}

interface SectionProps {
  title: string;
  items: LabelObject[];
  tone: 'success' | 'danger' | 'warning';
  prefix: string;
  bgClass: string;
  borderClass: string;
}

function DiffSection({ title, items, tone, prefix, bgClass, borderClass }: SectionProps) {
  return (
    <div>
      <div className="flex items-center gap-2 mb-2">
        <span className="text-sm font-semibold text-gray-700">{title}</span>
        <Badge tone={tone} size="sm">
          {items.length}건
        </Badge>
      </div>
      {items.length === 0 ? (
        <p className="text-xs text-gray-400 py-2 pl-2">변경 없음</p>
      ) : (
        <ul className={`rounded-lg border ${borderClass} divide-y divide-gray-100 overflow-hidden`}>
          {items.map((obj) => (
            <li key={obj.id} className={`${bgClass} px-3 py-2 flex items-center justify-between gap-4`}>
              <div className="flex items-center gap-2 min-w-0">
                <span className="text-xs font-mono font-bold opacity-70 shrink-0">{prefix}</span>
                <span
                  className="w-3 h-3 rounded-sm shrink-0 border border-black/10"
                  style={{ background: obj.color }}
                />
                <span className="text-sm font-medium text-gray-800 truncate">{obj.labelName}</span>
                <span className="text-xs text-gray-500 font-mono truncate">{bboxSummary(obj)}</span>
              </div>
              <div className="shrink-0 text-xs text-gray-500">
                신뢰도 {(obj.confidence * 100).toFixed(0)}%
              </div>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

export function DiffViewer({ from, to, videoId }: DiffViewerProps) {
  const params: Record<string, unknown> = {};
  if (from) params['from'] = from;
  if (to) params['to'] = to;

  const { data, isLoading, error } = useFetch<DiffResult>(
    `/history/videos/${videoId}/diff`,
    from && to ? params : undefined,
  );

  if (!from || !to) {
    return (
      <div className="flex items-center justify-center h-32 text-gray-400 text-sm">
        커밋을 선택하거나 두 커밋을 체크하여 diff를 확인하세요.
      </div>
    );
  }

  if (isLoading) {
    return (
      <div className="space-y-3">
        <Skeleton height="1.2rem" width="30%" />
        <Skeleton height="6rem" />
        <Skeleton height="4rem" />
      </div>
    );
  }

  if (error || !data) {
    return (
      <div className="flex items-center justify-center h-32 text-red-400 text-sm">
        diff를 불러오는데 실패했습니다.
      </div>
    );
  }

  return (
    <div className="space-y-5">
      <div className="text-xs text-gray-400 font-mono bg-gray-50 rounded px-3 py-1.5">
        {from.substring(0, 7)} → {to.substring(0, 7)}
      </div>

      <DiffSection
        title="추가"
        items={data.added}
        tone="success"
        prefix="+"
        bgClass="bg-green-50"
        borderClass="border-green-200"
      />

      <DiffSection
        title="삭제"
        items={data.removed}
        tone="danger"
        prefix="−"
        bgClass="bg-red-50"
        borderClass="border-red-200"
      />

      <DiffSection
        title="수정"
        items={data.modified}
        tone="warning"
        prefix="~"
        bgClass="bg-yellow-50"
        borderClass="border-yellow-200"
      />
    </div>
  );
}

export default DiffViewer;
