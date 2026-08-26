// 포털 채널(Module Federation Remote 임베드) 빌드에서는 인증 가드가 `/forbidden`·`/ingress`·
// `/role-claim` 으로 이동하지 않고 그 자리에 안내를 그려야 한다 — 그 경로들은 포털 Host 의
// 라우트 테이블에 없어(147개 전수 확인) 이동하면 Host 문서 전체가 404 로 떨어진다.
//
// 판정 방법: 가드가 실제로 <Navigate> 를 렌더했는지는 "그 경로가 매치됐는가"로 간접 확인한다.
// 아래 각 테스트의 라우트 테이블에는 `/forbidden`·`/ingress`·`/role-claim` 을 등록하지 않고,
// 대신 catch-all(`*`)에 CATCHALL_PAGE 를 심어 둔다. 만약 가드가 여전히 <Navigate> 를 쓰면
// react-router 는 미매치 경로를 catch-all 로 떨어뜨려 CATCHALL_PAGE 가 뜬다(회귀 감지).
// 이번 변경이 맞다면 CATCHALL_PAGE 는 절대 뜨지 않고, 그 자리에 안내 텍스트만 보여야 한다.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';

import { AuthenticatedGuard, ChannelGuard, RoleGuard } from '@/router/guards';
import { useAuthStore } from '@/stores/useAuthStore';
import { Role } from '@/lib/api/types';

function renderGuardedRoute(initialPath: string, guardedElement: React.ReactNode) {
  return render(
    <MemoryRouter initialEntries={[initialPath]}>
      <Routes>
        <Route path="/target" element={guardedElement} />
        {/* /forbidden·/ingress·/role-claim 은 의도적으로 등록하지 않는다 — 포털 Host 에는
            그 경로가 없다는 사실을 그대로 재현한다. */}
        <Route path="*" element={<div>CATCHALL_PAGE</div>} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('포털 채널 빌드 — 가드는 이동 대신 그 자리에 안내를 그린다', () => {
  beforeEach(() => {
    useAuthStore.getState().clear();
  });

  afterEach(() => {
    useAuthStore.getState().clear();
    vi.unstubAllEnvs();
  });

  describe('RoleGuard', () => {
    it('claims_없음_포털채널_이동대신_인증필요_안내', () => {
      vi.stubEnv('VITE_BUILD_CHANNEL', 'portal');
      renderGuardedRoute(
        '/target',
        <RoleGuard allow={[Role.PORTAL_USER]}>
          <div>TARGET_PAGE</div>
        </RoleGuard>,
      );

      expect(screen.getByTestId('portal-embed-guard-notice')).toBeInTheDocument();
      expect(screen.getByText('인증이 필요합니다')).toBeInTheDocument();
      expect(screen.queryByText('CATCHALL_PAGE')).toBeNull();
      expect(screen.queryByText('TARGET_PAGE')).toBeNull();
    });

    it('role_미부여_INTERNAL채널_포털빌드_이동대신_역할필요_안내', () => {
      vi.stubEnv('VITE_BUILD_CHANNEL', 'portal');
      useAuthStore.setState({
        token: 'tok',
        claims: { sub: 'u', role: null, channel: 'INTERNAL', exp: 9999999999 },
      });

      renderGuardedRoute(
        '/target',
        <RoleGuard allow={[Role.REVIEWER]}>
          <div>TARGET_PAGE</div>
        </RoleGuard>,
      );

      expect(screen.getByTestId('portal-embed-guard-notice')).toBeInTheDocument();
      expect(screen.getByText('역할이 아직 부여되지 않았습니다')).toBeInTheDocument();
      expect(screen.queryByText('CATCHALL_PAGE')).toBeNull();
    });

    it('역할_불일치_포털채널_이동대신_권한없음_안내', () => {
      vi.stubEnv('VITE_BUILD_CHANNEL', 'portal');
      useAuthStore.setState({
        token: 'tok',
        claims: { sub: 'u', role: 'WORKER', channel: 'INTERNAL', exp: 9999999999 },
      });

      renderGuardedRoute(
        '/target',
        <RoleGuard allow={[Role.REVIEWER]}>
          <div>TARGET_PAGE</div>
        </RoleGuard>,
      );

      expect(screen.getByTestId('portal-embed-guard-notice')).toBeInTheDocument();
      expect(screen.getByText('접근 권한이 없습니다')).toBeInTheDocument();
      expect(screen.queryByText('CATCHALL_PAGE')).toBeNull();
    });

    it('기본채널_internal_에서는_지금처럼_이동한다_회귀없음', () => {
      vi.stubEnv('VITE_BUILD_CHANNEL', 'internal');
      renderGuardedRoute(
        '/target',
        <RoleGuard allow={[Role.PORTAL_USER]}>
          <div>TARGET_PAGE</div>
        </RoleGuard>,
      );

      // 내부 채널은 여전히 /ingress 로 이동 → 그 경로가 등록돼 있지 않으므로 catch-all 로 떨어진다.
      expect(screen.getByText('CATCHALL_PAGE')).toBeInTheDocument();
      expect(screen.queryByTestId('portal-embed-guard-notice')).toBeNull();
    });
  });

  describe('ChannelGuard', () => {
    it('claims_없음_포털채널_이동대신_인증필요_안내', () => {
      vi.stubEnv('VITE_BUILD_CHANNEL', 'portal');
      renderGuardedRoute(
        '/target',
        <ChannelGuard channel="PORTAL">
          <div>TARGET_PAGE</div>
        </ChannelGuard>,
      );

      expect(screen.getByTestId('portal-embed-guard-notice')).toBeInTheDocument();
      expect(screen.getByText('인증이 필요합니다')).toBeInTheDocument();
      expect(screen.queryByText('CATCHALL_PAGE')).toBeNull();
    });

    it('채널_불일치_포털채널_이동대신_권한없음_안내', () => {
      vi.stubEnv('VITE_BUILD_CHANNEL', 'portal');
      useAuthStore.setState({
        token: 'tok',
        claims: { sub: 'u', role: 'WORKER', channel: 'INTERNAL', exp: 9999999999 },
      });

      renderGuardedRoute(
        '/target',
        <ChannelGuard channel="PORTAL">
          <div>TARGET_PAGE</div>
        </ChannelGuard>,
      );

      expect(screen.getByTestId('portal-embed-guard-notice')).toBeInTheDocument();
      expect(screen.getByText('접근 권한이 없습니다')).toBeInTheDocument();
      expect(screen.queryByText('CATCHALL_PAGE')).toBeNull();
    });

    it('기본채널_internal_채널불일치_지금처럼_forbidden으로_이동_회귀없음', () => {
      vi.stubEnv('VITE_BUILD_CHANNEL', 'internal');
      useAuthStore.setState({
        token: 'tok',
        claims: { sub: 'u', role: 'WORKER', channel: 'INTERNAL', exp: 9999999999 },
      });

      renderGuardedRoute(
        '/target',
        <ChannelGuard channel="PORTAL">
          <div>TARGET_PAGE</div>
        </ChannelGuard>,
      );

      expect(screen.getByText('CATCHALL_PAGE')).toBeInTheDocument();
      expect(screen.queryByTestId('portal-embed-guard-notice')).toBeNull();
    });
  });

  describe('AuthenticatedGuard', () => {
    it('claims_없음_포털채널_이동대신_인증필요_안내', () => {
      vi.stubEnv('VITE_BUILD_CHANNEL', 'portal');
      renderGuardedRoute(
        '/target',
        <AuthenticatedGuard>
          <div>TARGET_PAGE</div>
        </AuthenticatedGuard>,
      );

      expect(screen.getByTestId('portal-embed-guard-notice')).toBeInTheDocument();
      expect(screen.getByText('인증이 필요합니다')).toBeInTheDocument();
      expect(screen.queryByText('CATCHALL_PAGE')).toBeNull();
    });

    it('기본채널_internal_claims_없음_지금처럼_ingress로_이동_회귀없음', () => {
      vi.stubEnv('VITE_BUILD_CHANNEL', 'internal');
      renderGuardedRoute(
        '/target',
        <AuthenticatedGuard>
          <div>TARGET_PAGE</div>
        </AuthenticatedGuard>,
      );

      expect(screen.getByText('CATCHALL_PAGE')).toBeInTheDocument();
      expect(screen.queryByTestId('portal-embed-guard-notice')).toBeNull();
    });
  });

  describe('토큰 만료 — 상위 시스템 이동은 이 안내의 대상이 아니다', () => {
    let assignSpy: ReturnType<typeof vi.fn>;
    let originalLocation: Location;

    beforeEach(() => {
      assignSpy = vi.fn();
      originalLocation = window.location;
      Object.defineProperty(window, 'location', {
        writable: true,
        value: { ...originalLocation, href: 'http://app.local/target', assign: assignSpy },
      });
    });

    afterEach(() => {
      Object.defineProperty(window, 'location', { writable: true, value: originalLocation });
    });

    it('포털채널_만료시_이_안내를_거치지_않고_그대로_상위_포털_로그인으로_redirect', () => {
      // `RoleGuard`/`AuthenticatedGuard` 의 만료 처리(useEffect → clear() + redirectToUpstream())는
      // 이번 변경의 대상이 아니다 — PortalEmbedNotice 는 "claims 없음"·"role 없음"·"role 불일치"
      // 세 분기에만 개입하고, 만료 분기(`redirectToUpstream` 호출)는 건드리지 않았다.
      // RTL 의 render() 는 effect 를 동기적으로 flush 하므로, 이 시점엔 이미 clear() 가 호출돼
      // claims 가 null 이 된 뒤의 재렌더 결과(= claims 없음 분기의 안내)가 보인다. 이 테스트가
      // 확인하는 것은 그 화면 표시가 아니라 "상위 시스템으로의 실제 redirect(window.location.assign)
      // 가 여전히 정확한 대상(포털 로그인 URL)으로 발생했는가" — 그것이 손대지 않았다는 증거다.
      vi.stubEnv('VITE_BUILD_CHANNEL', 'portal');
      vi.stubEnv('VITE_PORTAL_LOGIN_URL', 'http://portal.local/login');
      useAuthStore.setState({
        token: 'tok',
        claims: { sub: 'u', role: 'PORTAL_USER', channel: 'PORTAL', exp: 1 }, // 만료됨
      });

      renderGuardedRoute(
        '/target',
        <RoleGuard allow={[Role.PORTAL_USER]}>
          <div>TARGET_PAGE</div>
        </RoleGuard>,
      );

      expect(assignSpy).toHaveBeenCalledTimes(1);
      const target = assignSpy.mock.calls[0][0] as string;
      expect(target.startsWith('http://portal.local/login')).toBe(true);
      // 우리 라우트(catch-all)로는 이동하지 않는다 — 이동은 오직 상위 시스템으로만 향한다.
      expect(screen.queryByText('CATCHALL_PAGE')).toBeNull();
    });
  });
});
