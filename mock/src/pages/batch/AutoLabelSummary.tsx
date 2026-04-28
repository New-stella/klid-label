import type { VideoDto, LabelObject } from '../../api/types';

interface AutoLabelSummaryProps {
  video: VideoDto;
  labels: LabelObject[];
}

interface ConfidenceBucket {
  label: string;
  count: number;
  color: string;
}

interface LabelCount {
  code: string;
  name: string;
  count: number;
  color: string;
}

function buildConfidenceBuckets(labels: LabelObject[]): ConfidenceBucket[] {
  const high = labels.filter((l) => l.confidence >= 0.9).length;
  const mid = labels.filter((l) => l.confidence >= 0.7 && l.confidence < 0.9).length;
  const low = labels.filter((l) => l.confidence < 0.7).length;
  return [
    { label: '0.9+', count: high, color: 'bg-green-500' },
    { label: '0.7–0.9', count: mid, color: 'bg-yellow-400' },
    { label: '<0.7', count: low, color: 'bg-red-400' },
  ];
}

function buildLabelCounts(labels: LabelObject[]): LabelCount[] {
  const map = new Map<string, LabelCount>();
  for (const l of labels) {
    const existing = map.get(l.labelCode);
    if (existing) {
      existing.count++;
    } else {
      map.set(l.labelCode, { code: l.labelCode, name: l.labelName, count: 1, color: l.color });
    }
  }
  return Array.from(map.values()).sort((a, b) => b.count - a.count);
}

function ConfidenceHistogram({ buckets }: { buckets: ConfidenceBucket[] }) {
  const max = Math.max(...buckets.map((b) => b.count), 1);
  return (
    <div className="flex items-end gap-4 h-24 mt-2">
      {buckets.map((b) => (
        <div key={b.label} className="flex flex-col items-center gap-1 flex-1">
          <span className="text-xs font-semibold text-gray-700 tabular-nums">{b.count}</span>
          <div className="w-full flex items-end" style={{ height: '60px' }}>
            <div
              className={[b.color, 'w-full rounded-t transition-all'].join(' ')}
              style={{ height: `${Math.round((b.count / max) * 60)}px` }}
            />
          </div>
          <span className="text-xs text-gray-500">{b.label}</span>
        </div>
      ))}
    </div>
  );
}

function LabelBarChart({ counts }: { counts: LabelCount[] }) {
  const max = Math.max(...counts.map((c) => c.count), 1);
  return (
    <div className="space-y-2 mt-2">
      {counts.slice(0, 10).map((c) => (
        <div key={c.code} className="flex items-center gap-2">
          <span className="text-xs text-gray-600 w-20 truncate shrink-0">{c.name}</span>
          <div className="flex-1 bg-gray-100 rounded-full h-3 overflow-hidden">
            <div
              className="h-full rounded-full transition-all"
              style={{
                width: `${Math.round((c.count / max) * 100)}%`,
                backgroundColor: c.color,
              }}
            />
          </div>
          <span className="text-xs tabular-nums text-gray-500 w-8 text-right">{c.count}</span>
        </div>
      ))}
    </div>
  );
}

export function AutoLabelSummary({ video, labels }: AutoLabelSummaryProps) {
  const autoLabels = labels.filter((l) => l.createdBy === 'auto');
  const confidenceBuckets = buildConfidenceBuckets(autoLabels);
  const labelCounts = buildLabelCounts(labels);

  const totalLabels = labels.length;
  const autoCount = autoLabels.length;
  const autoRate = totalLabels > 0 ? ((autoCount / totalLabels) * 100).toFixed(1) : '0';

  return (
    <div className="space-y-6">
      {/* 처리 정보 */}
      <div>
        <h4 className="text-sm font-semibold text-gray-700 mb-3">처리 정보</h4>
        <div className="grid grid-cols-2 sm:grid-cols-3 gap-3">
          {[
            { label: '총 라벨 수', value: totalLabels.toLocaleString('ko-KR') },
            { label: '오토라벨 수', value: autoCount.toLocaleString('ko-KR') },
            { label: '오토라벨 비율', value: `${autoRate}%` },
            { label: '처리 상태', value: video.batchStatus },
            { label: '비식별 여부', value: video.deidentified ? '처리됨' : '미처리' },
          ].map((item) => (
            <div key={item.label} className="bg-gray-50 rounded-lg p-3">
              <p className="text-xs text-gray-500 mb-0.5">{item.label}</p>
              <p className="text-sm font-semibold text-gray-800">{item.value}</p>
            </div>
          ))}
        </div>
      </div>

      {/* 신뢰도 분포 */}
      <div>
        <h4 className="text-sm font-semibold text-gray-700 mb-1">신뢰도 분포 (오토라벨)</h4>
        <p className="text-xs text-gray-400 mb-2">오토라벨 {autoCount}건 기준</p>
        {autoCount === 0 ? (
          <p className="text-sm text-gray-400 py-4 text-center">오토라벨 데이터가 없습니다.</p>
        ) : (
          <ConfidenceHistogram buckets={confidenceBuckets} />
        )}
      </div>

      {/* 라벨별 분포 */}
      <div>
        <h4 className="text-sm font-semibold text-gray-700 mb-1">라벨별 분포</h4>
        {labelCounts.length === 0 ? (
          <p className="text-sm text-gray-400 py-4 text-center">라벨 데이터가 없습니다.</p>
        ) : (
          <LabelBarChart counts={labelCounts} />
        )}
      </div>
    </div>
  );
}

export default AutoLabelSummary;
