import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import { EXPORT_KEYS } from '@/lib/queryKeys';

import {
  getExportStatus,
  listDatasets,
  listRecentExports,
  prepareExport,
} from '../api';
import type { PrepareExportRequest } from '../types';

interface MutationOptions<T> {
  onSuccess?: (data: T) => void;
  onError?: (err: unknown) => void;
}

/**
 * 데이터셋 옵션 목록 (라디오).
 */
export function useDatasets() {
  return useQuery({
    queryKey: [...EXPORT_KEYS.all, 'datasets'] as const,
    queryFn: () => listDatasets(),
  });
}

/**
 * 내보내기 상태 조회 (id 진입 후).
 */
export function useExportStatus(id: number | undefined) {
  return useQuery({
    queryKey:
      id !== undefined ? EXPORT_KEYS.detail(id) : EXPORT_KEYS.all,
    queryFn: () => getExportStatus(id as number),
    enabled: id !== undefined,
  });
}

/**
 * 최근 내보내기 이력 (사이드 카드).
 */
export function useRecentExports(size = 5) {
  return useQuery({
    queryKey: [...EXPORT_KEYS.all, 'recent', size] as const,
    queryFn: () => listRecentExports(size),
  });
}

/**
 * 미리보기/내보내기 실행 mutation.
 */
export function usePrepareExport(
  options: MutationOptions<{ exportId: number }> = {},
) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: PrepareExportRequest) => prepareExport(body),
    onSuccess: (data) => {
      qc.invalidateQueries({ queryKey: EXPORT_KEYS.all });
      options.onSuccess?.(data);
    },
    onError: options.onError,
  });
}
