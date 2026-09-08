import { useEffect, useRef, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';

import { Alert } from '@/components/common/Alert';
import { Spinner } from '@/components/common/Spinner';
import type { Channel, Role } from '@/lib/api/types';
import { isPortalEmbedChannel, IS_PORTAL_CHANNEL_BUILD } from '@/lib/buildChannel';
import { useAuthStore } from '@/stores/useAuthStore';
import { isDevLoginEnabled } from '@/lib/devLogin';

import { detectChannel, isUpstreamLoginConfigured, redirectToUpstream } from './redirectToUpstream';
import {
  ensureServerRole,
  SERVER_ROLE_UNKNOWN_DESC,
  SERVER_ROLE_UNKNOWN_TITLE,
} from './sessionBootstrap';
import { getAccessToken } from './tokenHandoff';
import { UpstreamLoginConfigHint } from './UpstreamLoginConfigHint';
import { resolveToken } from './tokenIngress';

const COOKIE_NAME = 'klid_jwt';
// ★배포는 채널마다 갈린다(@design ADR-012) — 관제향 빌드는 '관제서버', 포털향 빌드는 '포털'
//   한 가지만 쓴다. '관제 또는 포털' 병기는 배포 향과 맞지 않아 쓰지 않는다.
const UPSTREAM_LABEL = IS_PORTAL_CHANNEL_BUILD ? '포털' : '관제서버';

/**
 * 진입 직후 기본 상태의 문구(SCREEN-001 ①).
 *
 * ★두 줄 모두 <b>화면에 보이는 텍스트</b>다. 예전에는 제목을 {@code Spinner} 의 {@code label}
 * prop 으로만 넘겼는데 그 값은 {@code sr-only} 로만 렌더돼, 화면에는 회전하는 원만 있고 글자가
 * 하나도 보이지 않았다. 눈으로 보는 사용자에게는 "무엇을 기다리는 중인지"가 통째로 없었다.
 */
const LOADING_TITLE = '세션을 확인하는 중';
const LOADING_DESC =
  `${UPSTREAM_LABEL}에서 전달한 인증 정보를 확인하고 있습니다. 확인이 끝나면 자동으로 이동합니다.`;

/**
 * 인증 실패 안내(SCREEN-001 ②) — 분류(제목) + 상세(본문).
 * 예전에는 두 문장을 이어 붙인 한 줄이라 시안의 제목/본문 구분이 없었다.
 */
interface IngressError {
  readonly title: string;
  readonly description: string;
}

/** 두 실패 사유가 공유하는 상세 — 사용자가 할 수 있는 행동은 같다. */
const REENTER_DESC = `${UPSTREAM_LABEL}에서 다시 접근해주세요.`;

const ERROR_NO_TOKEN: IngressError = {
  title: '로그인 서버에 연결할 수 없습니다',
  description: REENTER_DESC,
};
const ERROR_EXPIRED: IngressError = {
  title: '세션이 만료되었습니다',
  description: REENTER_DESC,
};
/**
 * 서버 인가 조회(`GET /v1/me`) 자체가 실패했고 토큰 클레임에도 역할이 없을 때. [@design SEQ-034]
 *
 * ★<b>이 자리에 관리자 등록 화면을 두지 않는다.</b> 예전에는 조회 실패를 전부 「역할 없음」으로
 * 뭉개 `/role-claim` 으로 보냈고, 그래서 **인증 실패·서버 장애가 *"이 시스템에는 아직 관리자가
 * 없습니다"* 로 표시**됐다(246 실측 2026-09-07 — 만료 토큰이 남아 401 을 받고 그 화면에 도달).
 * 역할을 <b>확인하지 못한 상태</b>와 역할이 <b>없는 상태</b>는 다르다.
 */
const ERROR_ROLE_UNKNOWN: IngressError = {
  title: SERVER_ROLE_UNKNOWN_TITLE,
  description: SERVER_ROLE_UNKNOWN_DESC,
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
 *   5) 채널별 메인 진입점 navigate
 *      - PORTAL → /portal (종전 그대로, 서버 role 조회 안 함)
 *      - INTERNAL → GET /v1/me 로 <서버 인가 role 과 이름>을 확인해 claims 에 주입한 뒤
 *          role 있으면 /dashboard, role=null(무권한)이면 /role-claim(관리자 등록 화면)
 *        조회가 실패하면 갈래를 나눈다 — 401 은 상위 시스템 재로그인, 그 밖은 토큰 role 이
 *        있으면 종전 폴백(/dashboard), 없으면 오류 표시. <어느 쪽도 /role-claim 이 아니다>.
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

    // [@design SCREEN-001] [@design SCREEN-002] [@design ADR-063] [@design UC-041]
    // [@design SEQ-034] [@design AC-1016] [@design AC-1017] [@design AC-1098] [@design API-006]
    // 인가 role 의 진실원은 <토큰 클레임이 아니라 GET /v1/me> 다. 관제 진입자는 진입 순간
    // userNo 를 발급받고 role=null(무권한)로 진입할 수 있으며, 관제 토큰의 role 클레임은
    // 관제 자신의 역할값이라 우리 역할 집합과 겹치지 않을 수 있다 — 그것만으로 판정하면
    // 역할 보유자를 무권한으로 오인한다.
    //
    // ★<b>채널을 가리지 않고 묻는다</b> (2026-09-08). 예전에는 포털 채널이 여기서 곧바로
    //   되돌아 나가 서버 역할을 <한 번도> 묻지 않았다. 포털 토큰에 역할이 늘 실려 튕기지
    //   않았을 뿐, 서버가 역할을 바꾸거나 회수해도 화면은 옛 역할로 계속 움직였다.
    //   ⚠ 조회 시점을 채널마다 다르게 만들지 말 것 — 두 벌이 되면 한쪽만 낡는다.
    //
    // ★조회가 <실패>했을 때 role=null 로 뭉개지 않는다 (2026-09-07). 실패는 세 갈래다:
    //   ① 401(인증 실패·만료)  → 상위 시스템 재로그인. 기존 만료 처리와 <같은 결말>이다.
    //   ② 그 밖 + 토큰 role 有 → 종전 폴백 그대로. ★유효 세션을 막지 않는다는 폴백의 취지는
    //                            그대로 살린다 — 인가 최종 판정은 어차피 서버가 소유한다.
    //   ③ 그 밖 + 토큰 role 無 → 오류 표시. <여기가 고친 자리다> — 예전에는 이 갈래가
    //                            /role-claim 으로 떨어져 서버 장애가 "관리자가 없습니다"로
    //                            표시됐다.
    //   ⚠ 구 동작 폐기 — *"조회 실패 시에는 토큰 클레임 role 로 폴백"* 을 <전 갈래>에 적용하던
    //     것. ②만 남고 ①③은 갈라졌다. 되돌리면 오류가 다시 사양으로 위장된다.
    //
    // ⚠ 조회는 `ensureServerRole` 한 곳이 소유한다 — 여기서 `getMe` 를 직접 부르면 새로고침
    //   복원 경로와 조회·주입 규약이 두 벌이 되고, 같은 부팅에서 `/me` 가 두 번 나간다.
    const routeAfterHandoff = async (channel: Channel, fallbackRole: Role | null) => {
      const outcome = await ensureServerRole();
      let effectiveRole: Role | null;
      if (outcome.kind === 'resolved') {
        effectiveRole = outcome.role;
      } else if (outcome.kind === 'unauthorized') {
        // ① 인증이 유효하지 않다 — 역할 없음이 아니다. HTTP 계층이 이미 토큰을 비웠으나
        //    여기서도 명시적으로 비워 이 갈래를 자족적으로 만든다(중복 호출은 무해).
        useAuthStore.getState().clear();
        handleAuthFailure(channel, ERROR_EXPIRED);
        return;
      } else if (fallbackRole == null) {
        // ③ 역할을 <확인하지 못했다>. 관리자 등록 화면으로 보내지 않는다.
        setError(ERROR_ROLE_UNKNOWN);
        return;
      } else {
        // ② 유효 세션 폴백 — 종전 동작.
        effectiveRole = fallbackRole;
      }

      // 포털 채널의 도착지는 종전 그대로다 — <바뀐 것은 도착지가 아니라 「묻고 나서 간다」는
      // 순서>다. PORTAL 은 서버도 역할을 고정 부여하므로 무권한 온보딩 갈래가 없다.
      if (channel === 'PORTAL') {
        navigate('/portal', { replace: true });
        return;
      }
      navigate(effectiveRole != null ? '/dashboard' : '/role-claim', { replace: true });
    };
    void routeAfterHandoff(claims.channel, claims.role);
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
