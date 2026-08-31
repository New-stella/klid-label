import { useEffect, useRef, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';

import { Alert } from '@/components/common/Alert';
import { Spinner } from '@/components/common/Spinner';
import type { Channel } from '@/lib/api/types';
import { isPortalEmbedChannel } from '@/lib/buildChannel';
import { useAuthStore } from '@/stores/useAuthStore';
import { isDevLoginEnabled } from '@/lib/devLogin';

import { detectChannel, isUpstreamLoginConfigured, redirectToUpstream } from './redirectToUpstream';
import { getAccessToken } from './tokenHandoff';
import { UpstreamLoginConfigHint } from './UpstreamLoginConfigHint';
import { resolveToken } from './tokenIngress';

const COOKIE_NAME = 'klid_jwt';

/**
 * 진입 직후 기본 상태의 문구(SCREEN-001 ①).
 *
 * ★두 줄 모두 <b>화면에 보이는 텍스트</b>다. 예전에는 제목을 {@code Spinner} 의 {@code label}
 * prop 으로만 넘겼는데 그 값은 {@code sr-only} 로만 렌더돼, 화면에는 회전하는 원만 있고 글자가
 * 하나도 보이지 않았다. 눈으로 보는 사용자에게는 "무엇을 기다리는 중인지"가 통째로 없었다.
 */
const LOADING_TITLE = '세션을 확인하는 중';
const LOADING_DESC =
  '관제서버 또는 포털에서 전달한 인증 정보를 확인하고 있습니다. 확인이 끝나면 자동으로 이동합니다.';

/**
 * 인증 실패 안내(SCREEN-001 ②) — 분류(제목) + 상세(본문).
 * 예전에는 두 문장을 이어 붙인 한 줄이라 시안의 제목/본문 구분이 없었다.
 */
interface IngressError {
  readonly title: string;
  readonly description: string;
}

/** 두 실패 사유가 공유하는 상세 — 사용자가 할 수 있는 행동은 같다. */
const REENTER_DESC = '관제서버 또는 포털에서 다시 접근해주세요.';

const ERROR_NO_TOKEN: IngressError = {
  title: '로그인 서버에 연결할 수 없습니다',
  description: REENTER_DESC,
};
const ERROR_EXPIRED: IngressError = {
  title: '세션이 만료되었습니다',
  description: REENTER_DESC,
};

/**
 * `/ingress` 진입 페이지.
 * 흐름:
 *   1) [개발 전용] VITE_DEV_TOKEN 환경변수로 토큰 자동 주입 (DEV 빌드에서만)
 *   2) URL `?token=` 또는 cookie/localStorage 에서 토큰 수령 (env 전략)
 *   3) 토큰 없음 / claims 없음 / 만료 시:
 *      - DEV 빌드 → /dev/login 으로 이동 (관제서버 미연결 환경 막다른 길 방지)
 *      - 운영 → detectChannel 기준 상위 시스템 redirect, 실패 시 에러 메시지
 *   4) JWT decode → 만료 검증 → useAuthStore.setToken
 *   5) 채널별 메인 진입점 navigate (INTERNAL → /dashboard, PORTAL → /portal)
 */
export function SessionIngressPage() {
  const [params] = useSearchParams();
  const navigate = useNavigate();
  const [error, setError] = useState<IngressError | null>(null);
  // 이동이 <설정 누락>으로 불가능한 경우의 채널. 세션 문구는 그대로 두고 원인만 덧붙인다
  // (상위 서버 장애와 구분되지 않던 것이 이 화면의 결함이었다). null 이면 해당 없음.
  const [configMissingChannel, setConfigMissingChannel] = useState<Channel | null>(null);
  // StrictMode 더블 마운트 보호
  const ranRef = useRef(false);

  useEffect(() => {
    if (ranRef.current) return;
    ranRef.current = true;

    // 인증 실패 fallback: dev 로그인 노출 시 /dev/login, 운영은 upstream redirect → 실패 시 에러 메시지.
    // (DEV 빌드 또는 VITE_DEV_LOGIN_ENABLED=true 폐쇄망 bring-up 빌드에서만 /dev/login 으로 보낸다.)
    const handleAuthFailure = (
      channel: ReturnType<typeof detectChannel>,
      failure: IngressError,
    ) => {
      if (isDevLoginEnabled()) {
        navigate('/dev/login', { replace: true });
        return;
      }
      const redirected = redirectToUpstream(channel);
      if (redirected) return;
      // 이동에 실패했다 — 무엇이 일어났는지(failure)는 그대로 알리고, 그 원인이 <설정 누락>이면
      // 손댈 곳까지 덧붙인다. 예전에는 설정 누락이 상위 서버 장애와 같은 문구로 덮였다.
      if (!isUpstreamLoginConfigured(channel)) setConfigMissingChannel(channel);
      setError(failure);
    };

    // [@design INT-013]
    // 인계 채널은 **채널마다 다르다.** 관제 채널은 같은 출처 브라우저 저장소가 곧 인계 채널이라
    // 그대로 읽는다. 포털 채널은 access token 이 **Host 메모리에만** 있고 저장소를 쓰지 않기로
    // 한 채널이라, 저장소를 읽으면 ①아무것도 없거나 ②앞 채널이 남긴 **죽은 토큰**을 줍는다.
    // 그래서 그 채널에서는 획득 창구(`features/auth/tokenHandoff`)에 묻는다 — 저장소 직접
    // 읽기를 창구 뒤로 추상화한다는 설계의 나머지 절반이 이 지점이다.
    //
    // ⚠ 관제 채널의 동작은 한 글자도 바뀌지 않는다(URL·쿠키·저장소 전략 그대로).
    let token = isPortalEmbedChannel()
      ? getAccessToken()
      : resolveToken({
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
      handleAuthFailure(detectChannel(), ERROR_NO_TOKEN);
      return;
    }

    // 토큰을 store에 적재 (decode + 타입 가드는 store 내부에서 수행)
    useAuthStore.getState().setToken(token);
    const claims = useAuthStore.getState().claims;

    if (!claims) {
      handleAuthFailure(detectChannel(), ERROR_NO_TOKEN);
      return;
    }

    // 만료 검증
    const nowSec = Math.floor(Date.now() / 1000);
    if (claims.exp <= nowSec) {
      useAuthStore.getState().clear();
      handleAuthFailure(claims.channel, ERROR_EXPIRED);
      return;
    }

    // mock 정합 — INTERNAL 채널은 /dashboard 진입
    const target = claims.channel === 'PORTAL' ? '/portal' : '/dashboard';
    navigate(target, { replace: true });
  }, [params, navigate]);

  if (error !== null) {
    return (
      <div className="flex min-h-screen items-center justify-center px-4">
        <Alert
          variant="error"
          aria-live="assertive"
          title={error.title}
          className="max-w-sm shadow-sm"
        >
          {error.description}
          {configMissingChannel !== null && (
            <UpstreamLoginConfigHint channel={configMissingChannel} />
          )}
        </Alert>
      </div>
    );
  }

  return (
    <div className="flex min-h-screen items-center justify-center px-4">
      {/*
        role/aria-live 는 이 블록이 소유하고, 낭독 대상은 아래 보이는 두 줄이다.
        Spinner 자신도 role="status" + sr-only 문구를 갖고 있어 그대로 두면 같은 안내가 두 번
        읽히므로, 회전 표시는 순수 장식으로 낮춰(aria-hidden) 접근성 트리에서 뺀다.
        ⚠ Spinner 의 sr-only 자체는 건드리지 않는다 — 다른 화면들이 쓰는 공용 컴포넌트다.
      */}
      <div
        role="status"
        aria-live="polite"
        className="flex flex-col items-center gap-3 text-center"
      >
        <div aria-hidden="true">
          <Spinner size="lg" />
        </div>
        <p className="text-body-lg text-gray-900">{LOADING_TITLE}</p>
        <p className="text-body-sm text-gray-600">{LOADING_DESC}</p>
      </div>
    </div>
  );
}
