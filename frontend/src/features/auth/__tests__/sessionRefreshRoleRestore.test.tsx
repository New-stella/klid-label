import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import MockAdapter from 'axios-mock-adapter';

import { apiClient } from '@/lib/api/client';
import { Channel, Role } from '@/lib/api/types';
import { RoleGuard } from '@/router/guards';
import { useAuthStore } from '@/stores/useAuthStore';

import { ensureServerRole, resetServerRoleResolution, restoreSession } from '../sessionBootstrap';

/**
 * ★<b>새로고침</b> 복원 경로의 회귀 가드 — 진입 화면(`/ingress`)을 거치지 않는다.
 *
 * <h3>이 파일이 재현하는 것</h3>
 * 사용자가 관제에서 인계받아 들어와 어떤 화면을 보다가 <b>브라우저 새로고침</b>을 하면, 앱은
 * 진입 화면을 다시 지나지 않고 저장소의 토큰만 복원한다. 서버 인가 역할은 저장소에 보관하지
 * 않으므로(그래야 서버가 역할을 회수했을 때 옛 값이 남지 않는다) 그 순간 통째로 사라지고,
 * 다시 묻지 않으면 역할 보유자가 <b>「역할 없음」으로 판정</b>돼 권한 요청 안내로 튕긴다.
 *
 * ⚠ <b>진입 화면을 거치는 경로를 재현하면 이 결함을 아예 지나가지 않는다</b> — 그 경로는
 *   원래 서버 역할을 묻기 때문이다. 그래서 여기서는 `restoreSession()`(앱 부팅이 부르는 것)
 *   만 부르고 `SessionIngressPage` 는 렌더하지 않는다.
 *
 * <h3>실제 토큰을 모사한다</h3>
 * 관제 인계 토큰의 `role` 클레임은 <b>관제 자신의 역할값</b>이라 우리 역할 집합과 겹치지 않을
 * 수 있고, 그때 디코더가 그것을 역할 미부여로 낮춘다(`decodeJwtPayload`). 그래서 픽스처의
 * 역할 클레임을 `LEARN_MANAGER` 로 둔다 — 「클레임이 아예 없는」 토큰만 재현하면 <b>클레임은
 * 있는데 우리 값이 아닌</b> 실제 상황이 검사 밖으로 빠진다.
 *
 * [@design ADR-063] [@design UC-041] [@design SEQ-034] [@design SCREEN-001]
 * [@design AC-1016] [@design AC-1017] [@design API-006]
 */

function b64url(obj: Record<string, unknown>): string {
  const utf8 = unescape(encodeURIComponent(JSON.stringify(obj)));
  return btoa(utf8).replace(/=+$/, '').replace(/\+/g, '-').replace(/\//g, '_');
}
function buildJwt(payload: Record<string, unknown>): string {
  return `${b64url({ alg: 'HS256', typ: 'JWT' })}.${b64url(payload)}.signature`;
}

/** 관제 인계 토큰 — 우리 역할 집합에 없는 관제 역할값을 싣는다(디코드 결과 role=null). */
const CONTROL_JWT = buildJwt({
  sub: '9001',
  role: 'LEARN_MANAGER',
  channel: 'INTERNAL',
  name: '시스템관리자',
  exp: 9999999999,
});

const PORTAL_JWT = buildJwt({
  sub: 'p-1',
  role: 'PORTAL_USER',
  channel: 'PORTAL',
  exp: 9999999999,
});

function meBody(role: string | null, name?: string) {
  return {
    success: true,
    data: { sub: '9001', role, channel: 'INTERNAL', ...(name === undefined ? {} : { name }) },
    message: null,
    errorCode: null,
  };
}

/** 새로고침 = 진입 화면을 거치지 않고 앱이 부팅되는 것. */
function bootAsRefresh() {
  restoreSession();
}

function renderGuardedRoute(allow: readonly Role[] = [Role.ADMIN, Role.REVIEWER, Role.WORKER]) {
  return render(
    <MemoryRouter initialEntries={['/videos']}>
      <Routes>
        <Route
          path="/videos"
          element={
            <RoleGuard allow={allow}>
              <div>VIDEOS_PAGE</div>
            </RoleGuard>
          }
        />
        <Route path="/role-claim" element={<div>ROLE_CLAIM_PAGE</div>} />
        <Route path="/forbidden" element={<div>FORBIDDEN_PAGE</div>} />
        <Route path="/ingress" element={<div>INGRESS_PAGE</div>} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('새로고침 복원 — 서버 인가 역할 재확보', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    sessionStorage.clear();
    useAuthStore.setState({ token: null, claims: null, isHydrated: false });
    resetServerRoleResolution();
    mock = new MockAdapter(apiClient);
  });

  afterEach(() => {
    mock.restore();
    sessionStorage.clear();
    vi.unstubAllEnvs();
  });

  // ── 수용기준 1 · 2 ────────────────────────────────────────────────
  it('★새로고침하면_보던_화면이_그대로_다시_뜬다_권한요청_안내로_튕기지_않는다', async () => {
    sessionStorage.setItem('klid_jwt', CONTROL_JWT);
    mock.onGet('/me').reply(200, meBody('ADMIN', '시스템관리자'));

    bootAsRefresh();
    renderGuardedRoute();

    expect(await screen.findByText('VIDEOS_PAGE')).toBeInTheDocument();
    expect(screen.queryByText('ROLE_CLAIM_PAGE')).toBeNull();
    // 서버 진실원이 claims 에 실제로 주입돼야 가드가 그것을 본다.
    expect(useAuthStore.getState().claims?.role).toBe('ADMIN');
  });

  it('★확보가_끝나기_전에는_스피너를_보이고_권한요청_안내가_스치지_않는다', async () => {
    sessionStorage.setItem('klid_jwt', CONTROL_JWT);
    // 응답을 우리가 붙잡는다 — 붙잡지 않으면 「조회 중」 상태를 관측할 창이 없다.
    //
    // ⚠ 약속을 <b>미리</b> 만든다. 응답 핸들러 안에서 만들면 그 함수는 요청이 실제로 날아간
    //   뒤에야 실행되므로, 그 전에 부른 해제기는 <b>아직 아무것도 하지 않는 초기값</b>이라
    //   영영 풀리지 않는다(실측 — 이 케이스만 타임아웃으로 죽었다).
    // ⚠ 홀더 객체로 둔다. `let release: (() => void) | null` 은 TS 제어흐름 분석이 executor
    //   안의 대입을 못 봐 `never` 로 좁히고, vitest 는 통과하는데 build 만 깨진다.
    const gate = { release: () => {} };
    const gatedReply = new Promise<[number, unknown]>((resolve) => {
      gate.release = () => resolve([200, meBody('ADMIN')]);
    });
    mock.onGet('/me').reply(() => gatedReply);

    bootAsRefresh();
    renderGuardedRoute();

    // 조회 중 — 판정을 미룬다. 빈 화면도, 잘못된 안내도 아니다.
    expect(screen.getByText('인증 확인 중')).toBeInTheDocument();
    expect(screen.queryByText('ROLE_CLAIM_PAGE')).toBeNull();
    expect(screen.queryByText('VIDEOS_PAGE')).toBeNull();
    expect(useAuthStore.getState().serverRoleStatus).toBe('pending');

    gate.release();

    // 조회 완료 — 그때서야 판정한다.
    expect(await screen.findByText('VIDEOS_PAGE')).toBeInTheDocument();
    expect(useAuthStore.getState().serverRoleStatus).toBe('ready');
  });

  // ── 수용기준 3 ────────────────────────────────────────────────────
  it('★확보에_실패하면_오류로_드러나며_role_claim_으로_가지_않는다', async () => {
    sessionStorage.setItem('klid_jwt', CONTROL_JWT);
    mock.onGet('/me').reply(500);

    bootAsRefresh();
    renderGuardedRoute();

    expect(await screen.findByText('사용자 정보를 확인할 수 없습니다')).toBeInTheDocument();
    expect(screen.queryByText('ROLE_CLAIM_PAGE')).toBeNull();
    // 「역할이 없다」로 말하지 않는다 — 사유가 다르면 사용자가 할 일도 다르다.
    expect(screen.queryByText(/역할이 아직 부여되지 않았습니다/)).toBeNull();
  });

  it('확보에_실패해도_토큰에_우리_역할이_있으면_유효_세션을_막지_않는다', async () => {
    // ② 폴백 갈래의 회귀 가드. dev·포털 토큰처럼 우리 역할값을 싣는 세션이 여기 해당한다.
    sessionStorage.setItem(
      'klid_jwt',
      buildJwt({ sub: '7', role: 'REVIEWER', channel: 'INTERNAL', exp: 9999999999 }),
    );
    mock.onGet('/me').reply(503);

    bootAsRefresh();
    renderGuardedRoute();

    expect(await screen.findByText('VIDEOS_PAGE')).toBeInTheDocument();
    expect(screen.queryByText('사용자 정보를 확인할 수 없습니다')).toBeNull();
  });

  // ── 수용기준 4 — 「보내는 쪽」의 양성 대조군 ──────────────────────
  it('★역할이_정말_없으면_종전대로_role_claim_으로_안내된다', async () => {
    // 이것이 없으면 「어떤 경우에도 role_claim 으로 안 간다」로 만들어도 통과하고,
    // 그러면 최초 관리자가 될 길이 사라진다.
    sessionStorage.setItem('klid_jwt', CONTROL_JWT);
    mock.onGet('/me').reply(200, meBody(null));

    bootAsRefresh();
    renderGuardedRoute();

    expect(await screen.findByText('ROLE_CLAIM_PAGE')).toBeInTheDocument();
    expect(useAuthStore.getState().claims?.role).toBeNull();
  });

  // ── 왕복 후 상태 ─────────────────────────────────────────────────
  it('★두_번째_새로고침에서도_같다_한_번_복원되면_끝이_아니다', async () => {
    sessionStorage.setItem('klid_jwt', CONTROL_JWT);
    mock.onGet('/me').reply(200, meBody('ADMIN'));

    bootAsRefresh();
    const first = renderGuardedRoute();
    expect(await screen.findByText('VIDEOS_PAGE')).toBeInTheDocument();
    first.unmount();

    // 새로고침 = 메모리(스토어·모듈 상태)는 통째로 사라지고 저장소만 남는다.
    useAuthStore.setState({ token: null, claims: null, isHydrated: false });
    resetServerRoleResolution();

    bootAsRefresh();
    renderGuardedRoute();

    expect(await screen.findByText('VIDEOS_PAGE')).toBeInTheDocument();
    expect(useAuthStore.getState().claims?.role).toBe('ADMIN');
  });

  it('같은_토큰에_대해_확보_조회가_두_번_나가지_않는다', async () => {
    sessionStorage.setItem('klid_jwt', CONTROL_JWT);
    mock.onGet('/me').reply(200, meBody('ADMIN'));

    bootAsRefresh();
    renderGuardedRoute();
    await screen.findByText('VIDEOS_PAGE');

    // 앱 부팅과 진입 화면이 같은 순간에 둘 다 부르는 경로가 실재한다 — 두 번째는 요청을
    // 다시 쏘지 않고 앞의 결과를 그대로 쓴다.
    //
    // ⚠ 두 번째 호출을 <b>기다린 뒤</b> 센다. `waitFor(() => 1건)` 로 쓰면 두 번째 요청이
    //   이력에 기록되기 <b>전에</b> 첫 폴링이 통과해, 중복 방지를 통째로 지우는 변이가
    //   살아남는다(실측 — 그 판은 전건 초록이었다). 「일어나지 않는다」를 <b>지연 없이</b>
    //   단언하면 그 축은 검사되지 않는다.
    const outcome = await ensureServerRole();

    expect(outcome).toEqual({ kind: 'resolved', role: 'ADMIN' });
    expect(mock.history.get.filter((r) => r.url === '/me')).toHaveLength(1);
  });

  // ── 이름 축 회귀 (2026-09-07 에 고친 결함) ────────────────────────
  it('서버가_이름을_모르면_토큰_이름을_지우지_않는다', async () => {
    sessionStorage.setItem('klid_jwt', CONTROL_JWT);
    mock.onGet('/me').reply(200, meBody('ADMIN')); // name 없음

    bootAsRefresh();
    renderGuardedRoute();
    await screen.findByText('VIDEOS_PAGE');

    expect(useAuthStore.getState().claims?.name).toBe('시스템관리자');
  });

  it('서버가_아는_이름이_있으면_그것으로_덮는다', async () => {
    sessionStorage.setItem('klid_jwt', CONTROL_JWT);
    mock.onGet('/me').reply(200, meBody('ADMIN', '찬기차장'));

    bootAsRefresh();
    renderGuardedRoute();
    await screen.findByText('VIDEOS_PAGE');

    expect(useAuthStore.getState().claims?.name).toBe('찬기차장');
  });

  // ── 세션이 없을 때 — 대기를 잘못 넓히지 않았는지 ──────────────────
  it('토큰이_없으면_스피너에_갇히지_않고_종전대로_진입_안내로_보낸다', async () => {
    bootAsRefresh();
    renderGuardedRoute();

    expect(await screen.findByText('INGRESS_PAGE')).toBeInTheDocument();
    expect(useAuthStore.getState().serverRoleStatus).toBe('idle');
    expect(mock.history.get.filter((r) => r.url === '/me')).toHaveLength(0);
  });
});

describe('포털 채널 — 같은 확보 절차를 쓴다', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    sessionStorage.clear();
    useAuthStore.setState({ token: null, claims: null, isHydrated: false });
    resetServerRoleResolution();
    mock = new MockAdapter(apiClient);
  });

  afterEach(() => {
    mock.restore();
    sessionStorage.clear();
    vi.unstubAllEnvs();
  });

  // ── 수용기준 5 ────────────────────────────────────────────────────
  it('★포털_채널도_서버_인가_역할을_조회해_주입한다_토큰_클레임만으로_동작하지_않는다', async () => {
    vi.stubEnv('VITE_BUILD_CHANNEL', 'portal');
    // 포털은 저장소를 쓰지 않는다 — Host 가 창구로 내준 토큰을 본체에 건넨다.
    useAuthStore.getState().setTokenAndClaims(PORTAL_JWT);
    mock.onGet('/me').reply(200, {
      success: true,
      data: { sub: 'p-1', role: 'PORTAL_USER', channel: 'PORTAL', name: '포털사용자' },
      message: null,
      errorCode: null,
    });

    bootAsRefresh();

    await waitFor(() => {
      expect(mock.history.get.filter((r) => r.url === '/me')).toHaveLength(1);
    });
    await waitFor(() => {
      expect(useAuthStore.getState().serverRoleStatus).toBe('ready');
    });
    expect(useAuthStore.getState().claims?.name).toBe('포털사용자');
  });

  // ── 수용기준 6 ────────────────────────────────────────────────────
  it('포털_채널은_토큰을_브라우저_저장소에_보관하지_않는다_회귀', () => {
    vi.stubEnv('VITE_BUILD_CHANNEL', 'portal');
    useAuthStore.getState().setTokenAndClaims(PORTAL_JWT);

    expect(sessionStorage.length).toBe(0);
  });

  it('포털_채널의_복원은_저장소를_읽지_않아_앞_채널이_남긴_토큰을_줍지_않는다', async () => {
    vi.stubEnv('VITE_BUILD_CHANNEL', 'portal');
    sessionStorage.setItem('klid_jwt', CONTROL_JWT);

    bootAsRefresh();

    expect(useAuthStore.getState().token).toBeNull();
    expect(useAuthStore.getState().serverRoleStatus).toBe('idle');
    expect(mock.history.get.filter((r) => r.url === '/me')).toHaveLength(0);
  });

  // ── 수용기준 7 — 「보내는 쪽」의 짝 ───────────────────────────────
  it('★포털_채널_세션은_역할이_비어도_role_claim_으로_튕기지_않는다', async () => {
    // 가드의 `channel === "INTERNAL"` 한정을 넓히면 이 케이스가 죽는다. 포털 Host 문서에는
    // 그 경로가 없어 이동하면 포털 404 가 뜬다.
    useAuthStore.setState({
      token: 'tok',
      claims: { sub: 'p-2', role: null, channel: 'PORTAL', exp: 9999999999 },
      isHydrated: true,
      serverRoleStatus: 'ready',
    });

    render(
      <MemoryRouter initialEntries={['/portal']}>
        <Routes>
          <Route
            path="/portal"
            element={
              <RoleGuard allow={[Role.PORTAL_USER]}>
                <div>PORTAL_PAGE</div>
              </RoleGuard>
            }
          />
          <Route path="/role-claim" element={<div>ROLE_CLAIM_PAGE</div>} />
          <Route path="/forbidden" element={<div>FORBIDDEN_PAGE</div>} />
        </Routes>
      </MemoryRouter>,
    );

    expect(screen.getByText('FORBIDDEN_PAGE')).toBeInTheDocument();
    expect(screen.queryByText('ROLE_CLAIM_PAGE')).toBeNull();
  });
});

// ─────────────────────────────────────────────────────────────────────
// ★축을 도는 시험은 <목록 자체>를 지키는 단언이 따로 필요하다
//
//   이번 변경의 핵심 축이 「채널을 가리지 않고 서버 역할을 확보한다」다. 그 축을 채널 목록으로
//   도는 시험(`it.each`)으로 고정하면, <b>목록에서 한 채널을 빼는 변이가 실패를 내지 않는다</b> —
//   그 케이스가 틀리는 게 아니라 <b>아예 실행되지 않기</b> 때문이다. 시험은 초록인데 그 축이
//   통째로 검사 밖으로 나간다. 이것은 「해제만 단언」의 변종이지만 발견 방식이 다르다 — 단언을
//   읽어서는 안 보이고 <b>목록을 줄여 봐야</b> 「아무도 안 죽네」가 드러난다.
//
//   그래서 이 파일은 채널을 <b>돌지 않고</b> 케이스를 각각 세웠고(위 두 describe), 여기에
//   <b>덮개 목록 자체</b>를 못박는다. `Record<Channel, ...>` 이라 한 채널을 빼면 컴파일이
//   깨지고, 런타임 단언이 채널이 늘었을 때를 잡는다.
// ─────────────────────────────────────────────────────────────────────
describe('확보 절차가 마주칠 수 있는 채널을 이 파일이 전부 덮는다', () => {
  /** 채널 → 그 채널의 확보를 실제로 관측하는 케이스 이름. */
  const CHANNEL_COVERAGE: Record<Channel, string> = {
    INTERNAL: '★새로고침하면_보던_화면이_그대로_다시_뜬다_권한요청_안내로_튕기지_않는다',
    PORTAL: '★포털_채널도_서버_인가_역할을_조회해_주입한다_토큰_클레임만으로_동작하지_않는다',
  };

  it('★덮개_목록이_실제_채널_집합과_정확히_같다', () => {
    expect(Object.keys(CHANNEL_COVERAGE).sort()).toEqual(Object.values(Channel).sort());
    // 이름이 빈 칸이면 「적어 두기만 하고 케이스가 없는」 상태다.
    for (const caseName of Object.values(CHANNEL_COVERAGE)) {
      expect(caseName.length).toBeGreaterThan(0);
    }
  });
});
