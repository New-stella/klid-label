/**
 * 조달 착수 훅.
 *
 * <h3>★ 착수 응답을 조회 캐시에 그대로 앉힌다</h3>
 * 착수와 조회가 같은 모양을 돌려주므로(`api.ts` 참조) 착수 직후 화면이 곧바로 진행 중을 보고
 * 폴링을 시작할 수 있다. <b>무효화(invalidate)가 아니라 주입(setQueryData)</b>인 이유는, 무효화만
 * 하면 방금 받은 사실을 버리고 한 번 더 왕복해야 화면이 움직이기 때문이다.
 *
 * ⚠ 서버가 <b>이미 준비 완료·진행 중이면 새로 시작하지 않고 그 상태를 답한다</b>(멱등). 그래서
 *   착수 응답이 `IN_PROGRESS` 가 아닐 수 있고, 그것이 정상이다 — 화면이 「착수했으니 진행 중일
 *   것」으로 단정하지 않고 받은 값을 그대로 쓴다.
 *
 * @design INT-014
 */

import { useMutation, useQueryClient } from '@tanstack/react-query';

import { PORTAL_KEYS } from '@/lib/queryKeys';

import { startDatasetMaterials } from '../api';
import type { PortalMaterialsStatus } from '../types';

export function useStartDatasetMaterials(datasetId: number | undefined) {
  const qc = useQueryClient();

  return useMutation<PortalMaterialsStatus, unknown, void>({
    mutationFn: () => startDatasetMaterials(datasetId as number),
    onSuccess: (status) => {
      if (datasetId === undefined) return;
      qc.setQueryData(PORTAL_KEYS.datasetMaterials(datasetId), status);
    },
  });
}
