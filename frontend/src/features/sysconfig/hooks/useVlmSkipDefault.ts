// 시계열 위탁 **전체 건너뛰기** 설정 읽기. [@design ADR-050] [@design API-068]
//
// ★ 이 값은 시스템 설정 화면 밖(영상 처리 현황의 상시 배너)에서도 읽힌다 — 그래서 두 화면이
//   각자 키를 꺼내 해석하지 않도록 판정을 이 훅 하나로 모은다.
//
// ⚠ `GET /v1/manage/configs` 는 **REVIEWER 전용**이다(SecurityConfig `/v1/manage/**` + 컨트롤러
//   `@PreAuthorize`). WORKER 화면에서 조건 없이 부르면 진입할 때마다 403 이 쌓이므로, 호출부가
//   `enabled` 로 **호출 자체를 막는다**(실패를 삼키면 콘솔만 조용해지고 왕복은 그대로 남는다).

import { ConfigKey, isConfigOn } from '../types';

import { useConfigStrings } from './useConfigStrings';

export interface VlmSkipDefault {
  /** 스위치가 켜져 있는가. 설정 행이 없으면 거짓이다(그 상태가 곧 꺼짐이다). */
  on: boolean;
  /** 설정에 적힌 사유 원문. 없으면 빈 문자열. */
  reason: string;
  isLoading: boolean;
}

export function useVlmSkipDefault(options: { enabled?: boolean } = {}): VlmSkipDefault {
  const { data, isLoading } = useConfigStrings({ enabled: options.enabled });

  return {
    on: isConfigOn(data?.[ConfigKey.BATCH_VLM_SKIP_BY_DEFAULT]),
    reason: data?.[ConfigKey.BATCH_VLM_SKIP_BY_DEFAULT_REASON] ?? '',
    isLoading,
  };
}
