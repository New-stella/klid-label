import { ReactNode, useEffect } from 'react';
import { Navigate } from 'react-router-dom';

import { syncPortalSessionFromHandoff } from '@/features/auth/portalSession';
import { redirectToUpstream } from '@/features/auth/redirectToUpstream';
import {
  retryServerRole,
  SERVER_ROLE_UNKNOWN_DESC,
  SERVER_ROLE_UNKNOWN_TITLE,
} from '@/features/auth/sessionBootstrap';
import { isPortalEmbedChannel } from '@/lib/buildChannel';
import { roleSatisfiesAny } from '@/lib/authz';
import type { Channel, Role } from '@/lib/api/types';
import { useAuthStore } from '@/stores/useAuthStore';
import { ErrorState } from '@/components/common/ErrorState';
import { Spinner } from '@/components/common/Spinner';

type EmbedNoticeKind = 'auth-required' | 'role-required' | 'forbidden';

const EMBED_NOTICE_COPY: Record<EmbedNoticeKind, { title: string; message: string }> = {
  'auth-required': {
    title: '인증이 필요합니다',
    message: '로그인 정보를 확인할 수 없습니다. 포털에서 다시 접속해 주세요.',
  },
  'role-required': {
    title: '역할이 아직 부여되지 않았습니다',
    message: '이 화면을 사용하려면 역할 부여가 필요합니다. 관리자에게 문의해 주세요.',
  },
  forbidden: {
    title: '접근 권한이 없습니다',
    message: '현재 역할로는 이 화면에 접근할 수 없습니다.',
  },
};

/**
 * 포털 임베드(Module Federation Remote)에서 우리 라우트로의 이동을 대신하는 안내.
 *
 * 배경: `RoleGuard`/`ChannelGuard`/`AuthenticatedGuard` 는 조건이 안 맞으면
 * `<Navigate to="/forbidden">`·`"/ingress"`·`"/role-claim">` 으로 **문서 전체**를 이동시킨다.
 * 내부(관제) 채널 빌드에서는 그 경로가 우리 라우터 안에 실재해 문제가 없지만, 포털 채널
 * 빌드는 **포털 Host 문서 위에 마운트되는 Module Federation Remote** 라 그 이동이 우리
 * 라우터가 아니라 Host 문서 전체에 걸린다. 포털이 선언한 라우트 147개를 전수 확인한 결과
 * 그 세 경로가 하나도 없다 — 이동하면 포털 404 가 뜬다. 같은 도메인이라 해결되는 문제가
 * 아니라 같은 문서에 마운트되기 때문에 생기는 문제라, 라우트를 추가해도 소용없다.
 *
 * 그래서 포털 채널 빌드에서는 이동 대신 **그 자리**에 이 안내를 그린다. 접근이 거부되는
 * 사실 자체는 그대로다(가드 판정·인가 로직은 전혀 바뀌지 않는다) — 다만 그 사실을 알리는
 * 방법만 "문서를 옮긴다"에서 "그 자리에 그린다"로 바뀐다.
 *
 * ⚠ 토큰 만료로 상위 시스템(포털/관제 로그인 페이지)으로 가는 `redirectToUpstream` 은
 * 이 안내의 대상이 아니다 — 그건 우리 라우트가 아니라 Host 밖의 상위 시스템으로 가는
 * 이동이라 Host 문서 이탈이 오히려 정확하다(Host 도 해야 할 일이고, `?next=` 로 복귀
 * 주소까지 실어 보낸다). 「우리 라우트로의 이동」만 막고 「상위 시스템으로의 이동」은
 * 그대로 둔다 — 이게 이 구분의 핵심이다.
 */
function PortalEmbedNotice({ kind }: { kind: EmbedNoticeKind }) {
  const { title, message } = EMBED_NOTICE_COPY[kind];
  return (
    <div
      className="flex h-full items-center justify-center py-10"
      data-testid="portal-embed-guard-notice"
    >
      <ErrorState title={title} message={message} />
    </div>
  );
}

// [@design INT-013]
/**
 * 포털 채널에서 세션이 비었을 때 <b>Host 창구에 다시 물어 되살린다</b>.
 *
 * 포털 채널의 스토어 세션은 진실원이 아니라 Host 메모리의 거울이다. 그런데 그것을 비우는 자리는
 * 셋(요청 인터셉터의 401 · 이 파일의 만료 분기 둘)인데 <b>채우는 자리는 진입 화면 하나뿐이고,
 * 포털 채널 가드는 그 화면으로 가지 않는다</b>(이동 대신 제자리 안내를 그리므로). 그래서 한 번
 * 비면 영영 다시 차지 않았고, 쓰던 화면이 갑자기 인증 안내로 바뀌어 새로고침 외에는 방법이 없었다.
 *
 * 여기서 다시 묻는 것이 설계가 규정한 모습이다 — *"Remote 는 매 호출 시점에 토큰 획득 창구로
 * 토큰을 취득한다"*. 요청 헤더 축은 이미 그렇게 하고 있었고 화면 가드 축만 한 번 베낀 값에
 * 머물러 있었다.
 *
 * ⚠ <b>되풀이는 되맞춤 쪽이 막는다</b> — 거부당한 토큰과 같은 값이면 다시 채우지 않는다
 *   (`features/auth/portalSession`). 여기서 조건을 하나 더 두면 판정이 두 곳으로 갈린다.
 * ⚠ <b>렌더 중에 부르지 않는다.</b> 되맞춤은 스토어에 쓰므로 렌더 도중 호출하면 렌더 중 상태
 *   변경이 된다. 효과로 미루면 안내가 한 프레임 비쳤다가 화면으로 바뀐다 — 그 대가를 받는다.
 *   (첫 진입의 한 프레임은 `App` 이 hydrate 와 함께 미리 맞춰 없앤다.)
 *
 * @param shouldRecover 세션이 없다고 판정된 상태인지. 거짓이면 아무것도 하지 않는다.
 */
function usePortalSessionRecovery(shouldRecover: boolean): void {
  useEffect(() => {
    if (!shouldRecover) return;
    // 되맞춤은 비동기다(Host 에 묻는다). 결과는 스토어에 반영되어 재렌더로 드러나므로
    // 여기서 기다릴 것이 없다 — `void` 로 「의도적으로 기다리지 않음」을 표시한다.
    void syncPortalSessionFromHandoff();
  }, [shouldRecover]);
}

// [@design ADR-063] [@design AC-1017] [@design SCREEN-001] [@design SCREEN-002]
// [@design SCREEN-003] [@design API-006]
/**
 * 서버 인가 역할을 <b>확인하지 못했을 때</b>의 제자리 안내.
 *
 * ★<b>이 자리에 「역할이 없습니다」를 두지 않는다.</b> 역할을 <b>확인하지 못한 상태</b>와
 * 역할이 <b>없는 상태</b>는 다르다. 둘을 뭉치면 인증 실패·서버 장애가 최초 관리자 등록
 * 화면으로 떨어져 *"이 시스템에는 아직 관리자가 없습니다"* 로 표시된다 — 진입 화면이 이미
 * 한 번 고친 결함이며(`SessionIngressPage` 의 `ERROR_ROLE_UNKNOWN`), 복원 경로에서 같은
 * 실수를 되풀이하지 않는다.
 *
 * ⚠ 문구는 진입 화면과 <b>같은 상수</b>를 쓴다 — 두 곳에 각각 적으면 한쪽만 고쳐진다.
 *
 * <h3>★재시도 조작 (2026-09-08 · {@code ADR-063} ⑨)</h3>
 * 확보 실패는 캐시되므로 그대로 두면 <b>문서를 새로 불러올 때까지 영구히 실패</b>다. 문구는
 * "잠시 후 다시 시도해주세요"인데 사용자가 할 수 있는 시도가 없었다. 라벨은 확정 사양
 * (`SCREEN-001`)이 정한 <b>"다시 시도"</b>이며 그 값이 {@code ErrorState} 의 기본값과 같다.
 *
 * ⚠ <b>자동 반복으로 만들지 말 것</b> — 사람이 누를 때만 다시 태운다. `useEffect` 로 재시도를
 *   걸면 실패 → 렌더 → 재시도 → 실패 고리가 되어 장애 구간에 조회가 폭주한다.
 */
function ServerRoleUnknownNotice() {
  return (
    <div
      className="flex h-full items-center justify-center py-10"
      data-testid="server-role-unknown-notice"
    >
      <ErrorState
        title={SERVER_ROLE_UNKNOWN_TITLE}
        message={SERVER_ROLE_UNKNOWN_DESC}
        onRetry={() => {
          void retryServerRole();
        }}
      />
    </div>
  );
}

interface RoleGuardProps {
  /**
   * 그 자리가 요구하는 역할. 내부 채널 라우트는 `@/lib/routeAccess` 의 선언에서
   * `allowFor(경로)` 로 받아 넘긴다 — 좌측 메뉴가 읽는 것과 같은 값이다.
   *
   * `readonly` 인 것은 그 선언이 얼린 배열을 그대로 내주기 때문이다(복사본을 만들면 라우트가
   * 정말 그 선언에서 값을 받았는지 참조로 확인할 수 없다).
   */
  allow: readonly Role[];
  children: ReactNode;
}

/**
 * 역할 기반 접근 제어.
 * - isHydrated false → 토큰 복원 대기 (스피너)
 * - claims 없음 → /ingress (재인계 시도)
 * - exp 만료 → 상위 시스템 redirect
 * - 서버 역할 미확보 / 확보 중 → 판정 보류 (스피너)
 * - 서버 역할 확보 실패 + 토큰에도 역할 없음 → 제자리 오류 안내 (★/role-claim 아님, 재시도 제공)
 * - role 미부여(null) → /role-claim (Phase 2 — 권한 자가 부여 화면)
 * - 역할 불일치 → /forbidden
 *
 * [@design ADR-063] [@design UC-041] [@design AC-1016] [@design AC-1017]
 * ★<b>역할 판정을 하는 유일한 가드</b>라 서버 역할 확보 상태를 읽는 것도 여기뿐이다.
 * `ChannelGuard` 는 토큰이 싣고 온 채널만 보고, `AuthenticatedGuard` 는 역할 미부여
 * 사용자를 <b>일부러 통과시키는</b> 자리다 — 둘에 대기를 붙이면 지킬 것 없는 대기가 된다.
 */
export function RoleGuard({ allow, children }: RoleGuardProps) {
  const claims = useAuthStore((s) => s.claims);
  const isHydrated = useAuthStore((s) => s.isHydrated);
  const serverRoleStatus = useAuthStore((s) => s.serverRoleStatus);
  const expired = isExpired(claims?.exp);

  usePortalSessionRecovery(isHydrated && !claims);

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
    if (isPortalEmbedChannel()) return <PortalEmbedNotice kind="auth-required" />;
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
  // [@design ADR-063] [@design SEQ-034] [@design AC-1016] [@design AC-1098]
  // ★서버 인가 역할을 <아직 확보하지 못했으면 판정하지 않는다>. 새로고침 복원 직후가 그
  //   순간이다 — 저장소는 토큰만 되살리고 서버 역할은 화면 수명과 함께 사라지므로, 여기서
  //   기다리지 않으면 역할 보유자가 <역할 없음>으로 판정돼 권한 요청 안내로 튕긴다.
  //
  //   ★<b>미확보(`unacquired`)와 확보 중(`pending`)은 사유가 다르지만 판정은 같다</b> — 둘 다
  //     <아직 결과가 없다>. 예전에는 미확보가 `idle` 과 한 값이라 <세션 없음>과 뭉쳐 있었고,
  //     그래서 <조용히 통과>했다(서버 역할을 한 번도 확인하지 않은 채 화면이 열렸다).
  //     ⚠ `idle` 을 여기에 끌어들이지 말 것 — 그건 <물을 것이 없다>는 뜻이라 통과가 맞다.
  //       끌어들이면 로그인하지 않은 사용자가 진입 안내 대신 영구 스피너를 본다.
  //
  //   ⚠ 조회 자체를 이 자리로 끌어올리지 말 것. 가드는 렌더 시점에 동기로 판정하는 자리라
  //     여기에 비동기 조회를 넣으면 <역할을 가진 사용자의 모든 라우트 전환>까지 그 조회를
  //     기다린다. 조회는 부팅 1회(`features/auth/sessionBootstrap`)에서 끝내고 여기서는
  //     그 결과만 읽는다.
  if (serverRoleStatus === 'unacquired' || serverRoleStatus === 'pending') {
    return (
      <div className="flex h-full items-center justify-center py-10">
        <Spinner label="인증 확인 중" />
      </div>
    );
  }
  // ★확인하지 <못한> 것과 역할이 <없는> 것을 가른다 (@design AC-1017).
  //   토큰에 역할이 남아 있으면 종전 폴백대로 통과시킨다 — 유효 세션을 막다른 길에 빠뜨리지
  //   않는다는 취지는 그대로이고, 인가의 최종 판정은 어차피 서버가 소유한다. 토큰에도 역할이
  //   없을 때만 <오류로 드러낸다> — 그 갈래를 /role-claim 으로 보내면 서버 장애가
  //   *"아직 관리자가 없습니다"* 로 위장된다.
  if (serverRoleStatus === 'failed' && !claims.role) {
    return <ServerRoleUnknownNotice />;
  }
  // 인증은 되었으나 role 이 부여되지 않은 사용자는 /role-claim 으로 안내.
  // INTERNAL 채널에만 적용 (PORTAL_USER 은 토큰 발급 시점에 항상 role 이 부여됨).
  //
  // ★도착지 판정 축은 「역할 없음」 <하나가 아니라> 「역할 없음 + 창구 열림」 <둘>이다
  //   (@design SCREEN-002 v32 · @design AC-1098). 그 둘째 축은 <그 화면이 소유한다> — 화면이
  //   진입 시점에 개폐를 조회해(API-245) 열림이면 최초 관리자 등록 모습을, 닫힘이면 권한 요청
  //   안내 모습을 그린다. 즉 여기서 보내는 곳은 「관리자 등록 화면」이 아니라 <두 모습을 가진
  //   화면>이며, 역할이 없다는 이유만으로 *"아직 관리자가 없습니다"* 가 뜨지 않는다.
  //
  //   ⚠ 개폐 조회를 <가드로 끌어올리지 말 것>. 가드는 렌더 시점에 동기로 판정하는 자리라
  //     비동기 조회를 넣으면 **역할을 가진 사용자의 모든 라우트 전환까지** 그 조회를 기다리게
  //     된다(그 사용자에게는 개폐가 애초에 무관한 정보다). 축이 둘이라는 것과 그 둘을 <같은
  //     곳에서> 판정한다는 것은 다른 이야기다.
  if (!claims.role && claims.channel === 'INTERNAL') {
    if (isPortalEmbedChannel()) return <PortalEmbedNotice kind="role-required" />;
    return <Navigate to="/role-claim" replace />;
  }
  // ★역할 포함 관계 판정은 `@/lib/authz` 가 소유한다 — 허용 목록에 이름이 그대로 있는지만
  //   보면 상위 역할이 하위 역할 자리에서 거부된다(관리자가 검수자 화면에서 막히던 결함).
  if (!roleSatisfiesAny(claims.role, allow)) {
    if (isPortalEmbedChannel()) return <PortalEmbedNotice kind="forbidden" />;
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

  usePortalSessionRecovery(isHydrated && !claims);

  if (!isHydrated) {
    return (
      <div className="flex h-full items-center justify-center py-10">
        <Spinner label="인증 확인 중" />
      </div>
    );
  }
  if (!claims) {
    if (isPortalEmbedChannel()) return <PortalEmbedNotice kind="auth-required" />;
    return <Navigate to="/ingress" replace />;
  }
  if (claims.channel !== channel) {
    if (isPortalEmbedChannel()) return <PortalEmbedNotice kind="forbidden" />;
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

  usePortalSessionRecovery(isHydrated && !claims);

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
    if (isPortalEmbedChannel()) return <PortalEmbedNotice kind="auth-required" />;
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
