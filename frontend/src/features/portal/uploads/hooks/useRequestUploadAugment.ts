/**
 * 포털 업로드 영상 **증강 요청 접수**(API-231) mutation.
 *
 * <h3>성공은 접수 사실일 뿐이다</h3>
 * 증강은 서버가 외부로 보내는 비동기 위탁이라 응답이 결과를 뜻하지 않는다. 그래서 이 훅은
 * 결과를 기다리지 않고, 접수가 끝나면 **요청 현황 목록을 다시 받아 오게만** 한다.
 *
 * ⚠ 캐시는 **버리지 않고 무효화**한다(`invalidateQueries`). 이 저장소에는 「게이트가 닫히는
 *   변화는 `removeQueries` 로 캐시를 제거한다」는 규칙이 있는데, 판정 기준은 *그 변화로 서버가
 *   이후 요청을 거부하게 되는가* 다. 요청 접수는 서버가 무엇도 거부하게 만들지 않고 목록에 행이
 *   하나 느는 것뿐이라 **최신값을 다시 받으면 되는 축**이다.
 *
 * @design API-231
 * @design SCREEN-033
 */
import { useCallback } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';

import { PORTAL_KEYS } from '@/lib/queryKeys';

import { requestUploadAugment, type RequestUploadAugmentBody } from '../api';

export interface RequestUploadAugmentVars {
  uldSn: number;
  body: RequestUploadAugmentBody;
}

export interface UseRequestUploadAugmentResult {
  requestAsync: (vars: RequestUploadAugmentVars) => Promise<unknown>;
  isPending: boolean;
  error: unknown;
  reset: () => void;
}

export function useRequestUploadAugment(): UseRequestUploadAugmentResult {
  const qc = useQueryClient();

  const mutation = useMutation({
    mutationFn: ({ uldSn, body }: RequestUploadAugmentVars) => requestUploadAugment(uldSn, body),
    onSuccess: () => {
      // 방금 낸 요청이 현황 목록에 보여야 한다 — 페이징 파라미터가 키에 들어 있어 쪽마다 키가
      // 달라지므로 개별 키가 아니라 포털 축을 통째로 무효화한다.
      qc.invalidateQueries({ queryKey: PORTAL_KEYS.all });
    },
  });

  const requestAsync = useCallback(
    (vars: RequestUploadAugmentVars) => mutation.mutateAsync(vars),
    [mutation],
  );

  return {
    requestAsync,
    isPending: mutation.isPending,
    error: mutation.error,
    reset: mutation.reset,
  };
}
