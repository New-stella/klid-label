import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import MockAdapter from 'axios-mock-adapter';

import { apiClient } from '@/lib/api/client';
import { Role } from '@/lib/api/types';
import { RoleGuard } from '@/router/guards';
import { useAuthStore } from '@/stores/useAuthStore';

import { ensureServerRole, resetServerRoleResolution, restoreSession } from '../sessionBootstrap';

/**
 * ★<b>세션 교체</b>를 건너는 축의 회귀 가드 — 새로고침(문서 재적재)이 아니다.
 *
 * <h3>이 파일이 재현하는 것</h3>
 * 앞선 라운드는 「새로고침하면 권한 요청 안내로 튕긴다」를 고쳤고, 조회를 한 번만 하도록
 * <b>모듈 스코프 캐시</b>를 두었다. 그 캐시는 <b>토큰 문자열로만</b> 키를 잡는데, 세션이
 * 갈리는 경로(<code>clear()</code> → 다시 <code>setToken()</code>)는 <b>같은 화면 수명 안</b>
 * 에서 일어나 모듈이 새로 뜨지 않는다. 그래서 캐시가 히트하면서 <b>스토어에는 아무것도
 * 재적용되지 않아</b> 고쳤다는 바로 그 증상이 다시 나타났다.
 *
 * ⚠ <b>여기서는 캐시를 비우지 않는다.</b> 기존 「두 번째 새로고침」 가드는 케이스 안에서
 *   <code>resetServerRoleResolution()</code> 을 불러 <b>캐시가 살아남는 축을 지나지 않는다</b> —
 *   그래서 이 결함이 전건 초록을 통과했다. 캐시를 비우는 순간 이 파일은 아무것도 지키지 않는다.
 *
 * <h3>덮는 축 넷</h3>
 * ① 같은 토큰으로 세션을 다시 세워도 역할 보유자가 화면에 도달한다(캐시 무효화)
 * ② 캐시가 살아 있어도 결과가 <b>지금 상태에 다시 앉는다</b>(멱등 재적용)
 * ③ 늦게 도착한 <b>옛 세션</b>의 응답이 새 상태를 바꾸지 않는다(성공·실패 양쪽)
 * ④ 확인 실패에서 <b>사람이</b> 다시 시도할 수 있다(자동 반복이 아니다)
 *
 * [@design ADR-063] [@design UC-041] [@design SEQ-034] [@design SCREEN-001]
 * [@design AC-1016] [@design AC-1017] [@design AC-1098] [@design API-006]
 */

function b64url(obj: Record<string, unknown>): string {
  const utf8 = unescape(encodeURIComponent(JSON.stringify(obj)));
  return btoa(utf8).replace(/=+$/, '').replace(/\+/g, '-').replace(/\//g, '_');
}
function buildJwt(payload: Record<string, unknown>): string {
  return `${b64url({ alg: 'HS256', typ: 'JWT' })}.${b64url(payload)}.signature`;
}

/**
 * 관제 인계 토큰 — 우리 역할 집합에 없는 관제 역할값을 싣는다(디코드 결과 role=null).
 *
 * ★이 성질이 이 파일의 전제다. 우리 역할이 실린 토큰을 쓰면 재수립 뒤에도 토큰만으로
 *   판정이 되어 <b>결함을 지나가지 않는다</b>.
 */
const CONTROL_JWT = buildJwt({
  sub: '9001',
  role: 'LEARN_MANAGER',
  channel: 'INTERNAL',
  name: '시스템관리자',
  exp: 9999999999,
});

/** 두 번째 세션 — 위와 <b>다른 토큰</b>이어야 「세션이 갈렸다」가 성립한다. */
const CONTROL_JWT_B = buildJwt({
  sub: '9002',
  role: 'LEARN_MANAGER',
  channel: 'INTERNAL',
  name: '시스템관리자',
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

function renderGuardedRoute() {
  return render(
    <MemoryRouter initialEntries={['/videos']}>
      <Routes>
        <Route
          path="/videos"
          element={
            <RoleGuard allow={[Role.ADMIN, Role.REVIEWER, Role.WORKER]}>
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

const meCalls = (mock: MockAdapter) => mock.history.get.filter((r) => r.url === '/me');

/** 「일어나지 않는다」를 <b>지연 없이</b> 단언하면 그 축은 검사되지 않는다. */
const settle = () => new Promise((resolve) => setTimeout(resolve, 50));

describe('세션 교체 — 확보 결과의 재사용은 세션에 종속된다', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    sessionStorage.clear();
    localStorage.clear();
    useAuthStore.setState({ token: null, claims: null, isHydrated: false });
    resetServerRoleResolution();
    mock = new MockAdapter(apiClient);
  });

  afterEach(() => {
    mock.restore();
    sessionStorage.clear();
    localStorage.clear();
    vi.unstubAllEnvs();
  });

  // ── 수용기준 ① ────────────────────────────────────────────────────
  it('★세션이_끊겼다_같은_토큰으로_다시_서면_역할_보유자가_화면에_도달한다', async () => {
    sessionStorage.setItem('klid_jwt', CONTROL_JWT);
    mock.onGet('/me').reply(200, meBody('ADMIN', '시스템관리자'));

    // 1) 진입 → 확보 완료
    restoreSession();
    const first = renderGuardedRoute();
    expect(await screen.findByText('VIDEOS_PAGE')).toBeInTheDocument();
    first.unmount();

    // 2) 아무 API 가 401 → 공통 인터셉터가 하는 일이 정확히 이것이다.
    //    ★모듈 스코프 캐시는 비우지 않는다 — 문서가 다시 뜨지 않았기 때문이다.
    act(() => {
      useAuthStore.getState().clear();
    });
    // 3) 진입 화면이 같은 토큰으로 세션을 다시 세운다(관제 인계 키는 남아 있다).
    act(() => {
      useAuthStore.getState().setToken(CONTROL_JWT);
    });

    renderGuardedRoute();

    expect(await screen.findByText('VIDEOS_PAGE')).toBeInTheDocument();
    expect(screen.queryByText('ROLE_CLAIM_PAGE')).toBeNull();
    expect(useAuthStore.getState().claims?.role).toBe('ADMIN');
    // 세션이 갈렸으므로 다시 물었다 — 세션 하나당 한 번.
    expect(meCalls(mock)).toHaveLength(2);
  });

  it('세션이_그대로면_다시_묻지_않는다_라우트_전환마다가_아니다', async () => {
    // ①의 짝 — 무효화를 「항상 무효화」로 넓히면 이 케이스가 죽는다.
    //
    // ⚠ <b>다시 렌더하는 것만으로는 이 축이 검사되지 않는다.</b> 가드는 확보를 부르지 않고
    //   결과만 읽으므로, 렌더를 아무리 반복해도 요청 수는 그대로다 — 그 판은 무효화를 통째로
    //   넓히는 변이에서도 초록이었다(실측). 그래서 <b>확보를 실제로 다시 부른다</b>.
    sessionStorage.setItem('klid_jwt', CONTROL_JWT);
    mock.onGet('/me').reply(200, meBody('ADMIN'));

    restoreSession();
    const first = renderGuardedRoute();
    await screen.findByText('VIDEOS_PAGE');
    first.unmount();

    renderGuardedRoute();
    await screen.findByText('VIDEOS_PAGE');
    const outcome = await ensureServerRole();
    await settle();

    expect(outcome).toEqual({ kind: 'resolved', role: 'ADMIN' });
    expect(meCalls(mock)).toHaveLength(1);
  });

  it('확보_대상이_아닌_세션은_붙잡지_않는다_idle_은_통과다', async () => {
    // ★<b>미확보 대기의 짝</b>. `idle` 까지 대기에 끌어들이면 <b>스토어에 상태를 직접 심는
    //   화면 시험 전부</b>가 영구 스피너에 걸린다 — 초기값을 `pending` 으로 올리는 것과 같은
    //   사고이고, 그 함정이 `useAuthStore` javadoc 에 이미 적혀 있다.
    //
    // ⚠ 「토큰이 없으면 진입 안내로 보낸다」는 이 축의 짝이 <b>되지 못한다</b> — claims 가 없어
    //   확보 상태 분기에 <b>닿기 전에</b> 되돌아 나가기 때문이다. 실제로 대기를 `idle` 까지
    //   넓히는 변이가 그 케이스를 통과했다(실측). 그래서 claims 가 <b>있는</b> 상태로 세운다.
    useAuthStore.setState({
      token: CONTROL_JWT,
      claims: { sub: '9001', role: 'ADMIN', channel: 'INTERNAL', exp: 9999999999 },
      isHydrated: true,
      serverRoleStatus: 'idle',
    });

    renderGuardedRoute();

    expect(screen.getByText('VIDEOS_PAGE')).toBeInTheDocument();
    expect(screen.queryByText('인증 확인 중')).toBeNull();
  });

  // ── 수용기준 ② ────────────────────────────────────────────────────
  it('★캐시가_살아_있어도_확보_결과가_지금_상태에_다시_앉는다', async () => {
    // 세션은 갈리지 않았고(같은 토큰) 토큰만 다시 세워진 경우. `setToken` 은 claims 를
    // <b>토큰 디코드값으로 재구성</b>하므로 주입해 둔 서버 역할이 지워진다. 캐시는 히트하는데
    // 그 결과가 스토어에 닿지 않으면 역할 보유자가 <역할 없음>으로 판정된다.
    sessionStorage.setItem('klid_jwt', CONTROL_JWT);
    mock.onGet('/me').reply(200, meBody('ADMIN'));

    restoreSession();
    const first = renderGuardedRoute();
    await screen.findByText('VIDEOS_PAGE');
    first.unmount();

    act(() => {
      useAuthStore.getState().setToken(CONTROL_JWT);
    });
    // 전제 자기검사 — 재수립 직후에는 토큰 디코드값(우리 역할 아님 → null)이다.
    // 이 줄이 없으면 「원래 역할이 남아 있어서」 통과한 것과 구분되지 않는다.
    expect(useAuthStore.getState().claims?.role).toBeNull();

    renderGuardedRoute();

    expect(await screen.findByText('VIDEOS_PAGE')).toBeInTheDocument();
    expect(useAuthStore.getState().claims?.role).toBe('ADMIN');
    // 세션이 갈리지 않았으므로 <다시 묻지 않고> 앞의 결과를 다시 앉힌다.
    expect(meCalls(mock)).toHaveLength(1);
  });

  // ── 수용기준 ② — 미확보는 판정에 도달하지 않는다 ──────────────────
  it('★세션은_있는데_아직_확보하지_않았으면_역할_판정에_도달하지_않는다', async () => {
    // 「세션 없음」과 「세션 있는데 미확보」는 판정이 정반대다. 뭉쳐 두면 후자가 조용히
    // 통과해, 서버 역할을 한 번도 확인하지 않은 채 화면이 열린다(fail-closed 가 아니다).
    useAuthStore.setState({
      token: CONTROL_JWT,
      claims: { sub: '9001', role: null, channel: 'INTERNAL', exp: 9999999999 },
      isHydrated: true,
      serverRoleStatus: 'unacquired',
    });

    renderGuardedRoute();

    expect(screen.getByText('인증 확인 중')).toBeInTheDocument();
    expect(screen.queryByText('ROLE_CLAIM_PAGE')).toBeNull();
    expect(screen.queryByText('VIDEOS_PAGE')).toBeNull();
    expect(meCalls(mock)).toHaveLength(0);
  });

  it('★토큰을_세우는_것만으로_미확보_표식이_선다_확보_전에는_스치지_않는다', async () => {
    // 위 케이스가 지키는 상태를 <실제로 만드는> 자리가 여기다. 스토어에 직접 심는 케이스만
    // 두면 「그 상태가 실제로는 만들어지지 않는」 변이를 놓친다.
    mock.onGet('/me').reply(200, meBody('ADMIN'));
    // 복원은 이미 끝난 상태로 둔다 — 그래야 아래 스피너가 <미확보> 때문임이 확정된다.
    useAuthStore.setState({ isHydrated: true });

    act(() => {
      useAuthStore.getState().setToken(CONTROL_JWT);
    });
    renderGuardedRoute();

    // 확보 절차가 아직 결과를 내지 않았다 — 판정을 미룬다.
    expect(useAuthStore.getState().serverRoleStatus).toBe('unacquired');
    expect(screen.queryByText('ROLE_CLAIM_PAGE')).toBeNull();
    expect(meCalls(mock)).toHaveLength(0);

    // 그리고 스스로 확보로 넘어간다 — <미확보에 갇히지 않는다>. 권한 자가 부여 직후처럼
    // 토큰만 갈아끼우는 경로가 확보를 부르지 않아도 세션 수립이 그것을 끌어온다.
    expect(await screen.findByText('VIDEOS_PAGE')).toBeInTheDocument();
    expect(useAuthStore.getState().claims?.role).toBe('ADMIN');
    expect(meCalls(mock)).toHaveLength(1);
  });

  it('세션이_없으면_기다리지_않고_종전대로_진입_안내로_보낸다', async () => {
    // ★양성 대조 — 「세션 없음」까지 붙잡으면 로그인하지 않은 사용자가 영구 스피너를 본다.
    restoreSession();
    renderGuardedRoute();

    expect(await screen.findByText('INGRESS_PAGE')).toBeInTheDocument();
    expect(useAuthStore.getState().serverRoleStatus).toBe('idle');
    expect(meCalls(mock)).toHaveLength(0);
  });

  // ── 수용기준 ③ — 응답의 세션 귀속 ─────────────────────────────────
  it('★세션이_갈린_뒤_도착한_옛_조회의_성공은_새_상태에_앉지_않는다', async () => {
    sessionStorage.setItem('klid_jwt', CONTROL_JWT);
    const gate = { release: () => {} };
    const gated = new Promise<[number, unknown]>((resolve) => {
      gate.release = () => resolve([200, meBody('ADMIN', '시스템관리자')]);
    });
    mock.onGet('/me').reply(() => gated);

    restoreSession();
    renderGuardedRoute();
    expect(useAuthStore.getState().serverRoleStatus).toBe('pending');

    // 조회가 도는 사이에 세션이 끊긴다.
    act(() => {
      useAuthStore.getState().clear();
    });
    gate.release();
    await settle();

    // 세션이 없는 상태에 확보 결과가 남지 않는다.
    expect(useAuthStore.getState().claims).toBeNull();
    expect(useAuthStore.getState().serverRoleStatus).toBe('idle');
  });

  it('★세션이_갈린_뒤_도착한_옛_조회의_실패도_새_상태에_앉지_않는다', async () => {
    // ⚠ 실패만 빠뜨리면 `clear()` 직후 도착한 응답이 세션 없는 상태에 「확인 실패」를 남겨,
    //   다음 진입이 근거 없이 오류 안내로 떨어진다. 성공·실패는 <같은 축의 두 갈래>다.
    sessionStorage.setItem('klid_jwt', CONTROL_JWT);
    const gate = { release: () => {} };
    const gated = new Promise<[number, unknown]>((resolve) => {
      gate.release = () => resolve([500, {}]);
    });
    mock.onGet('/me').reply(() => gated);

    restoreSession();
    renderGuardedRoute();
    expect(useAuthStore.getState().serverRoleStatus).toBe('pending');

    act(() => {
      useAuthStore.getState().clear();
    });
    gate.release();
    await settle();

    expect(useAuthStore.getState().serverRoleStatus).toBe('idle');
    expect(useAuthStore.getState().serverRoleStatus).not.toBe('failed');
  });

  it('★옛_세션의_늦은_응답이_도착해도_지금_세션의_확보_결과가_남는다_캐시_축', async () => {
    // ★<b>위 두 케이스는 스토어 축만 본다</b> — 늦은 응답이 <스토어에> 쓰지 않는 것까지만
    //   확인하고 <b>확보 캐시</b>에 무엇이 남는지는 보지 않는다. 그래서 다음 결함이 통과했다:
    //   결과 슬롯이 시도와 <b>따로 놀면</b> 옛 세션의 늦은 응답이 그 슬롯을 덮어, 그 뒤 지금
    //   세션으로 확보를 다시 부를 때 <b>캐시는 히트하는데 결과는 남의 것</b>이라 확보 상태가
    //   `'pending'` 으로 <b>영구 고착</b>한다(재시도 조작은 `'failed'` 에만 있어 탈출구도 없다).
    //
    // ⚠ 이 축은 <b>확보를 다시 부를 때만</b> 드러난다. 렌더만 반복하면 가드는 결과를 읽을 뿐이라
    //   상태가 그대로다. 재진입 `/ingress` 의 `setToken(같은 토큰)` 이 부르는 자리가 여기다.
    sessionStorage.setItem('klid_jwt', CONTROL_JWT);
    const gate = { release: () => {} };
    const gatedOld = new Promise<[number, unknown]>((resolve) => {
      // 옛 세션의 답 — 역할까지 다르게 둬야 「옛 값이 새 세션에 앉았는가」도 함께 갈린다.
      gate.release = () => resolve([200, meBody('WORKER')]);
    });
    mock
      .onGet('/me')
      .replyOnce(() => gatedOld)
      .onGet('/me')
      .reply(200, meBody('ADMIN', '시스템관리자'));

    // 1) 세션 A — 조회가 붙잡혀 있다.
    restoreSession();
    expect(useAuthStore.getState().serverRoleStatus).toBe('pending');

    // 2) 세션 B 로 갈린다. 캐시는 토큰이 바뀌었으므로 버려지고 확보가 다시 돈다.
    act(() => {
      useAuthStore.getState().setTokenAndClaims(CONTROL_JWT_B);
    });
    await settle();
    expect(useAuthStore.getState().serverRoleStatus).toBe('ready');
    expect(useAuthStore.getState().claims?.role).toBe('ADMIN');

    // 3) ★옛 세션의 응답이 <이제야> 도착한다.
    gate.release();
    await settle();

    // 4) 지금 세션으로 확보를 다시 부른다 — 옛 응답이 끼어들 자리가 없어야 한다.
    await ensureServerRole();
    await settle();

    expect(useAuthStore.getState().serverRoleStatus).toBe('ready');
    expect(useAuthStore.getState().claims?.role).toBe('ADMIN');
    // 세션 둘, 조회 둘 — 늦은 응답이 재조회를 유발하지도 않는다.
    expect(meCalls(mock)).toHaveLength(2);
  });

  // ── 수용기준 ④ — 사람이 다시 시도할 수 있다 ───────────────────────
  it('★확인_실패_화면에서_다시_시도하면_확보를_다시_수행한다', async () => {
    sessionStorage.setItem('klid_jwt', CONTROL_JWT);
    mock.onGet('/me').replyOnce(500).onGet('/me').reply(200, meBody('ADMIN'));

    restoreSession();
    renderGuardedRoute();

    expect(await screen.findByText('사용자 정보를 확인할 수 없습니다')).toBeInTheDocument();
    expect(meCalls(mock)).toHaveLength(1);

    await userEvent.click(screen.getByRole('button', { name: '다시 시도' }));

    expect(await screen.findByText('VIDEOS_PAGE')).toBeInTheDocument();
    expect(useAuthStore.getState().claims?.role).toBe('ADMIN');
    expect(meCalls(mock)).toHaveLength(2);
  });

  it('★확인_실패는_자동으로_다시_묻지_않는다_사람이_누를_때만이다', async () => {
    // ④의 짝 — 재시도를 자동 반복으로 만들면 장애 구간에 조회 폭주가 된다.
    sessionStorage.setItem('klid_jwt', CONTROL_JWT);
    mock.onGet('/me').reply(500);

    restoreSession();
    renderGuardedRoute();

    await screen.findByText('사용자 정보를 확인할 수 없습니다');
    await settle();

    expect(meCalls(mock)).toHaveLength(1);
  });
});
