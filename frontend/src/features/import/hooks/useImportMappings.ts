// 분류 대응 조회·확정·해제 훅.
//
// @design API-209 API-210 API-211

import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import { IMPORT_KEYS } from '@/lib/queryKeys';

import { createImportMappings, disableImportMapping, listImportMappings } from '../api';
import type {
  ImportMappingCreateRequest,
  ImportMappingSaveResult,
  ListImportMappingsParams,
} from '../types';

/** 확정된 분류 대응 목록 — 쪽 단위. */
export function useImportMappings(params: ListImportMappingsParams = {}) {
  return useQuery({
    queryKey: IMPORT_KEYS.mappingList(params as Record<string, unknown>),
    queryFn: () => listImportMappings(params),
    placeholderData: keepPreviousData,
  });
}

/**
 * 분류 대응 확정.
 *
 * 성공하면 대응 목록이 달라지고 그 분류는 다음 검사에서 처음 보는 분류로 나오지 않는다.
 * 대응 캐시를 무효화한다 — 검사 결과는 캐시가 아니라 화면 상태라 여기서 다루지 않는다.
 */
export function useCreateImportMappings(options?: {
  onSuccess?: (result: ImportMappingSaveResult) => void;
  onError?: (e: unknown) => void;
}) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: ImportMappingCreateRequest) => createImportMappings(body),
    onSuccess: (result) => {
      queryClient.invalidateQueries({ queryKey: IMPORT_KEYS.mappingLists() });
      options?.onSuccess?.(result);
    },
    onError: (e) => options?.onError?.(e),
  });
}

/**
 * 분류 대응 해제 — 호출부가 확인 단계를 거친 뒤에만 부른다.
 *
 * 되돌리면 그 분류가 다시 처음 보는 분류가 되어 다음 산출물을 가져올 때 적재가 막힌다.
 */
export function useDisableImportMapping(options?: {
  onSuccess?: () => void;
  onError?: (e: unknown) => void;
}) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (mpngSn: number) => disableImportMapping(mpngSn),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: IMPORT_KEYS.mappingLists() });
      options?.onSuccess?.();
    },
    onError: (e) => options?.onError?.(e),
  });
}
