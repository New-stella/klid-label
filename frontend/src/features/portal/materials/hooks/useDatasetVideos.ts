/**
 * 데이터셋 영상 목록 조회 — 등록이 진행 중이면 스스로 다시 묻는다. @design API-253 @design SCREEN-047
 *
 * ★ `enabled` 가 거짓이면 부르지 않는다 — 소재가 준비되기 전에 부르면 서버가 409 로 거부한다.
 *   준비 완료 판정은 상태 조회가 소유하고, 이 훅은 그 판정을 받아 쓰기만 한다.
 *
 * 폴링은 조달 상태 훅과 같은 모양이다: 진행 중을 처음 본 시점부터 예산을 세고, 예산이 다하면 자동
 * 질문만 멈춘다(사람이 누르는 「다시 확인」은 남는다).
 */

import { useQuery } from '@tanstack/react-query';
import { useRef } from 'react';

import { PORTAL_KEYS } from '@/lib/queryKeys';

import { getDatasetVideos } from '../api';
import { datasetVideosPollIntervalFor } from '../polling';
import { PortalDatasetVideoRegistrationState, type PortalDatasetVideoPage } from '../types';

/** 한 페이지 영상 수. */
export const DATASET_VIDEOS_PAGE_SIZE = 20;

export function useDatasetVideos(datasetId: number, page: number, enabled: boolean) {
  const seenRef = useRef<{ key: string; at: number }>({ key: '', at: 0 });
  const key = `${datasetId}:${page}`;

  return useQuery<PortalDatasetVideoPage>({
    queryKey: PORTAL_KEYS.datasetVideos(datasetId, page),
    queryFn: () => getDatasetVideos(datasetId, { page, size: DATASET_VIDEOS_PAGE_SIZE }),
    enabled,
    retry: false,
    refetchInterval: (query) => {
      const state = query.state.data?.registrationState;
      if (state !== PortalDatasetVideoRegistrationState.IN_PROGRESS) {
        seenRef.current = { key, at: 0 };
        return datasetVideosPollIntervalFor(state, 0);
      }
      const seen = seenRef.current;
      if (seen.key !== key || seen.at === 0) {
        seenRef.current = { key, at: Date.now() };
        return datasetVideosPollIntervalFor(state, 0);
      }
      return datasetVideosPollIntervalFor(state, Date.now() - seen.at);
    },
  });
}
