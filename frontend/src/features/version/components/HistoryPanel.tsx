// SCR-HIST-001/Inline — 버전 이력 인라인 패널.
//
// 라벨링 화면(SC-005) 우측 슬라이드 패널의 본문 UI. 유일한 사용처이며 변경 이력·버전 탭을
// 모두 제공한다. (구 별도 페이지 /history/:videoId 는 2026-08-03 제거.)
//
// 권한:
// - 조회(WORKER+REVIEWER): 버전 목록 + diff 가능
// - 롤백(REVIEWER+WORKER): "이 버전으로 롤백" 트리거 노출. WORKER는 본인 배정 프레임만 가능 (BE에서 IDOR 검증)
//
// 보안: 사용자 입력 commit hash 는 BE 에서 SHA hex 검증. FE 는 단순 전달.

import { useEffect, useState } from 'react';
import { History, RotateCcw, X } from 'lucide-react';

import { Button } from '@/components/common/Button';
import { ErrorState } from '@/components/common/ErrorState';
import { Spinner } from '@/components/common/Spinner';
import type { LabelHistoryItem } from '@/features/label/api';
import { LabelHistoryPanel } from '@/features/label/components/LabelHistoryPanel';
import { DiffViewer } from '@/features/version/components/DiffViewer';
import { RollbackConfirmModal } from '@/features/version/components/RollbackConfirmModal';
import { useDiff } from '@/features/version/hooks/useDiff';
import { useVersions } from '@/features/version/hooks/useVersions';
import { Role } from '@/lib/api/types';
import { cn } from '@/lib/cn';
import { resolveDisplayName } from '@/lib/displayName';
import { useAuthStore } from '@/stores/useAuthStore';
import { isEditBlockedNow, useIsEditBlocked } from '@/stores/useLabelStore';

/** 히스토리 패널 탭 — 변경 이력(저장, LS_DATA_LBL_HSTRY) / 버전(커밋, LS_LABEL_VERSION). */
type HistoryTab = 'changes' | 'versions';

interface HistoryPanelProps {
  /** 프레임(srcSn) — useVersions/useDiff/롤백에 모두 사용 */
  srcSn: number;
  /** 닫기 버튼 노출 + 콜백. 미지정 시 닫기 버튼 미노출 (라우트 페이지에서 사용) */
  onClose?: () => void;
  /** 라벨링 화면 우측 슬라이드용 다크 테마. 별도 페이지에서는 false (기본). */
  dark?: boolean;
  /**
   * 초기 활성 탭. 기본 'changes'(변경 이력=저장) — 라벨링 화면 인라인 패널에서
   * 저장 직후 기대 화면을 노출. 버전 브라우징 전용 페이지는 'versions' 전달.
   */
  defaultTab?: HistoryTab;
  /**
   * "변경 이력"(저장 이벤트) 탭에서 저장을 현재 작업본에 되돌리기 요청 콜백.
   * 라벨링 화면(작업본 컨텍스트)에서만 전달 — 버전 브라우징 전용 페이지는 미전달(버튼 미노출).
   */
  onRevert?: (item: LabelHistoryItem) => void;
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

export function HistoryPanel({
  srcSn,
  onClose,
  dark = false,
  defaultTab = 'changes',
  onRevert,
}: HistoryPanelProps) {
  const role = useAuthStore((s) => s.claims?.role ?? null);
  const canRollback = role === Role.REVIEWER || role === Role.WORKER;
  // 편집 차단 단일 판정원 — 롤백은 **서버측 라벨 재작성**이라 저장 PUT in-flight 와 교차 실행되면
  // 어느 쪽이 최종본인지 결정되지 않는다. 진행 중에는 진입 자체를 막는다.
  const editBlocked = useIsEditBlocked();

  const { data: versions, isLoading, error } = useVersions(srcSn);

  // 기본 활성 탭 = 변경 이력(저장). 저장 직후 사용자가 기대하는 화면을 바로 노출한다.
  const [activeTab, setActiveTab] = useState<HistoryTab>(defaultTab);
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
      aria-label="히스토리 패널"
    >
      <Header dark={dark} onClose={onClose} />
      <TabBar
        dark={dark}
        activeTab={activeTab}
        versionCount={list.length}
        onChange={setActiveTab}
      />

      {activeTab === 'changes' ? (
        // 변경 이력(저장) — LS_DATA_LBL_HSTRY. self-contained 컴포넌트 재사용.
        <div
          className="flex flex-1 flex-col overflow-y-auto"
          role="tabpanel"
          id="history-panel-changes"
          aria-labelledby="history-tab-changes"
          data-testid="history-changes-panel"
        >
          <LabelHistoryPanel srcSn={srcSn} dark={dark} onRevert={onRevert} />
        </div>
      ) : (
        // 버전(커밋) — LS_LABEL_VERSION 스냅샷 + diff + 롤백.
        <div
          className="flex flex-1 flex-col overflow-hidden"
          role="tabpanel"
          id="history-panel-versions"
          aria-labelledby="history-tab-versions"
          data-testid="history-versions-panel"
        >
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
                      // 이중 방어 — 렌더 값이 낡았을 수 있으므로 실행 시점 store 값도 본다(fail-closed).
                      onClick={() => {
                        if (editBlocked || isEditBlockedNow()) return;
                        setShowRollback(true);
                      }}
                      disabled={editBlocked}
                      title={
                        editBlocked
                          ? '다른 작업이 진행 중입니다. 완료 후 롤백할 수 있습니다.'
                          : undefined
                      }
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
  onClose,
}: {
  dark: boolean;
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
        <History size={15} className={dark ? 'text-gray-400' : 'text-gray-400'} />
        히스토리
      </h2>
      {onClose && (
        <button
          type="button"
          onClick={onClose}
          aria-label="히스토리 닫기"
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

/** 변경 이력(저장) / 버전(커밋) 탭 스위처. 우측 패널 탭과 동일한 role/aria 패턴. */
function TabBar({
  dark,
  activeTab,
  versionCount,
  onChange,
}: {
  dark: boolean;
  activeTab: HistoryTab;
  versionCount: number;
  onChange: (tab: HistoryTab) => void;
}) {
  const base =
    'flex-1 inline-flex items-center justify-center gap-1.5 px-3 py-2 text-xs font-semibold border-b-2 transition-colors';
  const activeCls = dark
    ? 'text-white border-primary-500'
    : 'text-primary-700 border-primary-500';
  const idleCls = dark
    ? 'text-gray-400 border-transparent hover:text-gray-200'
    : 'text-gray-500 border-transparent hover:text-gray-700';

  return (
    <div
      role="tablist"
      aria-label="히스토리 종류"
      className={dark ? 'flex border-b border-gray-700' : 'flex border-b border-gray-200'}
    >
      <button
        type="button"
        role="tab"
        id="history-tab-changes"
        aria-selected={activeTab === 'changes'}
        aria-controls="history-panel-changes"
        data-testid="history-tab-changes"
        onClick={() => onChange('changes')}
        className={cn(base, activeTab === 'changes' ? activeCls : idleCls)}
      >
        변경 이력
      </button>
      <button
        type="button"
        role="tab"
        id="history-tab-versions"
        aria-selected={activeTab === 'versions'}
        aria-controls="history-panel-versions"
        data-testid="history-tab-versions"
        onClick={() => onChange('versions')}
        className={cn(base, activeTab === 'versions' ? activeCls : idleCls)}
      >
        버전
        <span
          className={
            dark
              ? 'inline-flex items-center rounded-full bg-gray-700 px-1.5 py-0.5 text-[10px] font-medium text-gray-200'
              : 'inline-flex items-center rounded-full bg-gray-100 px-1.5 py-0.5 text-[10px] font-medium text-gray-600'
          }
        >
          {versionCount}
        </span>
      </button>
    </div>
  );
}

interface VersionLike {
  commitSha: string;
  shortHash: string;
  /** 표시명 — BE 해석 실패 시 null 이므로 표시에는 authorNo 폴백을 반드시 거친다. */
  authorName: string | null;
  /** 사번(REG_ID) — 이름이 없을 때의 폴백 원값. */
  authorNo: string | null;
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
                ? 'border-l-2 border-primary-400 bg-gray-800'
                : 'hover:bg-gray-800',
            ].join(' ')
          : [
              'px-4 py-3 transition-colors',
              isSelected
                ? 'border-l-2 border-primary-500 bg-primary-50'
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
                className="mt-0.5 shrink-0 accent-primary-600"
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
                        ? 'rounded bg-gray-800 px-1.5 py-0.5 font-mono text-xs text-primary-300'
                        : 'rounded bg-primary-50 px-1.5 py-0.5 font-mono text-xs text-primary-700'
                    }
                  >
                    {commit.shortHash}
                  </code>
                  {/* '최신'=success(추가/성공 의미), '현재'=info 로 라이트측은 KRDS 토큰화 완료.
                      다크 분기(bg-green-900/bg-blue-900)는 KRDS 토큰에 다크 전용 셰이드가 없어
                      다크 배경 대비 확보를 위해 raw shade 유지 — 의도적 예외. */}
                  {isLatest && (
                    <span
                      className={
                        dark
                          ? 'inline-flex items-center rounded-full bg-green-900 px-2 py-0.5 text-xs font-medium text-green-200'
                          : 'inline-flex items-center rounded-full bg-success/10 px-2 py-0.5 text-xs font-medium text-success'
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
                          : 'inline-flex items-center rounded-full bg-info/10 px-2 py-0.5 text-xs font-medium text-info'
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
                  {resolveDisplayName(commit.authorName, commit.authorNo) ?? '시스템'} ·{' '}
                  {formatTime(commit.committedAt)}
                </p>
              </button>
            </div>
          </li>
        );
      })}
    </ul>
  );
}
