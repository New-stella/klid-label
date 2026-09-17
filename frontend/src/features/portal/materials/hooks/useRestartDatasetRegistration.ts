/**
 * 데이터셋 영상 등록 재착수 훅. [@design API-262] [@design SCREEN-046]
 *
 * <h3>★ 무효화(invalidate)다 — 주입(setQueryData)이 아니다</h3>
 * 조달 착수 훅은 응답을 캐시에 앉히지만, 재착수 응답은 목록 응답과 <b>모양이 다르다</b>(등록 상태만
 * 있고 영상 페이지가 없다). 앉힐 자리가 없으므로 해당 데이터셋의 영상 목록 <b>전 페이지</b>를
 * 무효화해 목록 조회가 다시 돌게 한다 — 그 응답이 `IN_PROGRESS` 면 화면이 등록 중 판으로 바뀌고
 * 등록 중 폴링이 재개된다.
 *
 * ⚠ 서버가 <b>이미 완료·진행 중이면 새로 시작하지 않고 그 상태를 답한다</b>(멱등). 응답을 화면이
 *   해석하지 않는다 — 재조회한 목록이 진실이다.
 *
 * ⚠ `removeQueries` 가 아니라 `invalidateQueries` 다 — 재착수는 게이트를 닫는 변화가 아니라
 *   최신값을 다시 받아야 하는 변화다(캐시에 남은 것은 실패 상태와 빈 목록뿐이다).
 */

import { useMutation, useQueryClient } from '@tanstack/react-query';

import { PORTAL_KEYS } from '@/lib/queryKeys';

import { restartDatasetRegistration } from '../api';
import type { PortalDatasetRegistrationResult } from '../types';

export function useRestartDatasetRegistration(datasetId: number) {
  const qc = useQueryClient();

  return useMutation<PortalDatasetRegistrationResult, unknown, void>({
    mutationFn: () => restartDatasetRegistration(datasetId),
    onSuccess: () =>
      /* 접두 키 — 이 데이터셋의 영상 목록 전 페이지가 한 번에 무효화된다. */
      qc.invalidateQueries({ queryKey: PORTAL_KEYS.datasetVideosOf(datasetId) }),
  });
}
