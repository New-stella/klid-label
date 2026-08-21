// 검사(미리보기)·적재 훅 — 컴포넌트가 useMutation 을 직접 부르지 않도록 감싼다.
//
// @design API-205 API-206

import { useMutation, useQueryClient } from '@tanstack/react-query';

import { IMPORT_KEYS } from '@/lib/queryKeys';

import { createImport, scanImportFolder } from '../api';
import type {
  ImportCreateRequest,
  ImportCreateResult,
  ImportScanRequest,
  ImportScanResult,
} from '../types';

/**
 * 산출물 폴더 검사.
 *
 * 아무것도 저장하지 않으므로 성공해도 캐시를 무효화하지 않는다 — 서버 상태가 달라지지 않는다.
 */
export function useImportScan(options?: {
  onSuccess?: (result: ImportScanResult) => void;
  onError?: (e: unknown) => void;
}) {
  return useMutation({
    mutationFn: (body: ImportScanRequest) => scanImportFolder(body),
    onSuccess: (result) => options?.onSuccess?.(result),
    onError: (e) => options?.onError?.(e),
  });
}

/**
 * 외부 산출물 적재.
 *
 * 성공하면 이관 이력이 한 건 늘어나므로 이력 캐시를 무효화한다. 게이트가 닫히는 변화가 아니라
 * 최신값을 다시 받으면 되는 변화이므로 `invalidateQueries` 다.
 */
export function useCreateImport(options?: {
  onSuccess?: (result: ImportCreateResult) => void;
  onError?: (e: unknown) => void;
}) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: ImportCreateRequest) => createImport(body),
    onSuccess: (result) => {
      queryClient.invalidateQueries({ queryKey: IMPORT_KEYS.historyLists() });
      options?.onSuccess?.(result);
    },
    onError: (e) => options?.onError?.(e),
  });
}
