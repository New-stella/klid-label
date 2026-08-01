import type { AugmentProgress } from './types';

/**
 * 첫 응답 전(데이터 없음) 기본 폴링 주기(ms).
 * BE 의 접수 상태 권고값(`POLL_RECEIVED_MS`)과 같은 값이라 첫 왕복 이후 자연히 서버 힌트로 수렴한다.
 */
export const DEFAULT_PROGRESS_POLL_MS = 5000;

/**
 * 진행률 폴링 주기 산출 — **서버가 준 `nextPollAfterMs` 를 그대로 따른다**.
 *
 * 서버에 속도 제한(RateLimiter)이 없다는 것이 확정 사항이라, 폴링 증폭을 줄이는 수단은 이
 * 힌트뿐이다. 사유별로 값이 다르다(처리중 3s · 접수 5s · 일시장애 10s · 자체상한 15s ·
 * 미연동 30s). 화면이 임의 주기를 쓰면 그 방어가 통째로 사라진다.
 *
 * `0` 은 "더 폴링할 필요 없음(종결)" 이므로 `false` 로 바꿔 **폴링을 멈춘다** — 무한 폴링 금지.
 *
 * <h3>계약 밖 값은 전부 중단이다 (fail-closed — 주석과 코드가 같아야 한다)</h3>
 * 음수뿐 아니라 `NaN`·비수치(필드 누락·타입 오염)도 **중단**으로 해석한다. 구 구현은 이 경우
 * 오히려 가장 짧은 기본 주기(5s)로 폴백해, 주석이 선언한 fail-closed 와 정반대로 **가장 공격적인
 * 폴링**을 했다. 값을 신뢰할 수 없을 때는 멈추고, 사용자가 화면의 재시도 버튼으로 되살린다.
 *
 * `undefined`(첫 응답 전)만 기본 주기다 — 이건 "계약 밖 값" 이 아니라 아직 왕복이 없는 상태다.
 *
 * @returns 다음 폴링까지의 ms, 또는 `false`(중단)
 */
export function progressRefetchInterval(
  data: AugmentProgress | undefined,
): number | false {
  if (!data) return DEFAULT_PROGRESS_POLL_MS;
  const next = data.nextPollAfterMs;
  if (typeof next !== 'number' || !Number.isFinite(next)) {
    return false;
  }
  return next > 0 ? next : false;
}

/** TanStack Query 의 쿼리 상태(v5 `QueryStatus`) — 폴링 판정에 필요한 값만 좁혀 쓴다. */
export type ProgressQueryStatus = 'pending' | 'error' | 'success';

/**
 * 쿼리 상태까지 반영한 폴링 주기 — **에러 상태에서는 폴링하지 않는다** (Critical).
 *
 * `refetchInterval` 콜백은 데이터만 보고 재계산하면 실패를 인지하지 못한다. 최초 조회가 4xx/5xx 로
 * 실패하면 `state.data` 가 `undefined` 로 계속 남아 기본 주기(5s)로 **탭이 열려 있는 동안 영구
 * 재요청**하게 된다(`retry: false` 는 한 fetch 내부 재시도만 끈다). 서버에 속도 제한이 없으므로
 * 이 증폭을 막는 방어는 화면에만 있다.
 *
 * 대신 **정지가 영구가 되지 않도록** 화면(`AugmentProgressPanel`)이 실패를 알리고 수동 재시도
 * 버튼을 노출한다 — 재조회가 성공하면 서버 힌트로 폴링이 다시 살아난다.
 */
export function progressPollInterval(
  status: ProgressQueryStatus,
  data: AugmentProgress | undefined,
): number | false {
  if (status === 'error') return false;
  return progressRefetchInterval(data);
}
