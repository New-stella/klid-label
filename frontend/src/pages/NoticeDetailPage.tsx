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
import { useNoticeActions } from '@/features/notice/hooks/useNoticeActions';
import { useNotice } from '@/features/notice/hooks/useNotices';
import { NoticePubStatus, type NoticeAttach } from '@/features/notice/types';
import { cn } from '@/lib/cn';
import { resolveDisplayName } from '@/lib/displayName';
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
  // 수정·첨부 관리는 전용 수정 화면(/notice/:id/edit)이 담당한다 — 여기서는 발행·삭제만 다룬다.
  const { remove, publish, unpublish } = useNoticeActions();

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
  // 작성자 — 표시명 우선, 없으면 원값(regId) 폴백. 둘 다 없으면 미표시.
  const writerLabel = resolveDisplayName(notice.writerName, notice.regId);

  return (
    <div className="space-y-4">
      {/* 상단 액션 바 — 목록으로(좌) + 발행 제어(우, REVIEWER 전용).
          ★수정·삭제는 여기 두지 않는다. 사양 SCREEN-031 이 "하나의 액션 바가 아니다" 라고
          명시했다 — 발행 상태 제어(가역·게시 노출 축)와 콘텐츠 관리(수정·비가역 삭제 축)는
          성격이 달라, 한 줄에 모아 두면 '발행취소' 옆에서 '삭제' 를 잘못 누르기 쉽다. */}
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
          <div data-testid="notice-publish-actions" className="flex items-center gap-1">
            {/* 현재 상태에 해당하는 하나만 노출한다(동시 노출 안 함). */}
            {isPublished ? (
              <Button
                variant="outline"
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
                className="inline-flex items-center gap-1 rounded bg-amber-100 px-1.5 py-0.5 text-label font-semibold text-amber-700"
                aria-label="상단 고정"
              >
                <Pin size={11} aria-hidden />
                고정
              </span>
            )}
            {isReviewer && (
              <span
                className={cn(
                  'inline-flex items-center rounded-full px-2 py-0.5 text-label font-semibold',
                  isPublished
                    ? 'bg-success/10 text-success-700'
                    : 'bg-gray-100 text-gray-600',
                )}
              >
                {isPublished ? '발행' : '작성중'}
              </span>
            )}
          </div>
          <h1 className="mt-2 text-title-lg font-bold text-gray-900">{notice.title}</h1>
          <div className="mt-2 flex flex-wrap gap-x-4 gap-y-1 text-caption text-gray-500">
            {/* ★게시 메타는 작성자·등록·수정 3종만이다 — 발행일시(pubDt)는 응답에 있어도
                화면에 렌더하지 않는다(사양 SCREEN-031 '게시 메타' note).
                그 값은 "최근 발행 시점"이라 발행을 취소하면 비워지고 다시 발행하면 새 값으로
                덮인다 — 최초 발행 이력이 아니다. 여기 '발행: …' 으로 놓이면 게시 이력처럼
                읽히지만 실제로는 토글할 때마다 사라졌다 바뀌는 값이라 사실을 오도한다.
                발행 여부는 위 발행 상태 배지와 상단 발행/발행취소 버튼이 말한다. */}
            {writerLabel && <span>작성자: {writerLabel}</span>}
            <span>등록: {formatDateTime(notice.regDt)}</span>
            {notice.mdfcnDt && <span>수정: {formatDateTime(notice.mdfcnDt)}</span>}
          </div>
        </header>

        <div className="px-6 py-6">
          <p className="whitespace-pre-wrap break-words text-body-md leading-relaxed text-gray-800">
            {notice.content}
          </p>
        </div>

        {/* Attachments */}
        {notice.attachments.length > 0 && (
          <footer className="border-t border-gray-100 px-6 py-4">
            <h2 className="mb-2 flex items-center gap-1.5 text-title-sm font-semibold text-gray-700">
              <Paperclip size={14} aria-hidden />
              첨부파일 ({notice.attachments.length})
            </h2>
            <ul className="space-y-1">
              {notice.attachments.map((a) => (
                <li key={a.attachSn}>
                  <Button
                    variant="ghost"
                    size="sm"
                    leftIcon={Download}
                    onClick={() => handleDownload(a)}
                    disabled={downloadingId === a.attachSn}
                    className="gap-2 px-2 text-body-md text-primary-700 hover:bg-primary-50 hover:text-primary-700 hover:underline disabled:opacity-50"
                  >
                    <span>{a.fileName}</span>
                    <span className="text-caption text-gray-400">
                      ({formatFileSize(a.fileSize)})
                    </span>
                  </Button>
                </li>
              ))}
            </ul>
          </footer>
        )}
      </article>

      {/* 하단 관리 액션 — 본문(article) 아래 구분선을 둔 별도 영역(사양 SCREEN-031).
          상단 발행 제어와 레이아웃이 분리되어 있다. */}
      {isReviewer && (
        <div
          data-testid="notice-manage-actions"
          className="flex items-center justify-end gap-1 border-t border-gray-200 pt-4"
        >
          {/* 수정은 이 화면 안의 모달이 아니라 전용 수정 화면으로 이동한다(사양 SCREEN-031).
              모달이면 수정 화면에 직접 진입할 URL 이 없고, 첨부 관리까지 모달 안에 갇힌다. */}
          <Button
            variant="secondary"
            size="sm"
            leftIcon={Pencil}
            onClick={() => navigate(`/notice/${id}/edit`)}
          >
            수정
          </Button>
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

      {/* Delete Confirm */}
      {/* 삭제 확인 — 제목 '공지 삭제', 본문에 **대상 게시글 제목**을 넣는다(사양 SCREEN-031).
          고정 문구는 어느 공지를 지우는지 말해 주지 않아, 목록·상세를 오가다 다른 글에서
          삭제를 누른 경우를 확인 단계가 걸러 내지 못한다(비가역 작업). */}
      <ConfirmDialog
        open={deleteOpen}
        title="공지 삭제"
        description={`${notice.title} 공지를 삭제합니다. 이 작업은 되돌릴 수 없습니다.`}
        confirmLabel="삭제"
        variant="danger"
        loading={remove.isPending}
        onConfirm={handleDelete}
        onCancel={() => setDeleteOpen(false)}
      />
    </div>
  );
}
