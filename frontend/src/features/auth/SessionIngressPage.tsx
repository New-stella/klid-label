import { useEffect, useRef, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';

import { Spinner } from '@/components/common/Spinner';
import { useAuthStore } from '@/stores/useAuthStore';

import { detectChannel, redirectToUpstream } from './redirectToUpstream';
import { resolveToken } from './tokenIngress';

const COOKIE_NAME = 'klid_jwt';

/**
 * `/ingress` 진입 페이지.
 * 흐름:
 *   1) [개발 전용] VITE_DEV_TOKEN 환경변수로 토큰 자동 주입 (DEV 빌드에서만)
 *   2) URL `?token=` 또는 cookie에서 토큰 수령 (env 전략)
 *   3) 토큰 없으면 detectChannel 기준으로 상위 시스템 redirect
 *      → redirect URL 없으면(환경변수 미설정) 에러 메시지 표시 (스피너 무한 방지)
 *   4) JWT decode → 만료 검증 → useAuthStore.setToken
 *   5) 채널별 메인 진입점 navigate (INTERNAL → /dashboard, PORTAL → /portal)
 */
export function SessionIngressPage() {
  const [params] = useSearchParams();
  const navigate = useNavigate();
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  // StrictMode 더블 마운트 보호
  const ranRef = useRef(false);

  useEffect(() => {
    if (ranRef.current) return;
    ranRef.current = true;

    let token = resolveToken({
      urlToken: params.get('token'),
      cookieName: COOKIE_NAME,
    });

    // [개발 전용] VITE_DEV_TOKEN 환경변수로 upstream 없이 개발 가능하게 지원
    if (!token && import.meta.env.DEV) {
      const devToken = import.meta.env.VITE_DEV_TOKEN as string | undefined;
      if (devToken) {
        token = devToken;
      }
    }

    if (!token) {
      const redirected = redirectToUpstream(detectChannel());
      if (!redirected) {
        setErrorMessage(
          '로그인 서버에 연결할 수 없습니다. 관제서버 또는 포털에서 다시 접근해주세요.',
        );
      }
      return;
    }

    // 토큰을 store에 적재 (decode + 타입 가드는 store 내부에서 수행)
    useAuthStore.getState().setToken(token);
    const claims = useAuthStore.getState().claims;

    if (!claims) {
      const redirected = redirectToUpstream(detectChannel());
      if (!redirected) {
        setErrorMessage(
          '로그인 서버에 연결할 수 없습니다. 관제서버 또는 포털에서 다시 접근해주세요.',
        );
      }
      return;
    }

    // 만료 검증
    const nowSec = Math.floor(Date.now() / 1000);
    if (claims.exp <= nowSec) {
      useAuthStore.getState().clear();
      const redirected = redirectToUpstream(claims.channel);
      if (!redirected) {
        setErrorMessage(
          '세션이 만료되었습니다. 관제서버 또는 포털에서 다시 접근해주세요.',
        );
      }
      return;
    }

    // mock 정합 — INTERNAL 채널은 /dashboard 진입
    const target = claims.channel === 'PORTAL' ? '/portal' : '/dashboard';
    navigate(target, { replace: true });
  }, [params, navigate]);

  if (errorMessage !== null) {
    return (
      <div
        className="flex min-h-screen items-center justify-center"
        role="alert"
        aria-live="assertive"
      >
        <div className="max-w-sm rounded-lg border border-red-200 bg-red-50 p-6 text-center shadow-sm">
          <p className="text-sm font-medium text-red-700">{errorMessage}</p>
        </div>
      </div>
    );
  }

  return (
    <div className="flex min-h-screen items-center justify-center" role="status" aria-live="polite">
      <Spinner size="lg" label="세션을 확인하는 중" />
    </div>
  );
}
