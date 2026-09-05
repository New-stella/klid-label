// 회귀 가드 — 관리자 페이지 라우트는 「관리자 역할 + 관리자 유효창」 두 겹을 함께 요구한다.
// [@design SCREEN-040] [@design NAV-001] [@design ADR-046] [@design ROLE-004]
//
// ★가르는 축이 **역할**로 바뀌었다. 예전에는 검수자면 라우트를 통과하고 유효창에서 걸렸는데,
//   관리자 역할이 생기면서 역할 단계에서 갈린다. 검수자는 이제 접근 거부다 — 그 케이스가
//   아래 「검수자는 …」 이며, 유효창을 열어 줘도 통과하지 못한다(유효창은 역할을 올리지 않는다).
//
// ★이 가드는 **프로덕션 라우트 테이블**(`@/router` 의 router.routes)과 **실제 LNB 렌더**를 본다.
//   테스트 안에서 `<RoleGuard allow={...}><AdminSessionGuard>` 트리를 새로 조립해 단언하면
//   프로덕션 코드를 한 줄도 실행하지 않는 동어반복이 되어, 그쪽 배선을 통째로 지워도 초록으로
//   남는다(이 저장소가 실제로 겪은 형태다).
//
// ★두 축을 **함께** 고정하는 것이 핵심이다 — 메뉴만 옮기고 라우트를 놓치면 「메뉴에는 없는데
//   주소로는 들어가진다」가 되고, 반대면 「메뉴는 보이는데 누르면 거부된다」가 된다.
//
// ★가드 **순서**도 못 박는다. 유효창은 인가를 대체하지 않고 가산되므로 역할 가드가 바깥이어야
//   한다. 뒤집히면 검수자가 아닌 사용자가 「접근 거부」 대신 **패스워드 입력 화면**을 보게 되고,
//   그건 「패스워드를 아는 사람이 관리자」라는 뜻이 된다.
//
// ⚠ 허용 케이스는 마운트하지 않는다 — 통과하면 지연 로딩된 실제 화면이 import 되어 이 가드의
//   관심사 밖(데이터 조회 등)에서 실패할 수 있다. 거부 케이스는 자식이 아예 렌더되지 않아 안전하다.
//   유일한 예외는 진입 화면(`/admin`)이며, 그건 「막히지 않는다」가 이 가드의 단언이라 필요하다.

import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { Suspense } from 'react';
import { cleanup, render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import type { RouteObject } from 'react-router-dom';

import { Lnb } from '@/components/layout/Lnb';
import { useAdminSessionStore } from '@/features/adminSession/store';
import { renderWithProviders } from '@/test/renderWithProviders';
import { router } from '@/router';
import { useAuthStore } from '@/stores/useAuthStore';

/** 라우트 트리를 훑어 전체 경로 → RouteObject 로 색인한다(실제 트리를 그대로 읽는다). */
function indexRoutes(
  routes: RouteObject[],
  prefix = '',
  acc = new Map<string, RouteObject>(),
): Map<string, RouteObject> {
  for (const route of routes) {
    const segment = route.path ?? '';
    const joined = segment.startsWith('/')
      ? segment
      : `${prefix}${segment ? `/${segment}` : ''}`;
    const full = (joined || '/').replace(/\/{2,}/g, '/');
    if (route.path) acc.set(full, route);
    if (route.children) indexRoutes(route.children, full, acc);
  }
  return acc;
}

const ROUTES = indexRoutes(router.routes as RouteObject[]);

function routeAt(fullPath: string): RouteObject {
  const found = ROUTES.get(fullPath);
  // 경로가 사라졌거나 개명됐다면 이 가드가 아무것도 지키지 못하므로 그 사실을 먼저 실패로 알린다.
  expect(found, `라우트 ${fullPath} 를 찾지 못했다`).toBeTruthy();
  return found!;
}

/**
 * 관리자 페이지의 기능 화면 — 진입 게이트(`/admin`)는 별개 축이라 여기 넣지 않는다.
 *
 * 순서는 NAV-001 이 정한 메뉴 순서와 같다(파일 업로드는 끝이 아니라 패스워드 교체 **앞**).
 * vitest 는 DEV 빌드라 isDevUploadEnabled() 가 true → 파일 업로드 라우트도 등록된다.
 */
const ADMIN_FEATURE_PATHS = [
  '/admin/users',
  '/admin/endpoints',
  // 산출물 가져오기 — 구 주소 `/manage/imports` 에서 옮겨왔다. 서버가 적재 실행·대응 저장/삭제를
  // 관리자 전용으로 좁혔는데 화면만 검수자에게 열려 있어 마지막 단계에서만 403 이 나던 자리다.
  '/admin/imports',
  '/admin/uploads',
  '/admin/password',
  '/admin/maintenance',
] as const;

/**
 * 진입 게이트의 element — `/admin` 은 **index 자식**이 화면을 그린다.
 *
 * ⚠ `/admin` 노드 자체에는 element 가 없다(children 만 있는 부모). 그걸 마운트하면 아무것도
 * 렌더되지 않아 「가드가 없다」와 「화면이 없다」가 구분되지 않는다.
 */
function adminGateElement() {
  const parent = routeAt('/admin');
  const index = parent.children?.find((c) => c.index === true);
  expect(index?.element, '`/admin` 의 index 라우트에 element 가 없다').toBeTruthy();
  return index!.element;
}

function setRole(role: 'ADMIN' | 'REVIEWER' | 'WORKER') {
  useAuthStore.setState({
    // 값은 쓰이지 않는다 — 가드가 보는 것은 claims 뿐이다.
    token: 'dummy-token',
    claims: { sub: 'u', role, channel: 'INTERNAL', exp: 9999999999 },
  });
}

function openWindow() {
  useAdminSessionStore.getState().open({
    token: 'dummy-window',
    expiresAt: new Date(Date.now() + 10 * 60 * 1000).toISOString(),
  });
}

/** 실제 라우트 element 를 그대로 마운트한다 — 가드 판정이 프로덕션 배선 그대로 돈다. */
function renderRealRoute(fullPath: string) {
  return render(
    <MemoryRouter initialEntries={[fullPath]}>
      <Suspense fallback={<div>LOADING</div>}>
        <Routes>
          <Route path={fullPath} element={routeAt(fullPath).element} />
          <Route path="/admin" element={<div>ADMIN_GATE_REDIRECT</div>} />
          <Route path="/admin/imports" element={<div>ADMIN_IMPORTS_TARGET</div>} />
          <Route path="/forbidden" element={<div>FORBIDDEN_PAGE</div>} />
          <Route path="/ingress" element={<div>INGRESS_PAGE</div>} />
          <Route path="/role-claim" element={<div>ROLE_CLAIM_PAGE</div>} />
        </Routes>
      </Suspense>
    </MemoryRouter>,
  );
}

describe('관리자 라우트 — 역할 + 유효창 두 겹', () => {
  beforeEach(() => {
    useAuthStore.getState().clear();
    useAdminSessionStore.getState().clear();
  });

  afterEach(() => {
    // ⚠ 순서가 중요하다 — 먼저 언마운트하지 않고 store 를 비우면 아직 마운트된 컴포넌트가 그
    //   변경에 반응해 act(...) 경고가 난다.
    cleanup();
    useAuthStore.getState().clear();
    useAdminSessionStore.getState().clear();
  });

  it.each(ADMIN_FEATURE_PATHS)(
    '%s — 관리자여도 유효창이 없으면 진입 게이트로 보낸다',
    (path) => {
      // 역할을 통과해도 유효창은 그대로 요구된다 — 두 겹이 남아 있음을 고정한다.
      setRole('ADMIN');
      renderRealRoute(path);
      expect(screen.getByText('ADMIN_GATE_REDIRECT')).toBeInTheDocument();
    },
  );

  it.each(ADMIN_FEATURE_PATHS)('%s — 검수자는 유효창이 있어도 거부된다', (path) => {
    // ★계층은 관리자 → 검수자 **한 방향**이다. 검수자가 관리자 자리에 올라오지 않는다.
    //   유효창은 역할을 승격시키지 않으므로 패스워드를 알아도 통과하지 못한다.
    setRole('REVIEWER');
    openWindow();
    renderRealRoute(path);
    expect(screen.getByText('FORBIDDEN_PAGE')).toBeInTheDocument();
  });

  it.each(ADMIN_FEATURE_PATHS)('%s — 작업자는 유효창이 있어도 거부된다', (path) => {
    // 유효창은 역할을 승격시키지 않는다 — 패스워드를 알아도 관리자가 아니면 못 들어간다.
    setRole('WORKER');
    openWindow();
    renderRealRoute(path);
    expect(screen.getByText('FORBIDDEN_PAGE')).toBeInTheDocument();
  });

  it.each(ADMIN_FEATURE_PATHS)(
    '%s — 작업자에게는 유효창이 없어도 «접근 거부»가 뜬다 (가드 순서)',
    (path) => {
      // ★이 케이스가 **순서**를 고정한다. 역할 가드가 바깥이면 접근 거부,
      //   유효창 가드가 바깥이면 패스워드 입력 화면으로 간다 — 후자는 관리자가 아닌 사용자에게
      //   「패스워드만 맞히면 된다」는 신호를 주는 것이라 인가 결함이다.
      setRole('WORKER');
      renderRealRoute(path);
      expect(screen.getByText('FORBIDDEN_PAGE')).toBeInTheDocument();
      expect(screen.queryByText('ADMIN_GATE_REDIRECT')).toBeNull();
    },
  );

  it('진입_게이트_자신은_유효창을_요구하지_않는다', async () => {
    // 걸면 유효창이 없을 때 자기 자신으로 무한히 되돌아가 아무도 관리자 페이지에 못 들어간다.
    setRole('ADMIN');
    render(
      <MemoryRouter initialEntries={['/admin']}>
        <Suspense fallback={<div>LOADING</div>}>
          <Routes>
            <Route path="/admin" element={adminGateElement()} />
            <Route path="/forbidden" element={<div>FORBIDDEN_PAGE</div>} />
            <Route path="/ingress" element={<div>INGRESS_PAGE</div>} />
            <Route path="/role-claim" element={<div>ROLE_CLAIM_PAGE</div>} />
          </Routes>
        </Suspense>
      </MemoryRouter>,
    );

    // 지연 로딩이라 첫 렌더는 fallback 이다 — 실제 화면이 뜰 때까지 기다린다.
    expect(
      await screen.findByRole('heading', { name: '관리자 확인' }),
    ).toBeInTheDocument();
    expect(screen.queryByText('FORBIDDEN_PAGE')).toBeNull();
  });

  it('진입_게이트도_관리자만_들어간다', () => {
    // 유효창을 요구하지 않는다고 아무나 들어오는 것은 아니다 — 역할 가드는 그대로 있다.
    setRole('WORKER');
    render(
      <MemoryRouter initialEntries={['/admin']}>
        <Suspense fallback={<div>LOADING</div>}>
          <Routes>
            <Route path="/admin" element={adminGateElement()} />
            <Route path="/forbidden" element={<div>FORBIDDEN_PAGE</div>} />
            <Route path="/ingress" element={<div>INGRESS_PAGE</div>} />
            <Route path="/role-claim" element={<div>ROLE_CLAIM_PAGE</div>} />
          </Routes>
        </Suspense>
      </MemoryRouter>,
    );
    expect(screen.getByText('FORBIDDEN_PAGE')).toBeInTheDocument();
  });

  it('진입_게이트에_검수자는_들어가지_못한다', () => {
    // ★관리자 메뉴가 검수자에게 보이지 않는 것과 **같은 조건**이어야 한다 — 갈리면 「메뉴는
    //   없는데 주소로는 들어가진다」가 된다. 유효창이 아니라 역할에서 갈린다는 것을 고정한다.
    setRole('REVIEWER');
    openWindow();
    render(
      <MemoryRouter initialEntries={['/admin']}>
        <Suspense fallback={<div>LOADING</div>}>
          <Routes>
            <Route path="/admin" element={adminGateElement()} />
            <Route path="/forbidden" element={<div>FORBIDDEN_PAGE</div>} />
            <Route path="/ingress" element={<div>INGRESS_PAGE</div>} />
            <Route path="/role-claim" element={<div>ROLE_CLAIM_PAGE</div>} />
          </Routes>
        </Suspense>
      </MemoryRouter>,
    );
    expect(screen.getByText('FORBIDDEN_PAGE')).toBeInTheDocument();
  });

  it('사용자_관리는_구_주소에_남아있지_않다', () => {
    // 옮긴 것이지 복제한 것이 아니다 — 구 주소가 살아 있으면 유효창을 우회하는 뒷문이 된다.
    expect(ROUTES.has('/manage/users')).toBe(false);
    expect(ROUTES.has('/admin/users')).toBe(true);
  });

  it('파일_업로드는_구_주소에_남아있지_않다', () => {
    expect(ROUTES.has('/dev/upload')).toBe(false);
    expect(ROUTES.has('/admin/uploads')).toBe(true);
  });

  it('산출물_가져오기_구_주소는_새_주소로_보내되_화면을_그리지_않는다', () => {
    // ★북마크·공유 링크 보존은 필요하지만, 구 주소가 **화면을 그대로 그리면** 관리자 가드를
    //   우회하는 뒷문이 된다(검수자가 그 주소로 들어가 마지막 단계에서만 403 을 받던 상태).
    //   그래서 구 주소는 남기되 이동만 시킨다.
    setRole('REVIEWER');
    renderRealRoute('/manage/imports');
    expect(screen.getByText('ADMIN_IMPORTS_TARGET')).toBeInTheDocument();
  });

  it('산출물_가져오기_새_주소는_검수자에게_닫혀_있다', () => {
    // 위 리다이렉트가 「접근 가능」을 뜻하지 않는다 — 목적지에서 역할로 막힌다.
    setRole('REVIEWER');
    openWindow();
    renderRealRoute('/admin/imports');
    expect(screen.getByText('FORBIDDEN_PAGE')).toBeInTheDocument();
  });
});

describe('관리자 메뉴와 라우트가 같은 조건을 쓴다', () => {
  afterEach(() => {
    cleanup();
    useAuthStore.getState().clear();
    useAdminSessionStore.getState().clear();
  });

  it('메뉴에_보이는_여섯_항목이_모두_실재하는_라우트다', () => {
    setRole('ADMIN');
    renderWithProviders(<Lnb />);

    const adminGroup = screen.getByText('관리자').parentElement?.parentElement;
    const hrefs = Array.from(
      (adminGroup as HTMLElement).querySelectorAll('a'),
    ).map((a) => a.getAttribute('href'));

    expect(hrefs).toEqual([...ADMIN_FEATURE_PATHS]);
    for (const href of hrefs) {
      expect(ROUTES.has(href as string), `메뉴 ${href} 에 대응하는 라우트가 없다`).toBe(true);
    }
  });

  it('검수자에게는_관리자_그룹이_통째로_보이지_않는다', () => {
    // ★라우트가 관리자 전용이므로 메뉴도 같은 조건이어야 한다. 남겨 두면 눌러서 접근 거부를
    //   보게 되고, 그건 「여기 뭔가 있는데 나는 못 본다」를 알려 주는 것이라 노출 축의 결함이다.
    setRole('REVIEWER');
    renderWithProviders(<Lnb />);

    expect(screen.queryByText('관리자')).toBeNull();
    for (const path of ADMIN_FEATURE_PATHS) {
      expect(
        document.querySelector(`a[href="${path}"]`),
        `검수자에게 ${path} 링크가 남아 있다`,
      ).toBeNull();
    }
  });

  it('관리자는_관리자_그룹과_검수자_메뉴를_함께_본다', () => {
    // ★계층의 실효를 메뉴 축에서 고정한다 — 관리자가 검수자 권한을 물려받으므로 검수 메뉴가
    //   함께 보여야 한다. 「관리자 그룹만 보인다」면 계층이 노출 축에 닿지 않은 것이다.
    setRole('ADMIN');
    renderWithProviders(<Lnb />);

    expect(screen.getByText('관리자')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '사용자 관리' })).toBeInTheDocument();
    // 검수자에게 열린 자리들 — 계층으로 함께 들어온다.
    expect(screen.getByRole('link', { name: '검수 목록' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '영상 처리 현황' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '프리셋 관리' })).toBeInTheDocument();
    // 작업자·검수자 공용 자리도 그대로다.
    expect(screen.getByRole('link', { name: '작업 목록' })).toBeInTheDocument();
  });

  it('역할이_없으면_메뉴를_하나도_그리지_않는다', () => {
    // 역할 미부여는 라우트 가드가 역할 부여 화면으로 보내는 상태다. 그 사이 전체 메뉴가
    // 펼쳐지면 「권한이 없는데 다 보인다」는 어긋난 화면이 된다(fail-closed).
    useAuthStore.setState({
      token: 'dummy-token',
      claims: { sub: 'u', role: null, channel: 'INTERNAL', exp: 9999999999 },
    });
    renderWithProviders(<Lnb />);

    expect(screen.queryAllByRole('link')).toHaveLength(0);
  });

  it('유효창_보유_여부는_메뉴_노출_조건이_아니다', () => {
    // ★유효창은 관리자 페이지에 들어가 패스워드를 넣어야 열린다. 그것을 노출 조건으로 삼으면
    //   **들어갈 길 자체가 사라진다**(닫힌 고리). 잠긴 상태에서도 메뉴는 그대로 보여야 한다.
    setRole('ADMIN');
    useAdminSessionStore.getState().clear();
    const { unmount } = renderWithProviders(<Lnb />);
    const lockedHrefs = Array.from(
      (screen.getByText('관리자').parentElement?.parentElement as HTMLElement).querySelectorAll(
        'a',
      ),
    ).map((a) => a.getAttribute('href'));
    unmount();

    openWindow();
    renderWithProviders(<Lnb />);
    const unlockedHrefs = Array.from(
      (screen.getByText('관리자').parentElement?.parentElement as HTMLElement).querySelectorAll(
        'a',
      ),
    ).map((a) => a.getAttribute('href'));

    expect(lockedHrefs).toEqual(unlockedHrefs);
    expect(lockedHrefs.length).toBe(ADMIN_FEATURE_PATHS.length);
  });
});
