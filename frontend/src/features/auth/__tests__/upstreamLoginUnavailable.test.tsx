// 로그인 주소 미설정 시 <막다른 화면>이 되지 않는지 고정한다.
//
// 배경: 주소가 없으면 `redirectToUpstream` 은 fail-closed 로 아무 데도 보내지 않는다(그건 옳다).
// 문제는 그 다음이었다 — 가드는 "인증 확인 중" 스피너를 <영원히> 돌렸고, 진입 화면은 원인이
// 설정 누락인지 상위 서버 장애인지 구분되지 않는 문구를 냈다. 운영자가 볼 수 있는 단서가 없어
// 원인 파악이 불가능했다(빌드에 예시 주소가 구워진 산출물의 증상도 같은 종류다).

import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { RoleGuard } from '@/router/guards';
import { useAuthStore } from '@/stores/useAuthStore';

import { SessionIngressPage } from '../SessionIngressPage';

function b64url(obj: Record<string, unknown>): string {
  const b64 = btoa(unescape(encodeURIComponent(JSON.stringify(obj))));
  return b64.replace(/=+$/, '').replace(/\+/g, '-').replace(/\//g, '_');
}
const EXPIRED_TOKEN = `${b64url({ alg: 'HS256', typ: 'JWT' })}.${b64url({
  sub: 'u1',
  role: 'REVIEWER',
  channel: 'INTERNAL',
  exp: 1,
})}.sig`;

/** 만료 클레임을 적재한 상태 — 기존 가드 테스트(RoleGuard.test.tsx)와 같은 방식. */
function seedExpiredSession(): void {
  useAuthStore.setState({
    token: EXPIRED_TOKEN,
    claims: { sub: 'u1', role: 'REVIEWER', channel: 'INTERNAL', exp: 1 },
    isHydrated: true,
  });
}

describe('상위 로그인 주소 미설정 — 원인을 알 수 있는 화면', () => {
  let assignSpy: ReturnType<typeof vi.fn>;
  let originalLocation: Location;

  beforeEach(() => {
    useAuthStore.getState().clear();
    assignSpy = vi.fn();
    originalLocation = window.location;
    Object.defineProperty(window, 'location', {
      writable: true,
      value: { ...originalLocation, href: 'http://app.local/ingress', assign: assignSpy },
    });
    // 운영 산출물 재현 — dev 로그인 우회가 없고, 로그인 주소도 비어 있다.
    vi.stubEnv('DEV', false);
    vi.stubEnv('VITE_DEV_LOGIN_ENABLED', '');
    vi.stubEnv('VITE_CONTROL_LOGIN_URL', '');
    vi.stubEnv('VITE_PORTAL_LOGIN_URL', '');
  });

  afterEach(() => {
    Object.defineProperty(window, 'location', { writable: true, value: originalLocation });
    vi.unstubAllEnvs();
  });

  it('진입_화면은_설정_누락을_이름으로_알린다', async () => {
    render(
      <MemoryRouter initialEntries={['/ingress']}>
        <Routes>
          <Route path="/ingress" element={<SessionIngressPage />} />
          <Route path="/dev/login" element={<div>DEV_LOGIN</div>} />
        </Routes>
      </MemoryRouter>,
    );

    // then: "다시 접근해주세요" 같은 일반 안내가 아니라 설정 항목을 지목해야 한다
    await waitFor(() => {
      expect(screen.getByText(/설정되지 않았습니다/)).toBeInTheDocument();
    });
    expect(screen.getByText(/VITE_CONTROL_LOGIN_URL/)).toBeInTheDocument();
    expect(assignSpy).not.toHaveBeenCalled();
  });

  it('만료된_세션이_막다른_화면으로_끝나지_않는다', () => {
    // 가드는 만료를 보면 claims 를 지우고 진입 화면(`/ingress`)으로 넘긴다. 그 화면이
    // 원인을 알려야 사슬 전체가 막다른 길이 되지 않는다 — 그래서 한 경로로 이어서 본다.
    seedExpiredSession();

    render(
      <MemoryRouter initialEntries={['/manage']}>
        <Routes>
          <Route
            path="/manage"
            element={
              <RoleGuard allow={['REVIEWER']}>
                <div>PROTECTED</div>
              </RoleGuard>
            }
          />
          <Route path="/ingress" element={<SessionIngressPage />} />
        </Routes>
      </MemoryRouter>,
    );

    return waitFor(() => {
      expect(screen.getByText(/설정되지 않았습니다/)).toBeInTheDocument();
      expect(screen.queryByText('PROTECTED')).not.toBeInTheDocument();
    });
  });

  it('주소가_설정돼_있으면_종전대로_이동한다', async () => {
    // 회귀 가드 — 안내 화면이 정상 경로를 가로채면 안 된다.
    vi.stubEnv('VITE_CONTROL_LOGIN_URL', 'http://control.local/login');
    seedExpiredSession();

    render(
      <MemoryRouter>
        <RoleGuard allow={['REVIEWER']}>
          <div>PROTECTED</div>
        </RoleGuard>
      </MemoryRouter>,
    );

    await waitFor(() => {
      expect(assignSpy).toHaveBeenCalled();
    });
    expect(screen.queryByText(/설정되지 않았습니다/)).not.toBeInTheDocument();
  });
});
