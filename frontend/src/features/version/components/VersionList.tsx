import { type ReactNode } from 'react';

import { EmptyState } from '@/components/common/EmptyState';

import type { Version } from '../types';

interface VersionListProps {
  versions: Version[];
  /** 각 행 우측에 노출할 액션(롤백 버튼 등) — REVIEWER+WORKER 노출 */
  renderActions?: (version: Version) => ReactNode;
}

/**
 * 버전(커밋) 이력 리스트.
 * 첫 번째 항목이 가장 최신 — BE가 최신순으로 응답.
 * 현재 HEAD인 항목에 "현재" 뱃지 표시.
 */
export function VersionList({ versions, renderActions }: VersionListProps) {
  if (versions.length === 0) {
    return <EmptyState title="버전 이력이 없습니다" message="아직 커밋된 라벨이 없습니다." />;
  }

  return (
    <ul className="flex flex-col divide-y divide-border rounded border border-border bg-white">
      {versions.map((v) => (
        <li
          key={v.commitSha}
          data-testid="version-item"
          className="flex items-center justify-between gap-3 px-4 py-3"
        >
          <div className="flex flex-col gap-0.5">
            <div className="flex items-center gap-2">
              <code className="rounded bg-bgLight px-2 py-0.5 font-mono text-sub text-primary">
                {v.shortHash}
              </code>
              {v.isCurrent && (
                <span className="rounded bg-accent px-2 py-0.5 text-xs font-medium text-white">
                  현재
                </span>
              )}
              <span className="text-sub text-neutral">{v.authorName}</span>
            </div>
            <p className="text-body text-primary">{v.message}</p>
            <time className="text-xs text-neutral" dateTime={v.committedAt}>
              {v.committedAt}
            </time>
          </div>
          {renderActions ? <div className="flex-shrink-0">{renderActions(v)}</div> : null}
        </li>
      ))}
    </ul>
  );
}
