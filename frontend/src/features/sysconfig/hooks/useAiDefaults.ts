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
 *
 * ★<b>「역할 게이트를 두지 않는다」는 두 역할을 두고 한 말이다.</b> 그 뒤 <b>포털 사용자</b>가
 * 이 화면에 들어오게 됐는데 그 역할은 검수자도 작업자도 아니라, 조건 없는 호출이 <b>진입마다
 * 403</b> 을 낸다(실측 2건 — 재시도까지 두 번). 값이 프리필이라 화면은 안 깨지지만, 그것은
 * 「고쳐도 된다」가 아니라 「조용히 쌓인다」는 뜻이다 — 위 주석이 고쳤다고 적은 결함이 채널
 * 하나 옆에서 그대로 재발한 것이다.
 *
 * 그래서 <b>호출 여부를 호출부가 정한다</b>. 여기서 채널을 판정하지 않는 이유는 이 훅이 시스템
 * 설정 도메인이고, 「이 화면에서 AI 도구를 쓰는가」를 아는 것은 화면이기 때문이다.
 *
 * @param enabled 조회할지. 기본값 `true` — 기존 호출부의 동작이 바뀌지 않는다.
 */
export function useAiDefaults(enabled = true) {
  return useQuery<AiDefaults>({
    queryKey: SYSCONFIG_KEYS.aiDefaults(),
    queryFn: getAiDefaults,
    enabled,
  });
}
