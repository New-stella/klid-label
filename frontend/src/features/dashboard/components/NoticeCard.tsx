import { Pin } from 'lucide-react';

import { EmptyState } from '@/components/common/EmptyState';

import type { Notice } from '../types';

export interface NoticeCardProps {
  notices: Notice[];
}

/** 공지사항 카드 — 고정 공지 우선 노출 */
export function NoticeCard({ notices }: NoticeCardProps) {
  const sorted = [...notices].sort((a, b) => {
    if (a.pinned !== b.pinned) return a.pinned ? -1 : 1;
    return b.createdAt.localeCompare(a.createdAt);
  });

  return (
    <section
      aria-label="공지사항"
      data-testid="notice-card"
      className="rounded border border-border bg-white p-4"
    >
      <h2 className="mb-3 text-section-title text-primary">공지사항</h2>
      {sorted.length === 0 ? (
        <EmptyState message="공지사항이 없습니다" />
      ) : (
        <ul className="flex flex-col gap-2">
          {sorted.slice(0, 5).map((n) => (
            <li key={n.id} className="flex items-center gap-2 text-body">
              {n.pinned && (
                <Pin className="h-3 w-3 text-warning" aria-label="고정 공지" />
              )}
              <span className="flex-1 text-primary">{n.title}</span>
              <time className="text-sub text-neutral" dateTime={n.createdAt}>
                {n.createdAt.slice(0, 10)}
              </time>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
