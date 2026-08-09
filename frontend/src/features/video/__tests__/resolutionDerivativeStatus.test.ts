// B8#46 — 해상도 파생영상 확정 상태 조회 배선 회귀 가드.
//
// 검증 축 2개:
//  ① 조회 함수가 BE 계약(GET /videos/{rawSn}/resolution)을 그대로 친다.
//  ② 폴링이 **종료 상태에서 반드시 멈춘다** — 안 멈추면 화면을 켜 둔 사용자마다 무한 요청이
//     되어 self-DoS 다(CWE-770). 실제 타이머는 TanStack Query 소관이라 간격 판정만 검증한다.

import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';

import { apiClient } from '@/lib/api/client';

import { listResolutionDerivatives } from '../api';
import {
  DERIVATIVE_POLL_INTERVAL_MS,
  derivativePollInterval,
  resolutionDerivativeKey,
} from '../hooks/useResolutionDerivativeStatus';
import { isDerivativeSettled } from '../types';
import type {
  DerivativeStatus,
  ResolutionChangeResult,
  ResolutionDerivativeResult,
} from '../types';

function derivative(
  status: DerivativeStatus | string,
  goalResCd = 'RESL_720P',
): ResolutionDerivativeResult {
  return {
    rawSn: 11,
    goalResCd,
    targetW: 1280,
    targetH: 720,
    status: status as DerivativeStatus,
  };
}

function result(
  ...derivatives: ResolutionDerivativeResult[]
): ResolutionChangeResult {
  return { derivatives };
}

describe('isDerivativeSettled — 확정 종료 판정(단일 원천)', () => {
  it('CREATED 는 예약만 된 상태라 종료가 아니다', () => {
    expect(isDerivativeSettled('CREATED')).toBe(false);
  });

  it('IN_PROGRESS 는 종료가 아니다', () => {
    expect(isDerivativeSettled('IN_PROGRESS')).toBe(false);
  });

  it('COMPLETED · FAILED 는 종료다', () => {
    expect(isDerivativeSettled('COMPLETED')).toBe(true);
    expect(isDerivativeSettled('FAILED')).toBe(true);
  });

  it('미지의 상태값은 종료로 본다(fail-closed — 모르는 값에서 영원히 폴링하지 않는다)', () => {
    expect(isDerivativeSettled('SOMETHING_NEW')).toBe(true);
  });
});

describe('derivativePollInterval — 폴링 종료 계약', () => {
  it('데이터가 없으면(첫 조회 전) 폴링하지 않는다', () => {
    expect(derivativePollInterval(undefined)).toBe(false);
  });

  it('파생이 0건이면 기다릴 대상이 없어 폴링하지 않는다', () => {
    expect(derivativePollInterval(result())).toBe(false);
  });

  it('확정 진행 중(IN_PROGRESS)이면 폴링 간격을 반환한다', () => {
    expect(derivativePollInterval(result(derivative('IN_PROGRESS')))).toBe(
      DERIVATIVE_POLL_INTERVAL_MS,
    );
  });

  it('예약 직후(CREATED)도 아직 확정 전이라 폴링한다', () => {
    expect(derivativePollInterval(result(derivative('CREATED')))).toBe(
      DERIVATIVE_POLL_INTERVAL_MS,
    );
  });

  it('★전부 종료(COMPLETED/FAILED)면 폴링을 멈춘다 — 무한 폴링 차단', () => {
    expect(
      derivativePollInterval(
        result(
          derivative('COMPLETED', 'RESL_1080P'),
          derivative('FAILED', 'RESL_720P'),
        ),
      ),
    ).toBe(false);
  });

  it('일부만 종료면 남은 건을 위해 계속 폴링한다', () => {
    expect(
      derivativePollInterval(
        result(
          derivative('COMPLETED', 'RESL_1080P'),
          derivative('IN_PROGRESS', 'RESL_480P'),
        ),
      ),
    ).toBe(DERIVATIVE_POLL_INTERVAL_MS);
  });
});

describe('resolutionDerivativeKey', () => {
  it('VIDEO_KEYS.all 하위에 둬서 영상 캐시 무효화에 함께 딸려 간다', () => {
    // 해상도 변경 mutation 이 VIDEO_KEYS.all 을 invalidate 하므로 prefix 가 어긋나면
    // 새 파생을 요청해도 이 조회가 갱신되지 않는다.
    expect(resolutionDerivativeKey(7)[0]).toBe('videos');
    expect(resolutionDerivativeKey(7)).not.toEqual(resolutionDerivativeKey(8));
  });
});

describe('listResolutionDerivatives — BE 계약', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
  });

  afterEach(() => {
    mock.restore();
  });

  it('GET /videos/{rawSn}/resolution 을 호출하고 확정 상태를 그대로 돌려준다', async () => {
    mock.onGet('/videos/42/resolution').reply(200, {
      success: true,
      data: {
        derivatives: [
          {
            rawSn: 101,
            goalResCd: 'RESL_720P',
            targetW: 1280,
            targetH: 720,
            status: 'COMPLETED',
          },
          {
            rawSn: 102,
            goalResCd: 'RESL_480P',
            targetW: 854,
            targetH: 480,
            status: 'IN_PROGRESS',
          },
        ],
      },
      message: null,
      errorCode: null,
    });

    const res = await listResolutionDerivatives(42);

    expect(res.derivatives).toHaveLength(2);
    // 구 FE 타입은 CREATED/FAILED 2값뿐이라 이 두 값을 표현하지 못했다.
    expect(res.derivatives[0].status).toBe('COMPLETED');
    expect(res.derivatives[1].status).toBe('IN_PROGRESS');
  });
});
