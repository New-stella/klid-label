// 비식별 완료 기록 훅.
//
// @design API-215

import { useMutation, useQueryClient } from '@tanstack/react-query';

import { IMPORT_KEYS } from '@/lib/queryKeys';

import { recordDeidentComplete } from '../api';
import type { DeidentCompleteResult } from '../types';

/**
 * 외부에서 비식별한 산출물을 기록해 검수 승인 보류를 푼다.
 *
 * 성공하면 그 영상의 승인 보류 여부가 달라지므로 이력 목록을 무효화한다 — 목록이 보류 여부를
 * 싣고 있고 그 값이 다음 조작(다시 기록할 수 있는가)의 근거이기 때문이다.
 */
export function useRecordDeidentComplete(options?: {
  onSuccess?: (result: DeidentCompleteResult) => void;
  onError?: (e: unknown) => void;
}) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({
      rawSn,
      deidentifiedFolderPath,
    }: {
      rawSn: number;
      deidentifiedFolderPath: string;
    }) => recordDeidentComplete(rawSn, { deidentifiedFolderPath }),
    onSuccess: (result) => {
      queryClient.invalidateQueries({ queryKey: IMPORT_KEYS.historyLists() });
      options?.onSuccess?.(result);
    },
    onError: (e) => options?.onError?.(e),
  });
}
