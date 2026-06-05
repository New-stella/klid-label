import { useMutation, useQueryClient } from '@tanstack/react-query';

import { NOTICE_KEYS } from '@/lib/queryKeys';

import {
  createNotice,
  deleteAttachment,
  deleteNotice,
  publishNotice,
  unpublishNotice,
  updateNotice,
  uploadAttachment,
} from '../api';
import type { NoticeForm } from '../types';

/**
 * 공지 쓰기 작업 mutation 묶음 (REVIEWER 전용 — BE @PreAuthorize 가드와 짝).
 * 성공 시 목록/상세 캐시를 무효화한다.
 */
export function useNoticeActions() {
  const qc = useQueryClient();

  const invalidateLists = () =>
    qc.invalidateQueries({ queryKey: NOTICE_KEYS.lists() });
  const invalidateDetail = (id: number) =>
    qc.invalidateQueries({ queryKey: NOTICE_KEYS.detail(id) });

  const create = useMutation({
    mutationFn: (form: NoticeForm) => createNotice(form),
    onSuccess: invalidateLists,
  });

  const update = useMutation({
    mutationFn: ({ id, form }: { id: number; form: NoticeForm }) =>
      updateNotice(id, form),
    onSuccess: (_data, { id }) => {
      invalidateLists();
      invalidateDetail(id);
    },
  });

  const remove = useMutation({
    mutationFn: (id: number) => deleteNotice(id),
    onSuccess: invalidateLists,
  });

  const publish = useMutation({
    mutationFn: (id: number) => publishNotice(id),
    onSuccess: (_data, id) => {
      invalidateLists();
      invalidateDetail(id);
    },
  });

  const unpublish = useMutation({
    mutationFn: (id: number) => unpublishNotice(id),
    onSuccess: (_data, id) => {
      invalidateLists();
      invalidateDetail(id);
    },
  });

  const uploadAttach = useMutation({
    mutationFn: ({ id, file }: { id: number; file: File }) =>
      uploadAttachment(id, file),
    onSuccess: (_data, { id }) => invalidateDetail(id),
  });

  const removeAttach = useMutation({
    mutationFn: ({ id, attachId }: { id: number; attachId: number }) =>
      deleteAttachment(id, attachId),
    onSuccess: (_data, { id }) => invalidateDetail(id),
  });

  return {
    create,
    update,
    remove,
    publish,
    unpublish,
    uploadAttach,
    removeAttach,
  };
}
