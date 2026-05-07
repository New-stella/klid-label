import { useMemo, useState } from 'react';
import { Link, useParams } from 'react-router-dom';

import { Button } from '@/components/common/Button';
import { ErrorState } from '@/components/common/ErrorState';
import { PageHeader } from '@/components/common/PageHeader';
import { Spinner } from '@/components/common/Spinner';
import { DiffViewer } from '@/features/version/components/DiffViewer';
import { RollbackConfirmModal } from '@/features/version/components/RollbackConfirmModal';
import { VersionList } from '@/features/version/components/VersionList';
import { VersionPicker } from '@/features/version/components/VersionPicker';
import { useDiff } from '@/features/version/hooks/useDiff';
import { useVersions } from '@/features/version/hooks/useVersions';
import type { Version } from '@/features/version/types';
import { Role } from '@/lib/api/types';
import { useAuthStore } from '@/stores/useAuthStore';

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
  const numericId = videoId ? Number(videoId) : NaN;
  const role = useAuthStore((s) => s.claims?.role ?? null);
  const canRollback = role === Role.REVIEWER;

  const { data: versions, isLoading, error } = useVersions(
    Number.isFinite(numericId) ? numericId : undefined,
  );

  const [fromSha, setFromSha] = useState('');
  const [toSha, setToSha] = useState('');
  const [rollbackTarget, setRollbackTarget] = useState<Version | null>(null);

  const diffQuery = useDiff(
    Number.isFinite(numericId) ? numericId : undefined,
    toSha || undefined,
    fromSha || undefined,
  );

  // 롤백 직후 새로 발급된 commit이 fromSha/toSha에 포함된 상태에서
  // versions 재조회로 dropdown이 stale일 수 있어 useMemo로 안정화
  const versionList = useMemo(() => versions ?? [], [versions]);

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
    <div className="flex h-full flex-col gap-4" data-testid="history-page">
      <PageHeader
        title="버전 이력"
        breadcrumb={[
          { label: '영상', href: '/video/completed' },
          { label: '버전 이력' },
        ]}
        actions={
          <Link to={`/label/${numericId}`} className="text-sub text-primary hover:underline">
            ◀ 라벨링으로
          </Link>
        }
      />

      <section className="flex flex-col gap-2 rounded border border-border bg-white p-4">
        <h2 className="text-section-title text-primary">버전 비교</h2>
        <div className="flex items-end gap-3">
          <VersionPicker
            label="이전 버전"
            value={fromSha}
            onChange={setFromSha}
            versions={versionList}
            testId="version-picker-from"
          />
          <span className="pb-2 text-neutral">→</span>
          <VersionPicker
            label="이후 버전"
            value={toSha}
            onChange={setToSha}
            versions={versionList}
            testId="version-picker-to"
          />
        </div>
        <div className="mt-2">
          {fromSha && toSha ? (
            diffQuery.isLoading ? (
              <Spinner label="diff 로딩" />
            ) : diffQuery.error ? (
              <ErrorState title="diff 조회 실패" message={diffQuery.error.message} />
            ) : (
              <DiffViewer diffs={diffQuery.data ?? []} />
            )
          ) : (
            <p className="text-sub text-neutral">두 버전을 선택하면 변경 내역이 표시됩니다.</p>
          )}
        </div>
      </section>

      <section className="flex flex-col gap-2">
        <h2 className="text-section-title text-primary">커밋 이력</h2>
        <VersionList
          versions={versionList}
          renderActions={(v) =>
            v.isCurrent ? null : (
              <Button
                variant="danger"
                size="sm"
                disabled={!canRollback}
                onClick={() => setRollbackTarget(v)}
                aria-label={`롤백 시작 ${v.shortHash}`}
                data-testid={`rollback-trigger-${v.shortHash}`}
              >
                롤백 시작
              </Button>
            )
          }
        />
      </section>

      {rollbackTarget && Number.isFinite(numericId) && (
        <RollbackConfirmModal
          open={Boolean(rollbackTarget)}
          commitSha={rollbackTarget.commitSha}
          shortHash={rollbackTarget.shortHash}
          videoId={numericId}
          onClose={() => setRollbackTarget(null)}
          onSuccess={() => setRollbackTarget(null)}
        />
      )}
    </div>
  );
}
