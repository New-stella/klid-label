import { useEffect, useRef } from 'react';
import { useQueryClient } from '@tanstack/react-query';

import { AUGMENT_KEYS } from '@/lib/queryKeys';

import type { AugmentProgressStatus } from '../types';

/** 더는 바뀌지 않는 진행 상태 — 이 시점에 결과 페이로드가 바뀐다(파생 생성/실패/취소 확정). */
const TERMINAL_STATUSES: ReadonlySet<string> = new Set([
  'SUCCEEDED',
  'FAILED',
  'CANCELED',
]);

export function isTerminalProgressStatus(
  status: AugmentProgressStatus | string | null | undefined,
): boolean {
  return typeof status === 'string' && TERMINAL_STATUSES.has(status);
}

/**
 * 진행 상태가 **종결로 관측되면 결과 조회를 1회 무효화**한다 — 결과 화면의 자동 갱신 경로.
 *
 * <h3>왜 결과 쿼리에 폴링을 새로 달지 않았나 (택한 방향의 근거)</h3>
 * 결과 화면은 "잠시 후 자동으로 결과가 표시됩니다" 라고 말하면서 정작 갱신 경로가 없었다. 고치는
 * 길은 둘이었다 — ①결과 쿼리 자체를 폴링 ②이미 있는 진행률 폴링이 종결을 관측한 시점에 결과를
 * 무효화. **②를 택했다.**
 *
 * - 결과 API 는 폴링 힌트(`nextPollAfterMs`)를 주지 않는다. ①을 택하면 화면이 주기를 **임의로**
 *   정해야 하는데, 서버에 속도 제한(RateLimiter)이 없다는 것이 확정 사항이라 임의 주기는 곧
 *   무방비 증폭이다. 진행률 폴링은 이미 서버 힌트를 따르고 종결 시 스스로 멈추므로, 거기에
 *   얹으면 **새 요청 주기를 하나도 만들지 않고** 갱신 시점만 얻는다.
 * - 결과 조회는 프레임 쌍 + 항목 두 축을 모두 훑는 무거운 조회라 등간격 재조회 대상이 아니다.
 *
 * <h3>무효화 루프가 없다</h3>
 * 무효화 대상은 **결과 prefix(`AUGMENT_KEYS.details()`)뿐**이라 진행률 쿼리를 건드리지 않는다.
 * 결과가 갱신돼도 진행률은 재요청되지 않으므로 "결과→진행률→결과" 순환이 성립하지 않는다.
 * 같은 종결 상태로는 다시 무효화하지 않도록 마지막 관측값을 기억한다(리렌더마다 재발행 금지).
 *
 * <h3>전이가 아니라 "관측"인 이유</h3>
 * 마운트 시점에 이미 종결인 경우도 갱신해야 한다. 결과 페이로드는 진입 직전에 받은 값이라
 * `PROCESSING` 인데 진행률은 `완료 100%` 인 **한 화면 안의 자기모순**이 바로 그 상태다.
 * 전이(비종결→종결)만 잡으면 이 케이스를 영영 못 고친다. 대신 종결 잡에서 마운트당 결과 조회가
 * 1회 더 발생하는데, 같은 키의 동시 재조회는 React Query 가 합치므로 항목 수만큼 늘지 않는다.
 */
export function useResultRefreshOnProgressTerminal(
  status: AugmentProgressStatus | undefined,
): void {
  const qc = useQueryClient();
  const lastTerminalRef = useRef<string | null>(null);

  useEffect(() => {
    if (!isTerminalProgressStatus(status)) {
      // 비종결로 되돌아오면(재요청 등) 다음 종결을 다시 반영할 수 있게 기억을 비운다.
      lastTerminalRef.current = null;
      return;
    }
    if (lastTerminalRef.current === status) return;
    lastTerminalRef.current = status ?? null;
    void qc.invalidateQueries({ queryKey: AUGMENT_KEYS.details() });
  }, [status, qc]);
}
