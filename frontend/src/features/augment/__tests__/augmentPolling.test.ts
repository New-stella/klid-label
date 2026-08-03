import { describe, expect, it } from 'vitest';

import { progressPollInterval, progressRefetchInterval } from '../augmentPolling';
import type { AugmentProgress } from '../types';

/**
 * 폴링 주기 규칙 — 서버가 주는 `nextPollAfterMs` 를 그대로 따르고, 0(종결)이면 멈춘다.
 *
 * 서버에 속도 제한이 없으므로 폴링 증폭을 막는 수단은 이 힌트뿐이다(BE
 * `AugmentProgressResponse#nextPollAfterMs`). 임의 주기를 쓰면 그 방어가 사라진다.
 */
describe('증강 진행률 폴링 주기', () => {
  const base: AugmentProgress = {
    id: 7,
    augTypeCd: 'WINTER',
    status: 'RUNNING',
    progress: 40,
    unavailableReason: null,
    totalJobCount: 2,
    terminalJobCount: 0,
    cancelable: true,
    nextPollAfterMs: 3000,
  };

  it('nextPollAfterMs_를_폴링_주기로_사용한다', () => {
    // given / when / then — 서버 힌트를 그대로 사용
    expect(progressRefetchInterval(base)).toBe(3000);
    expect(progressRefetchInterval({ ...base, nextPollAfterMs: 30_000 })).toBe(30_000);
    expect(progressRefetchInterval({ ...base, nextPollAfterMs: 15_000 })).toBe(15_000);
  });

  it('종결되면_폴링이_중단된다', () => {
    // given — 종결 상태는 서버가 nextPollAfterMs=0 을 준다
    const terminal: AugmentProgress = {
      ...base,
      status: 'SUCCEEDED',
      progress: 100,
      cancelable: false,
      nextPollAfterMs: 0,
    };

    // when / then — false = 더 이상 폴링하지 않음
    expect(progressRefetchInterval(terminal)).toBe(false);
    expect(
      progressRefetchInterval({ ...terminal, status: 'CANCELED' }),
    ).toBe(false);
    expect(progressRefetchInterval({ ...terminal, status: 'FAILED' })).toBe(false);
  });

  it('첫_응답_전에는_기본_주기를_쓴다', () => {
    // given / when / then — 아직 왕복이 없는 상태(계약 밖 값이 아니다)
    expect(progressRefetchInterval(undefined)).toBe(5000);
  });

  it('계약을_벗어난_값은_전부_중단이다_fail_closed', () => {
    // given / when / then — 음수뿐 아니라 NaN·비수치도 신뢰하지 않는다.
    // 구 구현은 NaN 을 "가장 짧은 기본 주기(5s)" 로 폴백해, 주석이 선언한 fail-closed 와
    // 정반대로 가장 공격적인 폴링을 했다(서버에 속도 제한이 없어 방어가 화면뿐이다).
    expect(progressRefetchInterval({ ...base, nextPollAfterMs: -1 })).toBe(false);
    expect(progressRefetchInterval({ ...base, nextPollAfterMs: Number.NaN })).toBe(
      false,
    );
    expect(
      progressRefetchInterval({
        ...base,
        nextPollAfterMs: Number.POSITIVE_INFINITY,
      }),
    ).toBe(false);
    expect(
      progressRefetchInterval({
        ...base,
        // BE 계약 위반(필드 누락·타입 오염) 시뮬레이션 — 화면이 임의 주기로 대체하지 않는다
        nextPollAfterMs: '3000' as unknown as number,
      }),
    ).toBe(false);
  });

  it('에러_상태에서는_폴링하지_않는다', () => {
    // given — 최초 조회가 4xx/5xx 로 실패하면 data 는 계속 undefined 다.
    // when / then — 데이터만 보면 기본 주기(5s)로 영구 재요청하게 되므로 상태를 함께 본다.
    expect(progressPollInterval('error', undefined)).toBe(false);
    // 값이 남아 있는 백그라운드 실패(갱신 실패)도 마찬가지 — 되살리는 것은 사용자 재시도다.
    expect(progressPollInterval('error', base)).toBe(false);
  });

  it('정상_상태에서는_서버_힌트를_그대로_따른다', () => {
    // given / when / then
    expect(progressPollInterval('success', base)).toBe(3000);
    expect(progressPollInterval('pending', undefined)).toBe(5000);
    expect(
      progressPollInterval('success', { ...base, nextPollAfterMs: 0 }),
    ).toBe(false);
  });
});
