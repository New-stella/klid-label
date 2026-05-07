import { ReactNode, useEffect } from 'react';
import { Navigate } from 'react-router-dom';

import { redirectToUpstream } from '@/features/auth/redirectToUpstream';
import type { Channel, Role } from '@/lib/api/types';
import { useAuthStore } from '@/stores/useAuthStore';

interface RoleGuardProps {
  allow: Role[];
  children: ReactNode;
}

/**
 * 역할 기반 접근 제어.
 * - claims 없음 → /ingress (재인계 시도)
 * - exp 만료 → 상위 시스템 redirect
 * - 역할 불일치 → /forbidden
 */
export function RoleGuard({ allow, children }: RoleGuardProps) {
  const claims = useAuthStore((s) => s.claims);
  const expired = isExpired(claims?.exp);

  useEffect(() => {
    if (claims && expired) {
      useAuthStore.getState().clear();
      redirectToUpstream(claims.channel);
    }
  }, [claims, expired]);

  if (!claims) {
    return <Navigate to="/ingress" replace />;
  }
  if (expired) {
    // redirect는 useEffect에서 처리. 동기 렌더 차단을 위해 빈 노드 반환.
    return null;
  }
  if (!allow.includes(claims.role)) {
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
 * - claims 없음 → /ingress
 * - 채널 불일치 → /forbidden
 */
export function ChannelGuard({ channel, children }: ChannelGuardProps) {
  const claims = useAuthStore((s) => s.claims);

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
