// 공지 수정 화면 (route /notice/:id/edit) — REVIEWER 전용.
//
// 사양이 수정 동선을 전용 화면으로 규정하고, 상세 화면 안의 수정 모달은 폐기로 명시했다.
// 작성 화면과 달리 게시글 id 가 이미 발급돼 있어 첨부파일 관리(업로드·행별 삭제)를 함께 제공한다.
import { ArrowLeft, Upload } from 'lucide-react';
import { useRef } from 'react';
import { useNavigate, useParams } from 'react-router-dom';

import { AttachmentList } from '@/components/common/AttachmentList';
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
    // 목록·캔버스 화면과 달리 읽고 입력하는 단일 폼이라 본문 폭을 제한한다(SCREEN-037 디자인).
    <div className="mx-auto flex w-full max-w-[840px] flex-col gap-6">
      {/* 페이지 헤더 — 뒤로 가기(이전 화면) + 제목. 로딩·오류일 때도 유지된다. */}
      <div className="flex items-center gap-4">
        <Button variant="outline" leftIcon={ArrowLeft} onClick={() => navigate(-1)}>
          뒤로 가기
        </Button>
        <h1 className="text-title-lg text-gray-950">공지 수정</h1>
      </div>

      {/* 로딩·오류 상태 — 조회가 끝나기 전/실패 시 폼·첨부 섹션은 렌더하지 않는다.
          빈 폼을 먼저 보여 주면 사용자가 기존 내용이 지워진 것으로 읽고 그대로 저장할 수 있다. */}
      {isLoading ? (
        <div className="flex flex-col gap-4 rounded-lg border border-gray-200 bg-white p-6 shadow-sm">
          {/* 실제 폼 필드 구조(입력 44px + 텍스트영역)를 그대로 흉내 내 화면이 뒤바뀌는 폭을 줄인다.
              Skeleton 은 inline-block 이라 폭을 주지 않으면 접히므로 block + w-full 로 늘린다. */}
          <Skeleton className="block w-full" height={44} />
          <Skeleton className="block w-full" height={236} />
        </div>
      ) : error || !notice ? (
        <div className="rounded-lg border border-gray-200 bg-white px-6 shadow-sm">
          <ErrorState title="공지를 불러오지 못했습니다." onRetry={() => void refetch()} />
        </div>
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

          {/* 첨부파일 관리 — 폼과 구분된 별도 카드. 작성 화면에는 이 섹션이 없다. */}
          <section
            aria-label="첨부파일 관리"
            className="flex flex-col gap-4 rounded-lg border border-gray-200 bg-white p-6 shadow-sm"
          >
            <div className="flex items-center justify-between gap-4">
              <h2 className="text-title-sm text-gray-950">첨부파일</h2>
              <Button
                type="button"
                variant="outline"
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

            {/* 목록·0건 안내는 UI-112 AttachmentList 가 배타적으로 렌더한다(SCREEN-031 과 공유).
                크기는 이미 서식화된 문자열로 넘긴다 — 괄호 표기는 기존 화면 표기를 유지한 것. */}
            <AttachmentList
              action="delete"
              emptyMessage="첨부된 파일이 없습니다."
              items={notice.attachments.map((a) => ({
                id: a.attachSn,
                name: a.fileName,
                size: `(${formatFileSize(a.fileSize)})`,
              }))}
              onAction={(_item, index) => handleDeleteAttachment(notice.attachments[index])}
            />
          </section>
        </>
      )}
    </div>
  );
}
