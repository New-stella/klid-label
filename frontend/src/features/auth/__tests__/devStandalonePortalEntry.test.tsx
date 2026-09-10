import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import MockAdapter from 'axios-mock-adapter';

import { apiClient } from '@/lib/api/client';
import { useAuthStore } from '@/stores/useAuthStore';

import { SessionIngressPage } from '../SessionIngressPage';
import { clearDevHostToken, installDevHostTokenHandoff, seedDevHostToken } from '../devHostStub';
import { clearHostTokenHandoff } from '../tokenHandoff';
import { LOCAL_STORAGE_TOKEN_KEY } from '../tokenIngress';

// [@design INT-013] [@design SCREEN-004]
/**
 * 포털 채널을 **Host 없이 단독으로** 띄웠을 때의 진입 흐름 회귀 가드.
 *
 * 이 파일이 지키는 것은 「대역이 등록됐다」가 아니라 **「등록된 대역이 실제로 화면과 요청까지
 * 이어진다」**이다. 앞의 것만 보면 창구는 멀쩡한데 진입 페이지가 여전히 저장소를 뒤져
 * 개발용 로그인으로 되튕기는 상태를 통과시킨다(실제로 그 형상이 결함이었다).
 */

function b64url(obj: Record<string, unknown>): string {
  const json = JSON.stringify(obj);
  const utf8 = unescape(encodeURIComponent(json));
  return btoa(utf8).replace(/=+$/, '').replace(/\+/g, '-').replace(/\//g, '_');
}

function buildJwt(payload: Record<string, unknown>): string {
  return `${b64url({ alg: 'HS256', typ: 'JWT' })}.${b64url(payload)}.signature`;
}

const PORTAL_JWT = buildJwt({
  sub: '3001',
  role: 'PORTAL_USER',
  channel: 'PORTAL',
  exp: 9999999999,
  name: '홍길동',
});

const INTERNAL_JWT = buildJwt({
  sub: '1001',
  role: 'REVIEWER',
  channel: 'INTERNAL',
  exp: 9999999999,
  name: '김검수',
});

function renderIngress() {
  return render(
    <MemoryRouter initialEntries={['/ingress']}>
      <Routes>
        <Route path="/ingress" element={<SessionIngressPage />} />
        <Route path="/portal" element={<div>PORTAL_HOME</div>} />
        <Route path="/dashboard" element={<div>DASHBOARD_HOME</div>} />
        <Route path="/dev/login" element={<div>DEV_LOGIN</div>} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('포털 채널 단독 구동 — 진입 흐름', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    useAuthStore.getState().clear();
    localStorage.clear();
    sessionStorage.clear();
    clearHostTokenHandoff();
    clearDevHostToken();
    mock = new MockAdapter(apiClient);
    vi.spyOn(console, 'info').mockImplementation(() => {});
  });

  afterEach(() => {
    mock.restore();
    clearDevHostToken();
    clearHostTokenHandoff();
    vi.unstubAllEnvs();
    vi.restoreAllMocks();
    localStorage.clear();
    sessionStorage.clear();
  });

  describe('포털 채널', () => {
    beforeEach(() => {
      vi.stubEnv('VITE_BUILD_CHANNEL', 'portal');
    });

    it('★대역이_들고_있는_토큰으로_포털_홈까지_들어간다', async () => {
      installDevHostTokenHandoff();
      seedDevHostToken(PORTAL_JWT);
      // [@design ADR-012] 포털 향은 토큰의 역할을 인가 축에 쓰지 않으므로 진입 화면이 도착지를
      // 정하기 전에 `GET /v1/me` 로 역할을 확보한다. 그 대역이 없으면 이 시험은 <대역이 준 토큰이
      // 화면까지 이어지는가>가 아니라 <역할 확인 실패 안내>를 잡게 된다.
      mock.onGet('/me').reply(200, {
        success: true,
        data: { sub: '3001', role: 'PORTAL_USER', channel: 'PORTAL', name: '홍길동' },
        message: null,
        errorCode: null,
      });

      renderIngress();

      await waitFor(() => {
        expect(screen.getByText('PORTAL_HOME')).toBeInTheDocument();
      });
      expect(useAuthStore.getState().claims?.channel).toBe('PORTAL');
    });

    /**
     * ★★ 이 채널의 조달처는 **인계 창구**다. 저장소를 읽으면 ①아무것도 없거나 ②앞 채널이
     *    남긴 죽은 토큰을 줍는다 — 뒤쪽이 더 나쁘다(다른 사람의 세션으로 들어간다).
     */
    it('★저장소에_남은_앞_채널_토큰을_줍지_않는다', async () => {
      localStorage.setItem(LOCAL_STORAGE_TOKEN_KEY, INTERNAL_JWT);
      vi.stubEnv('VITE_TOKEN_INGRESS', 'localStorage');

      renderIngress();

      // 창구가 비어 있으므로 인증 실패로 떨어져 개발용 로그인으로 간다.
      await waitFor(() => {
        expect(screen.getByText('DEV_LOGIN')).toBeInTheDocument();
      });
      expect(screen.queryByText('DASHBOARD_HOME')).not.toBeInTheDocument();
      expect(useAuthStore.getState().claims).toBeNull();
    });

    /**
     * ⚠ 단언 대상이 `Authorization` 에서 **포털 전용 헤더**로 바뀌었다(2026-09-05).
     *   백엔드가 `x-access-token` 을 inbound 로 수용하게 되면서 포털 채널이 그 계약으로
     *   전환됐다(`INT-013` · `features/auth/tokenHandoff.buildAuthHeader`). 대역은 창구를
     *   제공할 뿐 헤더 축을 소유하지 않으므로, 여기서 지키는 것은 「대역이 준 토큰이 요청에
     *   실려 401 이 아니다」 그대로다.
     */
    it('★요청에_전용_인증_헤더가_실린다_401이_아니다', async () => {
      installDevHostTokenHandoff();
      seedDevHostToken(PORTAL_JWT);

      const seen: { auth?: unknown; xAccessToken?: unknown } = {};
      mock.onGet('/portal/videos').reply((config) => {
        const h = (config.headers ?? {}) as Record<string, unknown>;
        seen.auth = h['Authorization'];
        seen.xAccessToken = h['x-access-token'];
        return [200, { success: true, data: [], message: null, errorCode: null }];
      });

      await apiClient.get('/portal/videos');

      expect(seen.xAccessToken).toBe(PORTAL_JWT);
      // Bearer 스킴 미사용 — 전용 헤더 단독에 접두가 붙으면 그 문자열 전체가 토큰이 되어
      // 서명 파싱에서 거부된다(401). `Authorization` 과 함께 올 때의 값 충돌 거부는 별개 경로다.
      expect(seen.auth).toBeUndefined();
    });

    it('대역이_없으면_요청에_헤더가_붙지_않는다_스토어로_폴백하지_않는다', async () => {
      // 본체의 fail-closed 성질은 그대로다 — 대역은 창구를 <제공>할 뿐 폴백을 열지 않는다.
      useAuthStore.getState().setTokenAndClaims(PORTAL_JWT);

      const seen: { auth?: unknown; xAccessToken?: unknown } = {};
      mock.onGet('/portal/videos').reply((config) => {
        const h = (config.headers ?? {}) as Record<string, unknown>;
        seen.auth = h['Authorization'];
        seen.xAccessToken = h['x-access-token'];
        return [200, { success: true, data: [], message: null, errorCode: null }];
      });

      await apiClient.get('/portal/videos');

      expect(seen.auth).toBeUndefined();
      expect(seen.xAccessToken).toBeUndefined();
    });
  });

  describe('관제 채널 — 진입 경로가 한 글자도 바뀌지 않는다', () => {
    beforeEach(() => {
      vi.stubEnv('VITE_BUILD_CHANNEL', 'control');
      vi.stubEnv('VITE_TOKEN_INGRESS', 'localStorage');
    });

    it('★같은_출처_저장소에서_인계받아_대시보드로_들어간다', async () => {
      localStorage.setItem(LOCAL_STORAGE_TOKEN_KEY, INTERNAL_JWT);

      renderIngress();

      await waitFor(() => {
        expect(screen.getByText('DASHBOARD_HOME')).toBeInTheDocument();
      });
      expect(useAuthStore.getState().claims?.role).toBe('REVIEWER');
    });

    it('★대역이_어쩌다_등록돼_있어도_관제_채널의_조달처는_저장소다', () => {
      // 대역은 포털 채널에서만 켜지므로 여기서는 등록 자체가 거부된다.
      expect(installDevHostTokenHandoff()).toBe(false);
      expect(seedDevHostToken(PORTAL_JWT)).toBe(false);
    });
  });
});
