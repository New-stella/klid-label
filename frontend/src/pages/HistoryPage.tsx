import { useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { ArrowLeft, GitCommit, RotateCcw } from 'lucide-react';

import { Button } from '@/components/common/Button';
import { ErrorState } from '@/components/common/ErrorState';
import { Spinner } from '@/components/common/Spinner';
import { DiffViewer } from '@/features/version/components/DiffViewer';
import { RollbackConfirmModal } from '@/features/version/components/RollbackConfirmModal';
import { useDiff } from '@/features/version/hooks/useDiff';
import { useVersions } from '@/features/version/hooks/useVersions';
import { Role } from '@/lib/api/types';
import { useAuthStore } from '@/stores/useAuthStore';

function formatTime(iso: string): string {
  return new Date(iso).toLocaleString('ko-KR', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  });
}

/**
 * SCR-HIST-001 버전 목록·diff 페이지.
 *
 * 권한:
 * - 조회(WORKER+REVIEWER): 버전 목록 + diff 가능
 * - 롤백(REVIEWER만): 롤백 트리거 노출 (BE에서도 403으로 검증)
 *
 * 보안: 사용자 입력 commit hash는 BE에서 SHA hex 검증. FE는 단순 전달.
 */
export function HistoryPage() {
  const { videoId } = useParams<{ videoId: string }>();
  const navigate = useNavigate();
  const numericId = videoId ? Number(videoId) : NaN;
  const role = useAuthStore((s) => s.claims?.role ?? null);
  const canRollback = role === Role.REVIEWER;

  const { data: versions, isLoading, error } = useVersions(
    Number.isFinite(numericId) ? numericId : undefined,
  );

  const [selectedHash, setSelectedHash] = useState<string | null>(null);
  const [checkedHashes, setCheckedHashes] = useState<string[]>([]);
  const [showRollback, setShowRollback] = useState(false);

  const versionList = versions ?? [];
  const latestHash = versionList[0]?.commitSha ?? null;

  // 2개 체크 시: 두 커밋 직접 비교
  const diffFrom: string | undefined =
    checkedHashes.length === 2 ? checkedHashes[1] : undefined;
  const diffTo: string | undefined =
    checkedHashes.length === 2 ? checkedHashes[0] : undefined;

  // 단일 선택 시: 해당 커밋 vs 직전(목록 newest-first → idx+1) 커밋
  const singleDiffFrom =
    checkedHashes.length === 0 && selectedHash
      ? (() => {
          const idx = versionList.findIndex((v) => v.commitSha === selectedHash);
          return idx >= 0 ? versionList[idx + 1]?.commitSha : undefined;
        })()
      : undefined;
  const singleDiffTo =
    checkedHashes.length === 0 && selectedHash ? selectedHash : undefined;

  const activeFrom = checkedHashes.length === 2 ? diffFrom : singleDiffFrom;
  const activeTo = checkedHashes.length === 2 ? diffTo : singleDiffTo;

  const diffQuery = useDiff(
    Number.isFinite(numericId) ? numericId : undefined,
    activeTo,
    activeFrom,
  );

  // 롤백 대상: 단일 선택 + 최신 아닌 커밋
  const rollbackHash =
    checkedHashes.length === 0 && selectedHash && selectedHash !== latestHash
      ? selectedHash
      : null;
  const rollbackVersion = rollbackHash
    ? versionList.find((v) => v.commitSha === rollbackHash) ?? null
    : null;

  const handleCheck = (hash: string, checked: boolean) => {
    if (checked) {
      setCheckedHashes((prev) =>
        prev.length < 2 ? [...prev, hash] : [prev[1] ?? hash, hash],
      );
    } else {
      setCheckedHashes((prev) => prev.filter((h) => h !== hash));
    }
    setSelectedHash(null);
  };

  const handleSelect = (hash: string) => {
    if (checkedHashes.length > 0) return;
    setSelectedHash((prev) => (prev === hash ? null : hash));
  };

  if (Number.isNaN(numericId)) {
    return <ErrorState title="잘못된 영상 ID" />;
  }

  if (isLoading) {
    return (
      <div className="flex h-full items-center justify-center py-10">
        <Spinner label="버전 이력 로딩" />
      </div>
    );
  }

  if (error) {
    return <ErrorState title="버전 이력 조회 실패" message={error.message} />;
  }

  return (
    <div className="space-y-5 p-6" data-testid="history-page">
      {/* Header */}
      <div className="flex items-center gap-3">
        <button
          onClick={() => navigate(-1)}
          className="rounded-lg p-2 text-gray-500 transition-colors hover:bg-gray-100"
          aria-label="뒤로가기"
        >
          <ArrowLeft size={18} />
        </button>
        <div>
          <h1 className="text-xl font-bold text-gray-900">버전 관리</h1>
          <p className="mt-0.5 text-sm text-gray-500">{videoId}</p>
        </div>
      </div>

      <div className="grid grid-cols-[400px_1fr] items-start gap-6">
        {/* Left — commit list */}
        <div className="overflow-hidden rounded-lg border border-gray-200 bg-white">
          <div className="flex items-center justify-between border-b border-gray-100 px-4 py-3">
            <h2 className="flex items-center gap-1.5 text-sm font-semibold text-gray-700">
              <GitCommit size={15} className="text-gray-400" />
              커밋 목록
              <span className="ml-1 inline-flex items-center rounded-full bg-gray-100 px-2 py-0.5 text-xs font-medium text-gray-600">
                {versionList.length}
              </span>
            </h2>
            {checkedHashes.length === 2 && (
              <span className="text-xs font-medium text-blue-600">2개 선택됨</span>
            )}
          </div>

          <ul className="max-h-[600px] divide-y divide-gray-100 overflow-y-auto">
            {versionList.map((commit, idx) => {
              const isSelected = selectedHash === commit.commitSha;
              const isChecked = checkedHashes.includes(commit.commitSha);
              const isLatest = idx === 0 || commit.isCurrent;

              return (
                <li
                  key={commit.commitSha}
                  className={[
                    'px-4 py-3 transition-colors',
                    isSelected
                      ? 'border-l-2 border-blue-500 bg-blue-50'
                      : 'hover:bg-gray-50',
                  ].join(' ')}
                  data-testid={`commit-row-${commit.shortHash}`}
                >
                  <div className="flex items-start gap-2">
                    <input
                      type="checkbox"
                      checked={isChecked}
                      onChange={(e) => handleCheck(commit.commitSha, e.target.checked)}
                      className="mt-0.5 shrink-0 accent-blue-600"
                      aria-label={`커밋 ${commit.shortHash} 선택`}
                    />
                    <button
                      type="button"
                      className="min-w-0 flex-1 text-left disabled:cursor-not-allowed"
                      onClick={() => handleSelect(commit.commitSha)}
                      disabled={checkedHashes.length > 0}
                    >
                      <div className="mb-0.5 flex flex-wrap items-center gap-2">
                        <code className="rounded bg-blue-50 px-1.5 py-0.5 font-mono text-xs text-blue-700">
                          {commit.shortHash}
                        </code>
                        {isLatest && (
                          <span className="inline-flex items-center rounded-full bg-green-100 px-2 py-0.5 text-xs font-medium text-green-700">
                            최신
                          </span>
                        )}
                      </div>
                      <p className="truncate text-sm text-gray-800">{commit.message}</p>
                      <p className="mt-0.5 text-xs text-gray-400">
                        {commit.authorName} · {formatTime(commit.committedAt)}
                      </p>
                    </button>
                  </div>
                </li>
              );
            })}
          </ul>

          {checkedHashes.length === 2 && (
            <div className="border-t border-gray-100 bg-gray-50 px-4 py-3">
              <Button variant="primary" size="sm" fullWidth>
                diff 비교
              </Button>
            </div>
          )}
        </div>

        {/* Right — diff viewer */}
        <div className="min-h-64 space-y-4 rounded-lg border border-gray-200 bg-white p-5">
          <div className="flex items-center justify-between">
            <h2 className="text-sm font-semibold text-gray-700">변경 내용 (Diff)</h2>
            {rollbackVersion && canRollback && (
              <Button
                variant="secondary"
                size="sm"
                onClick={() => setShowRollback(true)}
                data-testid={`rollback-trigger-${rollbackVersion.shortHash}`}
              >
                <RotateCcw size={14} />
                이 버전으로 롤백
              </Button>
            )}
          </div>

          {activeFrom && activeTo ? (
            diffQuery.isLoading ? (
              <Spinner label="diff 로딩" />
            ) : diffQuery.error ? (
              <ErrorState title="diff 조회 실패" message={diffQuery.error.message} />
            ) : (
              <DiffViewer diffs={diffQuery.data ?? []} />
            )
          ) : (
            <p className="text-sm text-gray-500">
              커밋을 선택하거나 두 커밋을 체크하여 diff를 확인하세요.
            </p>
          )}
        </div>
      </div>

      {rollbackVersion && Number.isFinite(numericId) && (
        <RollbackConfirmModal
          open={showRollback}
          commitSha={rollbackVersion.commitSha}
          shortHash={rollbackVersion.shortHash}
          videoId={numericId}
          onClose={() => setShowRollback(false)}
          onSuccess={() => {
            setShowRollback(false);
            setSelectedHash(null);
          }}
        />
      )}
    </div>
  );
}
