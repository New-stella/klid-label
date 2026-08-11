// 공지 수정 화면 (route /notice/:id/edit) — REVIEWER 전용.
//
// 사양이 수정 동선을 전용 화면으로 규정하고, 상세 화면 안의 수정 모달은 폐기로 명시했다.
// 작성 화면과 달리 게시글 id 가 이미 발급돼 있어 첨부파일 관리(업로드·행별 삭제)를 함께 제공한다.
import { ArrowLeft, Paperclip, Trash2, Upload } from 'lucide-react';
import { useRef } from 'react';
import { useNavigate, useParams } from 'react-router-dom';

import { Button } from '@/components/common/Button';
import { ErrorState } from '@/components/common/ErrorState';
import { Skeleton } from '@/components/common/Skeleton';
import { NoticeFormSection } from '@/features/notice/components/NoticeFormSection';
import { useNoticeActions } from '@/features/notice/hooks/useNoticeActions';
import { useNotice } from '@/features/notice/hooks/useNotices';
import type { NoticeAttach, NoticeForm } from '@/features/notice/types';
import { formatFileSize } from '@/lib/formatFileSize';
import { useUiStore } from '@/stores/useUiStore';

export function NoticeEditPage() {
  const { id: idParam } = useParams<{ id: string }>();
  const id = Number(idParam);
  const navigate = useNavigate();
  const pushToast = useUiStore((s) => s.pushToast);
  const fileInputRef = useRef<HTMLInputElement>(null);

  const {
    data: notice,
    isLoading,
    error,
    refetch,
  } = useNotice(Number.isFinite(id) && id > 0 ? id : undefined);
  const { update, uploadAttach, removeAttach } = useNoticeActions();

  const backToDetail = () => navigate(`/notice/${id}`);

  const handleSubmit = (form: NoticeForm) => {
    update.mutate(
      { id, form },
      {
        onSuccess: () => {
          pushToast({ variant: 'success', message: '공지를 수정했습니다.' });
          backToDetail();
        },
      },
    );
  };

  const handleFileChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    // 동일 파일 재선택이 가능하도록 값을 비운다.
    e.target.value = '';
    if (!file) return;
    uploadAttach.mutate(
      { id, file },
      {
        onSuccess: () =>
          pushToast({ variant: 'success', message: '첨부파일을 업로드했습니다.' }),
      },
    );
  };

  const handleDeleteAttachment = (attach: NoticeAttach) => {
    removeAttach.mutate(
      { id, attachId: attach.attachSn },
      {
        onSuccess: () =>
          pushToast({ variant: 'success', message: '첨부파일을 삭제했습니다.' }),
      },
    );
  };

  return (
    <div className="space-y-4">
      {/* 페이지 헤더 — 뒤로 가기(이전 화면) + 제목. 로딩·오류일 때도 유지된다. */}
      <div className="flex items-center gap-2">
        <Button
          variant="outline"
          size="sm"
          leftIcon={ArrowLeft}
          onClick={() => navigate(-1)}
        >
          뒤로 가기
        </Button>
        <h1 className="text-title-lg font-bold text-gray-900">공지 수정</h1>
      </div>

      {/* 로딩·오류 상태 — 조회가 끝나기 전/실패 시 폼·첨부 섹션은 렌더하지 않는다.
          빈 폼을 먼저 보여 주면 사용자가 기존 내용이 지워진 것으로 읽고 그대로 저장할 수 있다. */}
      {isLoading ? (
        <div className="space-y-4 rounded-lg border border-gray-200 bg-white p-6 shadow-sm">
          <Skeleton height={44} />
          <Skeleton height={236} />
        </div>
      ) : error || !notice ? (
        <ErrorState title="공지를 불러오지 못했습니다." onRetry={() => void refetch()} />
      ) : (
        <>
          <div className="rounded-lg border border-gray-200 bg-white p-6 shadow-sm">
            <NoticeFormSection
              defaultValues={{
                title: notice.title,
                content: notice.content,
                pinned: notice.pinned,
              }}
              submitLabel="저장"
              submitting={update.isPending}
              onSubmit={handleSubmit}
              // 취소는 해당 게시글 상세로 복귀한다(사양 SCREEN-037 '취소' note).
              onCancel={backToDetail}
            />
          </div>

          {/* 첨부파일 관리 — 폼과 구분된 별도 영역. 작성 화면에는 이 섹션이 없다. */}
          <section className="rounded-lg border border-gray-200 bg-white p-6 shadow-sm">
            <div className="flex items-center justify-between">
              <h2 className="text-title-sm font-semibold text-gray-700">첨부파일</h2>
              <Button
                type="button"
                variant="outline"
                size="sm"
                leftIcon={Upload}
                loading={uploadAttach.isPending}
                onClick={() => fileInputRef.current?.click()}
              >
                파일 추가
              </Button>
              <input
                ref={fileInputRef}
                type="file"
                className="hidden"
                aria-label="첨부파일 선택"
                onChange={handleFileChange}
              />
            </div>

            {notice.attachments.length === 0 ? (
              <p className="mt-3 text-caption text-gray-400">첨부된 파일이 없습니다.</p>
            ) : (
              <ul className="mt-3 space-y-1">
                {notice.attachments.map((a) => (
                  <li
                    key={a.attachSn}
                    className="flex items-center justify-between rounded border border-gray-100 bg-gray-50 px-3 py-1.5"
                  >
                    <span className="flex items-center gap-2 text-body-md text-gray-700">
                      <Paperclip size={14} aria-hidden />
                      <span className="max-w-[280px] truncate">{a.fileName}</span>
                      <span className="text-caption text-gray-400">
                        ({formatFileSize(a.fileSize)})
                      </span>
                    </span>
                    <Button
                      variant="ghost"
                      size="sm"
                      aria-label={`${a.fileName} 삭제`}
                      onClick={() => handleDeleteAttachment(a)}
                      className="h-7 w-7 p-0 text-gray-400 hover:bg-danger/10 hover:text-danger active:bg-danger/20"
                    >
                      <Trash2 size={14} aria-hidden />
                    </Button>
                  </li>
                ))}
              </ul>
            )}
          </section>
        </>
      )}
    </div>
  );
}
