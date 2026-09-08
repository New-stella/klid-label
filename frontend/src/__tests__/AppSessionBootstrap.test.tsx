import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import MockAdapter from 'axios-mock-adapter';

// `vi.mock` 은 vitest 가 끌어올리므로 이 import 가 위에 있어도 대역이 먼저 선다.
import { App } from '@/App';
import { resetServerRoleResolution } from '@/features/auth/sessionBootstrap';
import { apiClient } from '@/lib/api/client';
import { useAuthStore } from '@/stores/useAuthStore';

/**
 * 앱 부팅 배선 가드 — <b>화면을 다시 불러올 때 지나는 유일한 자리</b>가 세션 복원을 부르는지.
 *
 * 복원 절차 자체는 `features/auth/__tests__/sessionRefreshRoleRestore.test.tsx` 가 덮는다.
 * 여기서 지키는 것은 <b>그 절차가 앱에 실제로 물려 있는가</b> 하나다 — 절차만 검증하면
 * 「App 이 그것을 부르지 않게」 바꾸는 변이가 살아남는다.
 *
 * 라우터는 대역으로 갈아 끼운다. 프로덕션 라우트 표는 이 가드의 관심 축이 아니고, 그것을
 * 그대로 태우면 지연 로드 화면들이 끌려 들어와 무엇이 실패한 것인지 흐려진다.
 *
 * [@design ADR-063] [@design SEQ-034] [@design UC-041]
 */
vi.mock('@/router', async () => {
  const { createMemoryRouter } = await vi.importActual<typeof import('react-router-dom')>(
    'react-router-dom',
  );
  const { RoleGuard } = await vi.importActual<typeof import('@/router/guards')>('@/router/guards');
  // 역할 판정을 하는 자리를 <b>실제 가드로</b> 세운다 — 대역 화면만 두면 「깜빡임이 없다」를
  // 관측할 대상 자체가 없어진다.
  return {
    router: createMemoryRouter([
      {
        path: '/',
        element: (
          <RoleGuard allow={['ADMIN', 'REVIEWER', 'WORKER']}>
            <div>ROUTED</div>
          </RoleGuard>
        ),
      },
      { path: '/role-claim', element: <div>ROLE_CLAIM_PAGE</div> },
    ]),
  };
});

function b64url(obj: Record<string, unknown>): string {
  const utf8 = unescape(encodeURIComponent(JSON.stringify(obj)));
  return btoa(utf8).replace(/=+$/, '').replace(/\+/g, '-').replace(/\//g, '_');
}
const CONTROL_JWT = `${b64url({ alg: 'HS256', typ: 'JWT' })}.${b64url({
  sub: '9001',
  role: 'LEARN_MANAGER', // 관제 자신의 역할값 — 우리 집합에 없어 디코드 결과는 role=null
  channel: 'INTERNAL',
  exp: 9999999999,
})}.signature`;

describe('App — 부팅 시 세션 복원 배선', () => {
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
  });

  it('★부팅이_토큰을_복원하고_서버_인가_역할까지_확보한다', async () => {
    sessionStorage.setItem('klid_jwt', CONTROL_JWT);
    mock.onGet('/me').reply(200, {
      success: true,
      data: { sub: '9001', role: 'ADMIN', channel: 'INTERNAL', name: '시스템관리자' },
      message: null,
      errorCode: null,
    });

    render(<App />);

    // 토큰 복원 — 이것만 하고 멈추던 것이 새로고침마다 역할을 잃던 결함이다.
    await waitFor(() => {
      expect(useAuthStore.getState().token).toBe(CONTROL_JWT);
    });
    // 서버 역할 확보까지 이어져야 가드가 서버 진실원을 본다.
    await waitFor(() => {
      expect(useAuthStore.getState().claims?.role).toBe('ADMIN');
    });
    expect(mock.history.get.filter((r) => r.url === '/me')).toHaveLength(1);
    expect(await screen.findByText('ROUTED')).toBeInTheDocument();
  });

  it('★부팅_직후_권한요청_안내가_한_번도_스치지_않는다', async () => {
    // 사용자가 실제로 본 증상이 <스쳤다 돌아오는> 깜빡임이다. 복원과 확보 시작이 같은 렌더
    // 배치에 묶이지 않으면 라우터가 「복원 완료 + 역할 없음」 상태로 한 프레임 렌더돼
    // 그 안내가 뜬다 — 그 한 프레임을 여기서 잡는다.
    sessionStorage.setItem('klid_jwt', CONTROL_JWT);
    mock.onGet('/me').reply(200, {
      success: true,
      data: { sub: '9001', role: 'ADMIN', channel: 'INTERNAL' },
      message: null,
      errorCode: null,
    });

    render(<App />);

    // 부팅 효과가 돈 직후 — 아직 응답 전이다.
    expect(screen.queryByText('ROLE_CLAIM_PAGE')).toBeNull();
    expect(useAuthStore.getState().isHydrated).toBe(true);
    expect(useAuthStore.getState().serverRoleStatus).toBe('pending');

    expect(await screen.findByText('ROUTED')).toBeInTheDocument();
    expect(screen.queryByText('ROLE_CLAIM_PAGE')).toBeNull();
  });
});
