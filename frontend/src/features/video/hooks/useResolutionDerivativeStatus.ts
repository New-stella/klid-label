import { useQuery } from '@tanstack/react-query';

import { VIDEO_KEYS } from '@/lib/queryKeys';

import { listResolutionDerivatives } from '../api';
import {
  isDerivativeSettled,
  type ResolutionChangeResult,
} from '../types';

/**
 * 해상도 파생영상 확정 상태 폴링 간격(ms).
 *
 * 확정(파일 산출·라벨 복사)은 영상 길이에 비례해 수십 초~분 단위라, 배치 단계 폴링
 * ({@code BATCH_POLL_INTERVAL_MS} 5초)보다 느슨하게 잡는다.
 */
export const DERIVATIVE_POLL_INTERVAL_MS = 5000;

/**
 * 파생영상 확정 상태 캐시 키.
 *
 * `VIDEO_KEYS.all` **하위**에 두는 것이 핵심이다 — 해상도 변경 mutation
 * ({@code useResolutionDerivative})이 성공 시 `VIDEO_KEYS.all` 을 invalidate 하므로,
 * 새 파생을 요청하면 이 조회도 별도 배선 없이 자동으로 다시 돈다.
 *
 * (키 팩토리 정본은 `lib/queryKeys.ts` 지만 이번 변경 범위 밖이라 여기서 파생시킨다.
 *  `VIDEO_KEYS.all` 을 prefix 로 쓰므로 무효화 계보는 동일하다.)
 */
export const resolutionDerivativeKey = (rawSn: number) =>
  [...VIDEO_KEYS.all, 'resolution-derivatives', rawSn] as const;

/**
 * 폴링을 계속할지 판정한다 — 진행 중이면 간격(ms), 아니면 `false`(중지).
 *
 * <p>종료 판정은 {@link isDerivativeSettled} 한 곳에 위임한다(규칙을 복제하지 않는다).
 * <ul>
 *   <li>데이터 없음(첫 조회 전) → `false` — react-query 가 최초 fetch 는 따로 수행한다</li>
 *   <li>파생 0건(아직 요청 안 함 / 전부 스킵) → `false` — 기다릴 대상이 없다</li>
 *   <li>하나라도 미확정(CREATED·IN_PROGRESS) → 폴링 간격</li>
 *   <li>전부 확정(COMPLETED·FAILED) → `false`</li>
 * </ul>
 *
 * <p>★반드시 종료 상태에서 멈춘다. 멈추지 않으면 화면을 켜 둔 사용자마다 무한 요청이 되어
 * self-DoS 가 된다(CWE-770). 미지의 상태값도 "확정"으로 보아 멈추는 쪽이 fail-closed 다.
 */
export function derivativePollInterval(
  data: ResolutionChangeResult | undefined,
): number | false {
  const derivatives = data?.derivatives;
  if (!derivatives || derivatives.length === 0) return false;
  return derivatives.some((d) => !isDerivativeSettled(d.status))
    ? DERIVATIVE_POLL_INTERVAL_MS
    : false;
}

/**
 * 해상도 파생영상 확정 상태 조회 + 진행 중 자동 폴링 (E-ISSUE-24 / B8#46).
 *
 * <p>생성 mutation({@code useResolutionDerivative})은 **예약**까지만 알려준다. 확정 결과는 이
 * 조회로만 드러나므로, 요청 직후 화면이 이 훅으로 확정/실패까지 따라갈 수 있다.
 *
 * <p>기존 mutation 훅의 시그니처는 건드리지 않았다 — 다른 화면이 그대로 쓰고 있다.
 *
 * @param rawSn   원본 영상 PK. `null`/0 이하면 조회하지 않는다.
 * @param enabled 화면이 조회 시점을 직접 통제해야 할 때 사용(기본 true).
 */
export function useResolutionDerivativeStatus(
  rawSn: number | null,
  options?: { enabled?: boolean },
) {
  const id = rawSn ?? -1;
  return useQuery({
    queryKey: resolutionDerivativeKey(id),
    queryFn: () => listResolutionDerivatives(id),
    enabled: (options?.enabled ?? true) && rawSn !== null && rawSn > 0,
    // 확정 진행 중에만 폴링하고 종료 상태에 도달하면 반드시 멈춘다.
    refetchInterval: (query) => derivativePollInterval(query.state.data),
  });
}
