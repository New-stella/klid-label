import { useQuery } from '@tanstack/react-query';

import { AUGMENT_KEYS } from '@/lib/queryKeys';

import { getAugmentProgress } from '../api';
import { progressPollInterval } from '../augmentPolling';
import type { AugmentProgress } from '../types';

/**
 * 증강 항목 진행상태 조회 — 서버 권고 주기로 폴링하고 **종결되거나 실패하면 스스로 멈춘다**.
 *
 * - 주기는 `progressPollInterval`(= 서버 `nextPollAfterMs` + 쿼리 상태)만 따른다.
 * - 외부 조회 실패는 BE 가 200 + `unavailableReason` 으로 degrade 하므로 재시도하지 않는다
 *   (진짜 오류인 4xx/5xx 만 실패로 남고, 화면은 결과 목록을 계속 보여준다).
 * - **에러 상태에서는 폴링을 멈춘다** — 데이터만 보고 주기를 재계산하면 실패 시 `data` 가 계속
 *   `undefined` 라 기본 주기로 영구 폴링한다. 복구 수단은 화면의 수동 재시도 버튼이다.
 * - 해상도 파생(RESL_*)은 외부 위탁이 없어 BE 가 400 을 주므로 호출부에서 `enabled=false` 로 막는다.
 */
export function useAugmentProgress(id: number | undefined, enabled = true) {
  return useQuery<AugmentProgress>({
    queryKey: AUGMENT_KEYS.progress(id ?? -1),
    queryFn: () => getAugmentProgress(id as number),
    enabled: enabled && id !== undefined,
    refetchInterval: (query) =>
      progressPollInterval(query.state.status, query.state.data),
    // 폴링 화면이라 창 복귀 때마다 추가 요청을 만들지 않는다(서버에 속도 제한이 없다).
    refetchOnWindowFocus: false,
    retry: false,
  });
}
