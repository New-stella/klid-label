import { useEffect, useRef } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';

import { Spinner } from '@/components/common/Spinner';
import { useAuthStore } from '@/stores/useAuthStore';

import { detectChannel, redirectToUpstream } from './redirectToUpstream';
import { resolveToken } from './tokenIngress';

const COOKIE_NAME = 'klid_jwt';

/**
 * `/ingress` 진입 페이지.
 * 흐름:
 *   1) URL `?token=` 또는 cookie에서 토큰 수령 (env 전략)
 *   2) 토큰 없으면 detectChannel 기준으로 상위 시스템 redirect
 *   3) JWT decode → 만료 검증 → useAuthStore.setToken
 *   4) 채널별 메인 진입점 navigate (INTERNAL → /video/completed, PORTAL → /portal)
 */
export function SessionIngressPage() {
  const [params] = useSearchParams();
  const navigate = useNavigate();
  // StrictMode 더블 마운트 보호
  const ranRef = useRef(false);

  useEffect(() => {
    if (ranRef.current) return;
    ranRef.current = true;

    const token = resolveToken({
      urlToken: params.get('token'),
      cookieName: COOKIE_NAME,
    });

    if (!token) {
      redirectToUpstream(detectChannel());
      return;
    }

    // 토큰을 store에 적재 (decode + 타입 가드는 store 내부에서 수행)
    useAuthStore.getState().setToken(token);
    const claims = useAuthStore.getState().claims;

    if (!claims) {
      redirectToUpstream(detectChannel());
      return;
    }

    // 만료 검증
    const nowSec = Math.floor(Date.now() / 1000);
    if (claims.exp <= nowSec) {
      useAuthStore.getState().clear();
      redirectToUpstream(claims.channel);
      return;
    }

    // mock 정합 — INTERNAL 채널은 /dashboard 진입
    const target = claims.channel === 'PORTAL' ? '/portal' : '/dashboard';
    navigate(target, { replace: true });
  }, [params, navigate]);

  return (
    <div className="flex min-h-screen items-center justify-center" role="status" aria-live="polite">
      <Spinner size="lg" label="세션을 확인하는 중" />
    </div>
  );
}
