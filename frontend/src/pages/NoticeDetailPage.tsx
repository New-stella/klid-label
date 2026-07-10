// 화면ID: KLID-AT-SC-031 — 게시판(공지) 상세
import { useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import {
  ArrowLeft,
  Download,
  Eye,
  EyeOff,
  Paperclip,
  Pencil,
  Pin,
  Trash2,
} from 'lucide-react';

import { Button } from '@/components/common/Button';
import { ConfirmDialog } from '@/components/common/ConfirmDialog';
import { ErrorState } from '@/components/common/ErrorState';
import { Skeleton } from '@/components/common/Skeleton';
import { downloadAttachment } from '@/features/notice/api';
import { NoticeEditModal } from '@/features/notice/components/NoticeEditModal';
import { useNoticeActions } from '@/features/notice/hooks/useNoticeActions';
import { useNotice } from '@/features/notice/hooks/useNotices';
import {
  NoticePubStatus,
  type NoticeAttach,
  type NoticeForm,
} from '@/features/notice/types';
import { cn } from '@/lib/cn';
import { Role } from '@/lib/api/types';
import { useAuthStore } from '@/stores/useAuthStore';
import { useUiStore } from '@/stores/useUiStore';

function formatFileSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}

function formatDateTime(iso: string | null): string {
  if (!iso) return '-';
  return iso.replace('T', ' ').slice(0, 16);
}

export function NoticeDetailPage() {
  const { id: idParam } = useParams<{ id: string }>();
  const id = Number(idParam);
  const navigate = useNavigate();
  const role = useAuthStore((s) => s.claims?.role) ?? Role.WORKER;
  const isReviewer = role === Role.REVIEWER;
  const pushToast = useUiStore((s) => s.pushToast);

  const { data: notice, isLoading, error } = useNotice(
    Number.isFinite(id) && id > 0 ? id : undefined,
  );
  const { update, remove, publish, unpublish, uploadAttach, removeAttach } =
    useNoticeActions();

  const [editOpen, setEditOpen] = useState(false);
  const [deleteOpen, setDeleteOpen] = useState(false);
  const [downloadingId, setDownloadingId] = useState<number | null>(null);

  const handleDownload = (attach: NoticeAttach) => {
    setDownloadingId(attach.attachSn);
    downloadAttachment(id, attach.attachSn, attach.fileName)
      .catch(() =>
        pushToast({ variant: 'error', message: '첨부파일 다운로드에 실패했습니다.' }),
      )
      .finally(() => setDownloadingId(null));
  };

  const handleEdit = (form: NoticeForm) => {
    update.mutate({ id, form }, { onSuccess: () => setEditOpen(false) });
  };

  const handleDelete = () => {
    remove.mutate(id, {
      onSuccess: () => {
        setDeleteOpen(false);
        navigate('/notice');
      },
    });
  };

  if (isLoading) {
    return (
      <div className="space-y-4">
        <Skeleton height={28} width="40%" />
        <Skeleton height={200} />
      </div>
    );
  }

  if (error || !notice) {
    return (
      <div className="space-y-4">
        <Button variant="ghost" size="sm" leftIcon={ArrowLeft} onClick={() => navigate('/notice')}>
          목록으로
        </Button>
        <ErrorState title="공지를 불러올 수 없습니다" />
      </div>
    );
  }

  const isPublished = notice.pubStatus === NoticePubStatus.PUBLISHED;

  return (
    <div className="space-y-4">
      {/* Top bar */}
      <div className="flex items-center justify-between">
        <Button
          variant="ghost"
          size="sm"
          leftIcon={ArrowLeft}
          onClick={() => navigate('/notice')}
        >
          목록으로
        </Button>
        {isReviewer && (
          <div className="flex items-center gap-1">
            <Button
              variant="secondary"
              size="sm"
              leftIcon={Pencil}
              onClick={() => setEditOpen(true)}
            >
              수정
            </Button>
            {isPublished ? (
              <Button
                variant="secondary"
                size="sm"
                leftIcon={EyeOff}
                loading={unpublish.isPending}
                onClick={() => unpublish.mutate(id)}
              >
                발행취소
              </Button>
            ) : (
              <Button
                variant="primary"
                size="sm"
                leftIcon={Eye}
                loading={publish.isPending}
                onClick={() => publish.mutate(id)}
              >
                발행
              </Button>
            )}
            <Button
              variant="danger"
              size="sm"
              leftIcon={Trash2}
              onClick={() => setDeleteOpen(true)}
            >
              삭제
            </Button>
          </div>
        )}
      </div>

      {/* Article */}
      <article className="rounded-lg border border-gray-200 bg-white shadow-sm">
        <header className="border-b border-gray-100 px-6 py-5">
          <div className="flex items-center gap-2">
            {/* KRDS 예외: 고정 pinned amber 는 강조 accent(상태 아님) — 토큰 획일화 제외(의도적 유지). */}
            {notice.pinned && (
              <span
                className="inline-flex items-center gap-1 rounded bg-amber-100 px-1.5 py-0.5 text-xs font-semibold text-amber-700"
                aria-label="상단 고정"
              >
                <Pin size={11} aria-hidden />
                고정
              </span>
            )}
            {isReviewer && (
              <span
                className={cn(
                  'inline-flex items-center rounded-full px-2 py-0.5 text-xs font-semibold',
                  isPublished
                    ? 'bg-success/10 text-success'
                    : 'bg-gray-100 text-gray-600',
                )}
              >
                {isPublished ? '발행' : '작성중'}
              </span>
            )}
          </div>
          <h1 className="mt-2 text-xl font-bold text-gray-900">{notice.title}</h1>
          <div className="mt-2 flex flex-wrap gap-x-4 gap-y-1 text-xs text-gray-500">
            {notice.regId && <span>작성자: {notice.regId}</span>}
            <span>등록: {formatDateTime(notice.regDt)}</span>
            {notice.mdfcnDt && <span>수정: {formatDateTime(notice.mdfcnDt)}</span>}
            {isPublished && notice.pubDt && (
              <span>발행: {formatDateTime(notice.pubDt)}</span>
            )}
          </div>
        </header>

        <div className="px-6 py-6">
          <p className="whitespace-pre-wrap break-words text-sm leading-relaxed text-gray-800">
            {notice.content}
          </p>
        </div>

        {/* Attachments */}
        {notice.attachments.length > 0 && (
          <footer className="border-t border-gray-100 px-6 py-4">
            <h2 className="mb-2 flex items-center gap-1.5 text-sm font-semibold text-gray-700">
              <Paperclip size={14} aria-hidden />
              첨부파일 ({notice.attachments.length})
            </h2>
            <ul className="space-y-1">
              {notice.attachments.map((a) => (
                <li key={a.attachSn}>
                  <button
                    type="button"
                    onClick={() => handleDownload(a)}
                    disabled={downloadingId === a.attachSn}
                    className="inline-flex items-center gap-2 rounded px-2 py-1 text-sm text-primary-700 hover:bg-primary-50 hover:underline disabled:opacity-50"
                  >
                    <Download size={14} aria-hidden />
                    <span>{a.fileName}</span>
                    <span className="text-xs text-gray-400">
                      ({formatFileSize(a.fileSize)})
                    </span>
                  </button>
                </li>
              ))}
            </ul>
          </footer>
        )}
      </article>

      {/* Edit Modal (REVIEWER) */}
      {isReviewer && (
        <NoticeEditModal
          open={editOpen}
          onClose={() => setEditOpen(false)}
          initial={notice}
          onSubmit={handleEdit}
          submitting={update.isPending}
          attachUploading={uploadAttach.isPending}
          onUploadAttachment={(file) => uploadAttach.mutate({ id, file })}
          onDeleteAttachment={(attach) =>
            removeAttach.mutate({ id, attachId: attach.attachSn })
          }
        />
      )}

      {/* Delete Confirm */}
      <ConfirmDialog
        open={deleteOpen}
        title="공지를 삭제하시겠습니까?"
        description="삭제한 공지는 복구할 수 없습니다."
        confirmLabel="삭제"
        variant="danger"
        loading={remove.isPending}
        onConfirm={handleDelete}
        onCancel={() => setDeleteOpen(false)}
      />
    </div>
  );
}
