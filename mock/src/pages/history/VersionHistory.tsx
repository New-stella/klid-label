import { useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { ArrowLeft, GitCommit, RotateCcw } from 'lucide-react';
import { useFetch } from '../../api/queries';
import { api } from '../../api/client';
import type { HistoryCommit } from '../../api/types';
import { Badge } from '../../components/ui/Badge';
import { Button } from '../../components/ui/Button';
import { Skeleton } from '../../components/ui/Skeleton';
import { DiffViewer } from '../../components/history/DiffViewer';
import { RollbackModal } from '../../components/history/RollbackModal';
import { useToast } from '../../components/common/Toast';

function formatTime(iso: string): string {
  return new Date(iso).toLocaleString('ko-KR', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  });
}

export function VersionHistory() {
  const { id: videoId } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const { showToast } = useToast();

  const [selectedHash, setSelectedHash] = useState<string | null>(null);
  const [checkedHashes, setCheckedHashes] = useState<string[]>([]);
  const [showRollback, setShowRollback] = useState(false);

  const { data: commits, isLoading, refetch } = useFetch<HistoryCommit[]>(
    `/history/videos/${videoId ?? ''}`,
  );

  const latestHash = commits?.[0]?.hash ?? null;

  // The "from" commit is the earlier one and "to" is the more recent one for diff
  const diffFrom: string | undefined =
    checkedHashes.length === 2 ? (checkedHashes[1] ?? undefined) : undefined;
  const diffTo: string | undefined =
    checkedHashes.length === 2 ? (checkedHashes[0] ?? undefined) : undefined;

  // When a single commit is selected (no comparison mode), show that commit vs previous
  const singleDiffFrom =
    checkedHashes.length === 0 && selectedHash
      ? (() => {
          const idx = commits?.findIndex((c) => c.hash === selectedHash) ?? -1;
          return commits?.[idx + 1]?.hash;
        })()
      : undefined;
  const singleDiffTo =
    checkedHashes.length === 0 && selectedHash ? selectedHash : undefined;

  const activeFrom = checkedHashes.length === 2 ? diffFrom : singleDiffFrom;
  const activeTo = checkedHashes.length === 2 ? diffTo : singleDiffTo;

  // Rollback target: the single selected commit (only when not in comparison mode)
  const rollbackHash =
    checkedHashes.length === 0 && selectedHash && selectedHash !== latestHash
      ? selectedHash
      : null;

  const handleCheck = (hash: string, checked: boolean) => {
    if (checked) {
      setCheckedHashes((prev) => (prev.length < 2 ? [...prev, hash] : [prev[1] ?? hash, hash]));
    } else {
      setCheckedHashes((prev) => prev.filter((h) => h !== hash));
    }
    setSelectedHash(null);
  };

  const handleSelect = (hash: string) => {
    if (checkedHashes.length > 0) return;
    setSelectedHash((prev) => (prev === hash ? null : hash));
  };

  const handleRollback = async (reason: string) => {
    if (!videoId || !selectedHash) return;
    await api.post(`/history/videos/${videoId}/rollback`, {
      hash: selectedHash,
      reason,
    });
    showToast('롤백이 완료되었습니다.', 'success');
    refetch();
    setSelectedHash(null);
  };

  if (isLoading) {
    return (
      <div className="p-6 space-y-4">
        <Skeleton height="2rem" width="50%" />
        <div className="grid grid-cols-[400px_1fr] gap-6">
          <div className="space-y-3">
            {Array.from({ length: 7 }, (_, i) => (
              <Skeleton key={i} height="5rem" />
            ))}
          </div>
          <Skeleton height="24rem" />
        </div>
      </div>
    );
  }

  const commitList = commits ?? [];

  return (
    <div className="p-6 space-y-5">
      {/* Header */}
      <div className="flex items-center gap-3">
        <button
          onClick={() => navigate(-1)}
          className="p-2 rounded-lg hover:bg-gray-100 text-gray-500 transition-colors"
          aria-label="뒤로가기"
        >
          <ArrowLeft size={18} />
        </button>
        <div>
          <h1 className="text-xl font-bold text-gray-900">버전 관리</h1>
          <p className="text-sm text-gray-500 mt-0.5">{videoId}</p>
        </div>
      </div>

      <div className="grid grid-cols-[400px_1fr] gap-6 items-start">
        {/* Left — commit list */}
        <div className="bg-white border border-gray-200 rounded-lg overflow-hidden">
          <div className="px-4 py-3 border-b border-gray-100 flex items-center justify-between">
            <h2 className="text-sm font-semibold text-gray-700 flex items-center gap-1.5">
              <GitCommit size={15} className="text-gray-400" />
              커밋 목록
              <Badge tone="neutral" size="sm">
                {commitList.length}
              </Badge>
            </h2>
            {checkedHashes.length === 2 && (
              <span className="text-xs text-blue-600 font-medium">2개 선택됨</span>
            )}
          </div>

          <ul className="divide-y divide-gray-100 max-h-[600px] overflow-y-auto">
            {commitList.map((commit, idx) => {
              const isSelected = selectedHash === commit.hash;
              const isChecked = checkedHashes.includes(commit.hash);
              const isLatest = idx === 0;

              return (
                <li
                  key={commit.hash}
                  className={[
                    'px-4 py-3 transition-colors',
                    isSelected ? 'bg-blue-50 border-l-2 border-blue-500' : 'hover:bg-gray-50',
                  ].join(' ')}
                >
                  <div className="flex items-start gap-2">
                    <input
                      type="checkbox"
                      checked={isChecked}
                      onChange={(e) => handleCheck(commit.hash, e.target.checked)}
                      className="mt-0.5 shrink-0 accent-blue-600"
                      aria-label={`커밋 ${commit.hash.substring(0, 7)} 선택`}
                    />
                    <button
                      className="flex-1 text-left min-w-0"
                      onClick={() => handleSelect(commit.hash)}
                      disabled={checkedHashes.length > 0}
                    >
                      <div className="flex items-center gap-2 mb-0.5 flex-wrap">
                        <code className="text-xs font-mono text-blue-700 bg-blue-50 px-1.5 py-0.5 rounded">
                          {commit.hash.substring(0, 7)}
                        </code>
                        {isLatest && (
                          <Badge tone="success" size="sm">
                            최신
                          </Badge>
                        )}
                      </div>
                      <p className="text-sm text-gray-800 truncate">{commit.message}</p>
                      <p className="text-xs text-gray-400 mt-0.5">
                        {commit.authorName} · {formatTime(commit.committedAt)}
                      </p>
                    </button>
                  </div>
                </li>
              );
            })}
          </ul>

          {/* Diff compare button */}
          {checkedHashes.length === 2 && (
            <div className="px-4 py-3 border-t border-gray-100 bg-gray-50">
              <Button
                variant="primary"
                size="sm"
                className="w-full"
                onClick={() => {
                  /* DiffViewer auto-updates via activeFrom/activeTo */
                }}
              >
                diff 비교
              </Button>
            </div>
          )}
        </div>

        {/* Right — diff viewer */}
        <div className="bg-white border border-gray-200 rounded-lg p-5 space-y-4 min-h-64">
          <div className="flex items-center justify-between">
            <h2 className="text-sm font-semibold text-gray-700">변경 내용 (Diff)</h2>
            {rollbackHash && (
              <Button
                variant="secondary"
                size="sm"
                leftIcon={RotateCcw}
                onClick={() => setShowRollback(true)}
              >
                이 버전으로 롤백
              </Button>
            )}
          </div>

          <DiffViewer
            videoId={videoId ?? ''}
            from={activeFrom}
            to={activeTo}
          />
        </div>
      </div>

      {/* Rollback modal */}
      <RollbackModal
        open={showRollback}
        hash={rollbackHash ?? ''}
        onClose={() => setShowRollback(false)}
        onConfirm={handleRollback}
      />
    </div>
  );
}

export default VersionHistory;
