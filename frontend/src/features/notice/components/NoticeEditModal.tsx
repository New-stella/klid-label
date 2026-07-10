// 화면ID: KLID-AT-SC-032 — 공지 작성/수정 모달 (REVIEWER 전용)
import { zodResolver } from '@hookform/resolvers/zod';
import { AlertCircle, Paperclip, Trash2, Upload } from 'lucide-react';
import { useEffect, useRef } from 'react';
import { useForm } from 'react-hook-form';

import { Button } from '@/components/common/Button';
import { Checkbox } from '@/components/common/Checkbox';
import { Modal } from '@/components/common/Modal';
import { KRDS_FOCUS } from '@/lib/focusRing';

import { noticeSchema, type NoticeFormValues } from '../schemas';
import type { Notice, NoticeAttach, NoticeForm } from '../types';

export interface NoticeEditModalProps {
  open: boolean;
  onClose: () => void;
  /** 수정 대상 — 없으면 신규 작성. */
  initial?: Notice;
  onSubmit: (form: NoticeForm) => void;
  submitting?: boolean;
  /** 첨부 업로드 (수정 모드에서만 노출 — 신규는 저장 후 첨부 가능). */
  onUploadAttachment?: (file: File) => void;
  onDeleteAttachment?: (attach: NoticeAttach) => void;
  attachUploading?: boolean;
}

const EMPTY_FORM: NoticeFormValues = {
  title: '',
  content: '',
  pinned: false,
};

function formatFileSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}

/**
 * 공지 작성/수정 모달.
 * - 제목(필수, 200자) / 내용(필수) / 상단 고정 체크
 * - 수정 모드: 기존 첨부 목록 + 첨부 업로드 (신규 작성은 저장 후 상세에서 첨부)
 */
export function NoticeEditModal({
  open,
  onClose,
  initial,
  onSubmit,
  submitting,
  onUploadAttachment,
  onDeleteAttachment,
  attachUploading,
}: NoticeEditModalProps) {
  const isEdit = !!initial;
  const fileInputRef = useRef<HTMLInputElement>(null);

  const {
    register,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<NoticeFormValues>({
    resolver: zodResolver(noticeSchema),
    defaultValues: EMPTY_FORM,
  });

  useEffect(() => {
    if (!open) return;
    if (initial) {
      reset({
        title: initial.title,
        content: initial.content,
        pinned: initial.pinned,
      });
    } else {
      reset(EMPTY_FORM);
    }
  }, [open, initial, reset]);

  const submit = handleSubmit((form) => {
    onSubmit({
      title: form.title.trim(),
      content: form.content,
      pinned: form.pinned,
    });
  });

  const handleFileChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (file && onUploadAttachment) onUploadAttachment(file);
    // 동일 파일 재선택 가능하도록 초기화
    e.target.value = '';
  };

  return (
    <Modal
      open={open}
      onClose={onClose}
      size="lg"
      title={isEdit ? '공지 수정' : '새 공지 작성'}
      footer={
        <>
          <Button
            type="button"
            variant="secondary"
            onClick={onClose}
            disabled={submitting}
          >
            취소
          </Button>
          <Button
            type="button"
            variant="primary"
            onClick={submit}
            loading={submitting}
          >
            {isEdit ? '저장' : '작성'}
          </Button>
        </>
      }
    >
      <form
        className="space-y-5"
        onSubmit={(e) => {
          e.preventDefault();
          submit();
        }}
      >
        {/* Title */}
        <div className="space-y-1.5">
          <label
            className="block text-sm font-medium text-gray-700"
            htmlFor="notice-title"
          >
            제목 <span className="text-danger">*</span>
          </label>
          <input
            id="notice-title"
            type="text"
            placeholder="공지 제목을 입력하세요 (최대 200자)"
            className={[
              `w-full text-sm border rounded-lg px-3 py-2 ${KRDS_FOCUS}`,
              errors.title ? 'border-danger' : 'border-gray-300',
            ].join(' ')}
            {...register('title')}
          />
          {errors.title?.message && (
            <p className="flex items-center gap-1 text-xs text-danger" role="alert">
              <AlertCircle className="h-3.5 w-3.5 shrink-0" aria-hidden="true" />
              {errors.title.message}
            </p>
          )}
        </div>

        {/* Content */}
        <div className="space-y-1.5">
          <label
            className="block text-sm font-medium text-gray-700"
            htmlFor="notice-content"
          >
            내용 <span className="text-danger">*</span>
          </label>
          <textarea
            id="notice-content"
            placeholder="공지 내용을 입력하세요."
            rows={8}
            className={[
              `w-full text-sm border rounded-lg px-3 py-2 resize-y ${KRDS_FOCUS}`,
              errors.content ? 'border-danger' : 'border-gray-300',
            ].join(' ')}
            {...register('content')}
          />
          {errors.content?.message && (
            <p className="flex items-center gap-1 text-xs text-danger" role="alert">
              <AlertCircle className="h-3.5 w-3.5 shrink-0" aria-hidden="true" />
              {errors.content.message}
            </p>
          )}
        </div>

        {/* Pinned */}
        <div>
          <Checkbox label="상단 고정" {...register('pinned')} />
        </div>

        {/* Attachments — 수정 모드에서만 (신규는 id 미발급) */}
        {isEdit && (
          <div className="space-y-2 border-t border-gray-100 pt-4">
            <div className="flex items-center justify-between">
              <span className="text-sm font-medium text-gray-700">첨부파일</span>
              <Button
                type="button"
                variant="secondary"
                size="sm"
                leftIcon={Upload}
                loading={attachUploading}
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
            {initial.attachments.length === 0 ? (
              <p className="text-xs text-gray-400">첨부된 파일이 없습니다.</p>
            ) : (
              <ul className="space-y-1">
                {initial.attachments.map((a) => (
                  <li
                    key={a.attachSn}
                    className="flex items-center justify-between rounded border border-gray-100 bg-gray-50 px-3 py-1.5"
                  >
                    <span className="flex items-center gap-2 text-sm text-gray-700">
                      <Paperclip size={14} aria-hidden />
                      <span className="truncate max-w-[280px]">{a.fileName}</span>
                      <span className="text-xs text-gray-400">
                        ({formatFileSize(a.fileSize)})
                      </span>
                    </span>
                    {onDeleteAttachment && (
                      <button
                        type="button"
                        aria-label={`${a.fileName} 삭제`}
                        onClick={() => onDeleteAttachment(a)}
                        className="inline-flex h-7 w-7 items-center justify-center rounded text-gray-400 hover:bg-danger/10 hover:text-danger transition-colors"
                      >
                        <Trash2 size={14} aria-hidden />
                      </button>
                    )}
                  </li>
                ))}
              </ul>
            )}
          </div>
        )}
      </form>
    </Modal>
  );
}
