// 진행 중인 업로드 진행률 + 일시정지/재개/취소 버튼.

import type { TusUploadProgress, TusUploadStatus } from '../types';

interface UploadProgressItemProps {
  filename: string;
  status: TusUploadStatus;
  progress: TusUploadProgress;
  onPause?: () => void;
  onResume?: () => void;
  onCancel?: () => void;
}

export function UploadProgressItem({
  filename,
  status,
  progress,
  onPause,
  onResume,
  onCancel,
}: UploadProgressItemProps) {
  return (
    <div className="flex flex-col gap-2 rounded border border-border bg-white p-3">
      <div className="flex items-center justify-between gap-2">
        <span className="truncate text-body" title={filename}>
          {filename}
        </span>
        <span className="text-sub text-neutral">{progress.percent}%</span>
      </div>
      <div className="h-1 w-full rounded bg-bgLight">
        <div
          className="h-1 rounded bg-primary transition-all"
          style={{ width: `${progress.percent}%` }}
        />
      </div>
      <div className="flex items-center justify-end gap-2">
        {status === 'UPLOADING' && onPause && (
          <button
            type="button"
            onClick={onPause}
            className="rounded border border-border px-2 py-1 text-sub hover:bg-bgLight"
          >
            일시정지
          </button>
        )}
        {status === 'PAUSED' && onResume && (
          <button
            type="button"
            onClick={onResume}
            className="rounded border border-primary px-2 py-1 text-sub text-primary hover:bg-bgLight"
          >
            재개
          </button>
        )}
        {(status === 'UPLOADING' || status === 'PAUSED') && onCancel && (
          <button
            type="button"
            onClick={onCancel}
            className="rounded border border-danger px-2 py-1 text-sub text-danger hover:bg-bgLight"
          >
            취소
          </button>
        )}
      </div>
    </div>
  );
}
