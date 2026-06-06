// SCR-HIST-001/Inline — 버전 이력 인라인 패널.
//
// HistoryPage 의 본문 UI 를 재사용 가능한 단일 컴포넌트로 추출. 라벨링 화면 우측 슬라이드
// 패널과 별도 페이지(/history/:videoId) 양쪽에서 동일한 UX 를 제공한다.
//
// 권한:
// - 조회(WORKER+REVIEWER): 버전 목록 + diff 가능
// - 롤백(REVIEWER+WORKER): "이 버전으로 롤백" 트리거 노출. WORKER는 본인 배정 프레임만 가능 (BE에서 IDOR 검증)
//
// 보안: 사용자 입력 commit hash 는 BE 에서 SHA hex 검증. FE 는 단순 전달.

import { useEffect, useState } from 'react';
import { GitCommit, RotateCcw, X } from 'lucide-react';

import { Button } from '@/components/common/Button';
import { ErrorState } from '@/components/common/ErrorState';
import { Spinner } from '@/components/common/Spinner';
import { DiffViewer } from '@/features/version/components/DiffViewer';
import { RollbackConfirmModal } from '@/features/version/components/RollbackConfirmModal';
import { useDiff } from '@/features/version/hooks/useDiff';
import { useVersions } from '@/features/version/hooks/useVersions';
import { Role } from '@/lib/api/types';
import { useAuthStore } from '@/stores/useAuthStore';

interface HistoryPanelProps {
  /** 프레임(srcSn) — useVersions/useDiff/롤백에 모두 사용 */
  srcSn: number;
  /** 닫기 버튼 노출 + 콜백. 미지정 시 닫기 버튼 미노출 (라우트 페이지에서 사용) */
  onClose?: () => void;
  /** 라벨링 화면 우측 슬라이드용 다크 테마. 별도 페이지에서는 false (기본). */
  dark?: boolean;
}

function formatTime(iso: string | undefined): string {
  if (!iso) return '-';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '-';
  return d.toLocaleString('ko-KR', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  });
}

export function HistoryPanel({ srcSn, onClose, dark = false }: HistoryPanelProps) {
  const role = useAuthStore((s) => s.claims?.role ?? null);
  const canRollback = role === Role.REVIEWER || role === Role.WORKER;

  const { data: versions, isLoading, error } = useVersions(srcSn);

  const [selectedHash, setSelectedHash] = useState<string | null>(null);
  const [checkedHashes, setCheckedHashes] = useState<string[]>([]);
  const [showRollback, setShowRollback] = useState(false);

  // BE 응답은 Version[] (FE 정합). 방어적으로 비-배열 응답은 빈 목록으로 처리.
  const list = Array.isArray(versions) ? versions : [];
  const latestHash = list[0]?.commitSha ?? null;

  // srcSn 전환 시 선택 상태 리셋 (이전 프레임의 hash 가 잔존하지 않도록)
  useEffect(() => {
    setSelectedHash(null);
    setCheckedHashes([]);
    setShowRollback(false);
  }, [srcSn]);

  const diffFrom: string | undefined =
    checkedHashes.length === 2 ? checkedHashes[1] : undefined;
  const diffTo: string | undefined =
    checkedHashes.length === 2 ? checkedHashes[0] : undefined;

  const singleDiffFrom =
    checkedHashes.length === 0 && selectedHash
      ? (() => {
          const idx = list.findIndex((v) => v.commitSha === selectedHash);
          return idx >= 0 ? list[idx + 1]?.commitSha : undefined;
        })()
      : undefined;
  const singleDiffTo =
    checkedHashes.length === 0 && selectedHash ? selectedHash : undefined;

  const activeFrom = checkedHashes.length === 2 ? diffFrom : singleDiffFrom;
  const activeTo = checkedHashes.length === 2 ? diffTo : singleDiffTo;

  const diffQuery = useDiff(srcSn, activeTo, activeFrom);

  const rollbackHash =
    checkedHashes.length === 0 && selectedHash && selectedHash !== latestHash
      ? selectedHash
      : null;
  const rollbackVersion = rollbackHash
    ? list.find((v) => v.commitSha === rollbackHash) ?? null
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

  // ESC 닫기 (onClose 가 있을 때만)
  useEffect(() => {
    if (!onClose) return;
    const handler = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, [onClose]);

  const containerClass = dark
    ? 'flex h-full flex-col bg-gray-900 text-white'
    : 'flex h-full flex-col bg-white';

  return (
    <div
      className={containerClass}
      data-testid="history-panel"
      aria-label="버전 이력 패널"
    >
      <Header dark={dark} count={list.length} onClose={onClose} />

      {isLoading ? (
        <div className="flex flex-1 items-center justify-center py-10">
          <Spinner label="버전 이력 로딩" />
        </div>
      ) : error ? (
        <div className="p-4">
          <ErrorState title="버전 이력 조회 실패" message={error.message} />
        </div>
      ) : (
        <div className="flex flex-1 flex-col overflow-hidden">
          {/* 커밋 목록 */}
          <CommitList
            dark={dark}
            versions={list}
            selectedHash={selectedHash}
            checkedHashes={checkedHashes}
            onSelect={handleSelect}
            onCheck={handleCheck}
            formatTime={formatTime}
          />

          {/* Diff + 롤백 */}
          <div
            className={
              dark
                ? 'flex-1 overflow-y-auto border-t border-gray-700 p-3'
                : 'flex-1 overflow-y-auto border-t border-gray-200 p-4'
            }
          >
            <div className="mb-2 flex items-center justify-between">
              <h3
                className={
                  dark
                    ? 'text-xs font-semibold text-gray-300'
                    : 'text-sm font-semibold text-gray-700'
                }
              >
                변경 내용 (Diff)
              </h3>
              {rollbackVersion && canRollback && (
                <Button
                  variant="secondary"
                  size="sm"
                  onClick={() => setShowRollback(true)}
                  data-testid={`rollback-trigger-${rollbackVersion.shortHash}`}
                >
                  <RotateCcw size={14} />
                  롤백
                </Button>
              )}
            </div>

            {activeFrom && activeTo ? (
              diffQuery.isLoading ? (
                <Spinner label="diff 로딩" />
              ) : diffQuery.error ? (
                <ErrorState title="diff 조회 실패" message={diffQuery.error.message} />
              ) : (
                <DiffViewer diffs={Array.isArray(diffQuery.data) ? diffQuery.data : []} />
              )
            ) : (
              <p className={dark ? 'text-xs text-gray-400' : 'text-sm text-gray-500'}>
                커밋을 선택하거나 두 커밋을 체크하여 diff 를 확인하세요.
              </p>
            )}
          </div>
        </div>
      )}

      {rollbackVersion && (
        <RollbackConfirmModal
          open={showRollback}
          commitSha={rollbackVersion.commitSha}
          shortHash={rollbackVersion.shortHash}
          videoId={srcSn}
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

// ----- 내부 컴포넌트 -----

function Header({
  dark,
  count,
  onClose,
}: {
  dark: boolean;
  count: number;
  onClose?: () => void;
}) {
  return (
    <div
      className={
        dark
          ? 'flex items-center justify-between border-b border-gray-700 px-3 py-2'
          : 'flex items-center justify-between border-b border-gray-200 px-4 py-3'
      }
    >
      <h2
        className={
          dark
            ? 'flex items-center gap-1.5 text-sm font-semibold text-white'
            : 'flex items-center gap-1.5 text-sm font-semibold text-gray-700'
        }
      >
        <GitCommit size={15} className={dark ? 'text-gray-400' : 'text-gray-400'} />
        커밋 목록
        <span
          className={
            dark
              ? 'ml-1 inline-flex items-center rounded-full bg-gray-700 px-2 py-0.5 text-xs font-medium text-gray-200'
              : 'ml-1 inline-flex items-center rounded-full bg-gray-100 px-2 py-0.5 text-xs font-medium text-gray-600'
          }
        >
          {count}
        </span>
      </h2>
      {onClose && (
        <button
          type="button"
          onClick={onClose}
          aria-label="버전 이력 닫기"
          data-testid="history-panel-close"
          className={
            dark
              ? 'rounded p-1 text-gray-400 hover:bg-gray-700 hover:text-white'
              : 'rounded p-1 text-gray-500 hover:bg-gray-100 hover:text-gray-700'
          }
        >
          <X size={16} />
        </button>
      )}
    </div>
  );
}

interface VersionLike {
  commitSha: string;
  shortHash: string;
  authorName: string;
  message: string;
  committedAt: string;
  isCurrent: boolean;
}

function CommitList({
  dark,
  versions,
  selectedHash,
  checkedHashes,
  onSelect,
  onCheck,
  formatTime,
}: {
  dark: boolean;
  versions: VersionLike[];
  selectedHash: string | null;
  checkedHashes: string[];
  onSelect: (hash: string) => void;
  onCheck: (hash: string, checked: boolean) => void;
  formatTime: (iso: string | undefined) => string;
}) {
  if (versions.length === 0) {
    return (
      <div
        className={
          dark
            ? 'p-4 text-xs text-gray-400'
            : 'p-4 text-sm text-gray-500'
        }
      >
        아직 커밋된 버전이 없습니다.
      </div>
    );
  }
  return (
    <ul
      className={
        dark
          ? 'max-h-[40vh] divide-y divide-gray-700 overflow-y-auto'
          : 'max-h-[40vh] divide-y divide-gray-100 overflow-y-auto'
      }
    >
      {versions.map((commit, idx) => {
        const isSelected = selectedHash === commit.commitSha;
        const isChecked = checkedHashes.includes(commit.commitSha);
        // 뱃지 분리 — "최신"은 시간순 첫 행, "현재"는 active 버전(롤백 시 과거 행으로 이동 가능).
        const isLatest = idx === 0;
        const isActive = commit.isCurrent;
        const rowClass = dark
          ? [
              'px-3 py-2 transition-colors',
              isSelected
                ? 'border-l-2 border-blue-400 bg-gray-800'
                : 'hover:bg-gray-800',
            ].join(' ')
          : [
              'px-4 py-3 transition-colors',
              isSelected
                ? 'border-l-2 border-blue-500 bg-blue-50'
                : 'hover:bg-gray-50',
            ].join(' ');
        return (
          <li
            key={commit.commitSha}
            className={rowClass}
            data-testid={`commit-row-${commit.shortHash}`}
          >
            <div className="flex items-start gap-2">
              <input
                type="checkbox"
                checked={isChecked}
                onChange={(e) => onCheck(commit.commitSha, e.target.checked)}
                className="mt-0.5 shrink-0 accent-blue-600"
                aria-label={`커밋 ${commit.shortHash} 선택`}
              />
              <button
                type="button"
                className="min-w-0 flex-1 text-left disabled:cursor-not-allowed"
                onClick={() => onSelect(commit.commitSha)}
                disabled={checkedHashes.length > 0}
              >
                <div className="mb-0.5 flex flex-wrap items-center gap-2">
                  <code
                    className={
                      dark
                        ? 'rounded bg-gray-800 px-1.5 py-0.5 font-mono text-xs text-blue-300'
                        : 'rounded bg-blue-50 px-1.5 py-0.5 font-mono text-xs text-blue-700'
                    }
                  >
                    {commit.shortHash}
                  </code>
                  {isLatest && (
                    <span
                      className={
                        dark
                          ? 'inline-flex items-center rounded-full bg-green-900 px-2 py-0.5 text-xs font-medium text-green-200'
                          : 'inline-flex items-center rounded-full bg-green-100 px-2 py-0.5 text-xs font-medium text-green-700'
                      }
                    >
                      최신
                    </span>
                  )}
                  {isActive && (
                    <span
                      className={
                        dark
                          ? 'inline-flex items-center rounded-full bg-blue-900 px-2 py-0.5 text-xs font-medium text-blue-200'
                          : 'inline-flex items-center rounded-full bg-blue-100 px-2 py-0.5 text-xs font-medium text-blue-700'
                      }
                    >
                      현재
                    </span>
                  )}
                </div>
                <p
                  className={
                    dark
                      ? 'truncate text-xs text-gray-200'
                      : 'truncate text-sm text-gray-800'
                  }
                >
                  {commit.message || '(메시지 없음)'}
                </p>
                <p
                  className={
                    dark
                      ? 'mt-0.5 text-xs text-gray-500'
                      : 'mt-0.5 text-xs text-gray-400'
                  }
                >
                  {commit.authorName} · {formatTime(commit.committedAt)}
                </p>
              </button>
            </div>
          </li>
        );
      })}
    </ul>
  );
}
