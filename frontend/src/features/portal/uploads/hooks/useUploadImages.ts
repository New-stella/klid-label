// Phase 5 — 포털 이미지 다중 업로드 mutation (진행률 포함).
// 성공 시 업로드 목록 쿼리를 무효화하여 새 자산이 즉시 반영되게 한다.
import { useCallback, useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';

import { PORTAL_KEYS } from '@/lib/queryKeys';

import { uploadImages } from '../api';
import type { PortalUpload } from '../types';

export interface UseUploadImagesResult {
  /** 검증 통과 파일들을 업로드한다. */
  uploadAsync: (files: File[]) => Promise<PortalUpload[]>;
  isPending: boolean;
  /** 진행률 0~1. */
  progress: number;
  error: unknown;
}

export function useUploadImages(): UseUploadImagesResult {
  const qc = useQueryClient();
  const [progress, setProgress] = useState(0);

  const mutation = useMutation({
    mutationFn: (files: File[]) => {
      setProgress(0);
      return uploadImages(files, setProgress);
    },
    onSuccess: () => {
      setProgress(1);
      qc.invalidateQueries({ queryKey: PORTAL_KEYS.all });
    },
  });

  const uploadAsync = useCallback(
    (files: File[]) => mutation.mutateAsync(files),
    [mutation],
  );

  return {
    uploadAsync,
    isPending: mutation.isPending,
    progress,
    error: mutation.error,
  };
}
