import { useQuery } from '@tanstack/react-query';

import { SYSCONFIG_KEYS } from '@/lib/queryKeys';

import { getAiDefaults } from '../api';
import type { AiDefaults } from '../types';

/**
 * AI 정밀도 기본값 조회 — 라벨링 화면의 AI 도구 슬라이더 초기값.
 *
 * ★ 역할 게이트를 두지 않는다. `useConfigs` 는 `GET /v1/manage/configs`(검수자 전용)를 부르므로
 * 작업자 화면에서 `enabled: isReviewer` 로 막아야 했고, 그 결과 작업자는 저장된 기본값을 아예
 * 받지 못했다. 이 조회는 검수자·작업자 모두 200 이라 조건 없이 호출한다.
 *
 * 조회 실패·값 없음이면 `undefined` 가 흘러 소비 컴포넌트가 자체 상수로 폴백한다 — 이 값은
 * **초기값 프리필**일 뿐이므로 없다고 화면이 깨져서는 안 된다.
 */
export function useAiDefaults() {
  return useQuery<AiDefaults>({
    queryKey: SYSCONFIG_KEYS.aiDefaults(),
    queryFn: getAiDefaults,
  });
}
