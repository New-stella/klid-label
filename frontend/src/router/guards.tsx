import { ReactNode, useEffect } from 'react';
import { Navigate } from 'react-router-dom';

import { redirectToUpstream } from '@/features/auth/redirectToUpstream';
import type { Channel, Role } from '@/lib/api/types';
import { useAuthStore } from '@/stores/useAuthStore';
import { Spinner } from '@/components/common/Spinner';

interface RoleGuardProps {
  allow: Role[];
  children: ReactNode;
}

/**
 * 역할 기반 접근 제어.
 * - isHydrated false → 토큰 복원 대기 (스피너)
 * - claims 없음 → /ingress (재인계 시도)
 * - exp 만료 → 상위 시스템 redirect
 * - role 미부여(null) → /role-claim (Phase 2 — 권한 자가 부여 화면)
 * - 역할 불일치 → /forbidden
 */
export function RoleGuard({ allow, children }: RoleGuardProps) {
  const claims = useAuthStore((s) => s.claims);
  const isHydrated = useAuthStore((s) => s.isHydrated);
  const expired = isExpired(claims?.exp);

  useEffect(() => {
    if (claims && expired) {
      useAuthStore.getState().clear();
      redirectToUpstream(claims.channel);
    }
  }, [claims, expired]);

  if (!isHydrated) {
    return (
      <div className="flex h-full items-center justify-center py-10">
        <Spinner label="인증 확인 중" />
      </div>
    );
  }
  if (!claims) {
    return <Navigate to="/ingress" replace />;
  }
  if (expired) {
    // redirect는 useEffect에서 처리. 빈 화면 노출 방지를 위해 스피너 표시.
    return (
      <div className="flex h-full items-center justify-center py-10">
        <Spinner label="인증 확인 중" />
      </div>
    );
  }
  // Phase 2 — 인증은 되었으나 role 이 부여되지 않은 사용자는 /role-claim 으로 안내.
  // INTERNAL 채널에만 적용 (PORTAL_USER 은 토큰 발급 시점에 항상 role 이 부여됨).
  if (!claims.role && claims.channel === 'INTERNAL') {
    return <Navigate to="/role-claim" replace />;
  }
  if (!claims.role || !allow.includes(claims.role)) {
    return <Navigate to="/forbidden" replace />;
  }
  return <>{children}</>;
}

interface ChannelGuardProps {
  channel: Channel;
  children: ReactNode;
}

/**
 * 채널 기반 접근 제어.
 * - isHydrated false → 토큰 복원 대기 (스피너)
 * - claims 없음 → /ingress
 * - 채널 불일치 → /forbidden
 */
export function ChannelGuard({ channel, children }: ChannelGuardProps) {
  const claims = useAuthStore((s) => s.claims);
  const isHydrated = useAuthStore((s) => s.isHydrated);

  if (!isHydrated) {
    return (
      <div className="flex h-full items-center justify-center py-10">
        <Spinner label="인증 확인 중" />
      </div>
    );
  }
  if (!claims) {
    return <Navigate to="/ingress" replace />;
  }
  if (claims.channel !== channel) {
    return <Navigate to="/forbidden" replace />;
  }
  return <>{children}</>;
}

function isExpired(exp: number | undefined): boolean {
  if (typeof exp !== 'number' || exp <= 0) return false;
  return exp <= Math.floor(Date.now() / 1000);
}

interface AuthenticatedGuardProps {
  children: ReactNode;
}

/**
 * 인증만 요구하는 경량 가드 (Phase 2 — `/role-claim` 진입용).
 *
 * - isHydrated false → 스피너
 * - claims 없음 → /ingress (재인계 시도)
 * - exp 만료 → 상위 시스템 redirect
 * - role / channel 검증 없음 (role 미부여 사용자도 통과)
 *
 * RoleGuard 와 달리 role=null 인 사용자를 통과시켜야 `/role-claim` 에서 권한을 부여받을 수 있다.
 * 만약 RoleGuard 를 사용하면 무한 redirect 가 발생한다.
 */
export function AuthenticatedGuard({ children }: AuthenticatedGuardProps) {
  const claims = useAuthStore((s) => s.claims);
  const isHydrated = useAuthStore((s) => s.isHydrated);
  const expired = isExpired(claims?.exp);

  useEffect(() => {
    if (claims && expired) {
      useAuthStore.getState().clear();
      redirectToUpstream(claims.channel);
    }
  }, [claims, expired]);

  if (!isHydrated) {
    return (
      <div className="flex h-full items-center justify-center py-10">
        <Spinner label="인증 확인 중" />
      </div>
    );
  }
  if (!claims) {
    return <Navigate to="/ingress" replace />;
  }
  if (expired) {
    return (
      <div className="flex h-full items-center justify-center py-10">
        <Spinner label="인증 확인 중" />
      </div>
    );
  }
  return <>{children}</>;
}
