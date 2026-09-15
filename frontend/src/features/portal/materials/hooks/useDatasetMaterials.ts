/**
 * 조달 상태 조회 훅 — 진행 중인 동안만 스스로 다시 묻는다.
 *
 * <h3>★ 폴링 판정은 이 파일이 갖지 않는다</h3>
 * 간격·예산은 `polling.ts` 가 소유하고 여기서는 <b>경과 시간</b>만 재서 넘긴다. 판정을 훅 안에
 * 박으면 그 축을 순수 단언으로 고정할 수 없어, 「예산을 다 써도 계속 돈다」는 회귀가 화면 시험을
 * 그대로 통과한다.
 *
 * <h3>경과의 기산점</h3>
 * <b>진행 중을 처음 본 시점</b>이다. 데이터셋이 바뀌면 다시 센다 — 안 그러면 앞 데이터셋에서 쓴
 * 예산이 새 데이터셋의 조달을 곧바로 멈춘다.
 *
 * <h3>재시도하지 않는다</h3>
 * 인가 거부·없는 데이터셋은 되풀이해야 같은 답이 온다. 조달 자체의 일시 장애는 <b>응답 본문의
 * 실패 상태</b>로 오지 오류로 오지 않으므로, HTTP 오류를 되풀이할 이유가 없다.
 *
 * @design INT-014
 */

import { useQuery } from '@tanstack/react-query';
import { useRef } from 'react';

import { PORTAL_KEYS } from '@/lib/queryKeys';

import { getDatasetMaterials } from '../api';
import { materialsPollIntervalFor } from '../polling';
import { PortalMaterialsState, type PortalMaterialsStatus } from '../types';

export function useDatasetMaterials(datasetId: number | undefined) {
  // 진행 중을 처음 본 시각. 데이터셋이 바뀌거나 진행 중이 아니게 되면 되감는다.
  const seenRef = useRef<{ datasetId: number | undefined; at: number }>({
    datasetId: undefined,
    at: 0,
  });

  return useQuery<PortalMaterialsStatus>({
    queryKey: PORTAL_KEYS.datasetMaterials(datasetId ?? -1),
    queryFn: () => getDatasetMaterials(datasetId as number),
    enabled: datasetId !== undefined,
    retry: false,
    refetchInterval: (query) => {
      const state = query.state.data?.state;
      if (state !== PortalMaterialsState.IN_PROGRESS) {
        seenRef.current = { datasetId, at: 0 };
        return materialsPollIntervalFor(state, 0);
      }
      const seen = seenRef.current;
      if (seen.datasetId !== datasetId || seen.at === 0) {
        seenRef.current = { datasetId, at: Date.now() };
        return materialsPollIntervalFor(state, 0);
      }
      return materialsPollIntervalFor(state, Date.now() - seen.at);
    },
  });
}
