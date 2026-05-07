// 내 업로드 목록 — V1.5: 다운로드 UI 미제공 (포털 자체 책임).
// IDOR: srcSn 링크는 BE 응답 그대로 사용. 직접 조작 금지.

import { Link } from 'react-router-dom';

import type { PortalUpload, PortalUploadStatus } from '../types';

interface MyUploadListProps {
  uploads: PortalUpload[];
}

const STATUS_LABEL: Record<PortalUploadStatus, string> = {
  UPLOADING: '업로드 중',
  COMPLETED: '업로드 완료',
  AUTOLABEL_PENDING: '오토라벨 대기',
  AUTOLABEL_DONE: '라벨링 완료',
  FAILED: '실패',
};

function formatSize(bytes: number): string {
  if (bytes >= 1024 * 1024 * 1024) return `${(bytes / 1024 / 1024 / 1024).toFixed(2)} GB`;
  if (bytes >= 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
  if (bytes >= 1024) return `${(bytes / 1024).toFixed(0)} KB`;
  return `${bytes} B`;
}

export function MyUploadList({ uploads }: MyUploadListProps) {
  if (uploads.length === 0) {
    return (
      <div className="rounded border border-border bg-white p-6 text-center text-body text-neutral">
        업로드된 영상이 없습니다.
      </div>
    );
  }

  return (
    <ul className="flex flex-col gap-2" data-testid="my-upload-list">
      {uploads.map((u) => (
        <li
          key={u.srcSn}
          className="flex flex-col gap-1 rounded border border-border bg-white p-3 md:flex-row md:items-center md:justify-between"
        >
          <div className="flex flex-col gap-1">
            <span className="text-body font-medium">{u.displayName}</span>
            <span className="text-sub text-neutral">
              {formatSize(u.fileSize)} · {STATUS_LABEL[u.status]}
            </span>
          </div>
          <Link
            to={`/portal/label/${u.srcSn}`}
            className="rounded border border-primary px-3 py-1 text-sub text-primary hover:bg-bgLight"
          >
            라벨링
          </Link>
        </li>
      ))}
    </ul>
  );
}
