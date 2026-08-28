// 좌측 메뉴 노출과 라우트 인가가 **한 선언에서 파생**되는지 고정한다.
// [@design NAV-001] [@design SHELL-001] [@design ROLE-004] [@design ADR-055]
//
// 무엇을 막나: 예전에는 같은 조건이 두 곳에 적혀 있었다 — 좌측 메뉴가 항목마다 허용 역할을 들고,
//   라우터가 따로 허용 목록을 걸었다. 두 파일의 주석이 「같은 조건이어야 한다」고 서로를 향해
//   경고만 했을 뿐 그것을 지키게 하는 장치가 없어, 한쪽만 갱신되면 「메뉴는 없는데 주소로는
//   들어가진다」(또는 그 반대)가 생겼다. 이제 둘 다 `@/lib/routeAccess` 를 읽는다.
//
// ★이 파일은 **두 축을 함께** 못 박는다 — 선언을 고치면 메뉴 단언과 라우터 단언이 **같이**
//   빨개져야 한다. 한쪽만 빨개지면 합쳐진 것이 아니라 한쪽이 여전히 자기 목록을 들고 있는 것이다.
//
// ★네 역할 전부에 단언을 둔다. 관리자 축에만 두면 검수자·작업자·포털의 조용한 변화가 통과한다 —
//   합치는 작업의 가장 큰 위험이 「관리자 외 동선이 바뀌는 것」이라 그 축을 먼저 고정한다.
//
// ★메뉴에 없지만 주소로 도달하는 화면(마킹·영상 상세·라벨링 캔버스·검수 상세·증강 결과·공지
//   상세/작성/수정)의 인가도 함께 본다. 「메뉴에서 파생」만으로는 그 자리가 통째로 덮이지 않아,
//   합치는 과정에서 소리 없이 사라질 수 있는 축이다.

import { isValidElement } from 'react';
import { afterEach, describe, expect, it } from 'vitest';
import { cleanup, screen } from '@testing-library/react';
import type { RouteObject } from 'react-router-dom';

import { Lnb } from '@/components/layout/Lnb';
import { Role } from '@/lib/api/types';
import { roleSatisfiesAny } from '@/lib/authz';
import { allowFor, buildMenuGroups, isDeclaredPath } from '@/lib/routeAccess';
import { router } from '@/router';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';

interface GuardedRoute {
  path: string;
  allow: readonly Role[];
}

/**
 * 라우트 트리를 훑어 **가드가 걸린 내부 경로**만 모은다.
 *
 * 판정 기준은 요소의 `allow` prop 존재다 — `InternalRoute`·`AdminRoute` 만 그 prop 을 갖는다.
 * 포털 라우트(`PortalRoute`)는 채널 자체가 달라 이 표의 대상이 아니고, 리다이렉트·오류 화면은
 * 가드가 없어 자연히 빠진다.
 */
function collectGuardedRoutes(routes: RouteObject[], prefix = ''): GuardedRoute[] {
  const out: GuardedRoute[] = [];
  for (const route of routes) {
    const segment = route.path ?? '';
    const joined = segment.startsWith('/')
      ? segment
      : `${prefix}${segment ? `/${segment}` : ''}`;
    // index 라우트는 segment 가 없어 부모 경로를 그대로 쓴다(`/task` 의 index 등).
    const full = (joined || prefix).replace(/\/{2,}/g, '/');
    const element = route.element;
    if (isValidElement(element)) {
      const allow = (element.props as { allow?: readonly Role[] }).allow;
      if (allow !== undefined) out.push({ path: full, allow });
    }
    if (route.children) out.push(...collectGuardedRoutes(route.children, full));
  }
  return out;
}

const GUARDED = collectGuardedRoutes(router.routes as RouteObject[]);
const GUARDED_PATHS = [...new Set(GUARDED.map((r) => r.path))].sort();

/** 그 역할이 통과하는 내부 경로 — **라우트 트리에서 읽은 값**으로 판정한다(선언을 되읽지 않는다). */
function permittedPaths(role: Role): string[] {
  return [
    ...new Set(GUARDED.filter((r) => roleSatisfiesAny(role, r.allow)).map((r) => r.path)),
  ].sort();
}

/**
 * 관리자 전용 경로 — 검수자가 닿지 못하는 자리다. **값으로 못 박는다**(선언에서 다시 뽑으면
 * 자기 자신을 자기 자신과 비교하는 동어반복이 된다).
 */
const ADMIN_ONLY_PATHS = [
  '/admin',
  '/admin/endpoints',
  '/admin/imports',
  '/admin/maintenance',
  '/admin/password',
  '/admin/uploads',
  '/admin/users',
];

/** 작업자가 닿는 내부 경로 전부. 검수자 전용·관리자 전용이 아닌 자리만 남는다. */
const WORKER_PATHS = [
  '/dashboard',
  '/label/:id',
  '/marking/:rawSn',
  '/notice',
  '/notice/:id',
  '/stat',
  '/stat/worker',
  '/task',
];

/** 메뉴에 없지만 주소로 도달하는 화면 — 인가가 사라지면 안 되는 자리다. */
const MENU_HIDDEN_PATHS = [
  '/marking/:rawSn',
  '/video/:id',
  '/label/:id',
  '/review',
  '/review/:id',
  '/augment',
  '/augment/result/:rawSn',
  '/notice/:id',
  '/notice/new',
  '/notice/:id/edit',
  '/manage/*',
  '/admin',
];

/** 인증 토큰 자리 — 값 자체는 판정에 쓰이지 않는다(가드는 `claims` 만 읽는다). */
const AUTH_STUB = 'tok';

/** 그 역할로 좌측 메뉴를 그리고 보이는 항목 라벨을 순서대로 돌려준다. */
function renderLnbAs(role: Role | null): string[] {
  useAuthStore.setState({
    token: AUTH_STUB,
    claims: { sub: '1', role, channel: 'INTERNAL', exp: 9999999999 },
  });
  renderWithProviders(<Lnb />);
  return screen.queryAllByRole('link').map((a) => a.textContent ?? '');
}

describe('메뉴·라우트 인가 단일 진실원 — 파생 증명', () => {
  it('가드된_내부_라우트가_한_건도_빠짐없이_선언에서_값을_받는다', () => {
    // 스캔 0건이면 가드가 아무것도 보지 않은 것이다 — 통과가 아니라 실패다.
    expect(GUARDED.length).toBeGreaterThan(20);

    for (const route of GUARDED) {
      // 참조 동일성 — 라우터가 값을 **복제**하거나 자기 상수를 다시 두면 여기서 깨진다.
      expect(route.allow, `${route.path} 가 선언에서 값을 받지 않는다`).toBe(allowFor(route.path));
    }
  });

  it('라우터가_쓰는_경로가_전부_선언에_있다', () => {
    // 선언에 없으면 `allowFor` 가 빈 목록(=아무에게도 열려 있지 않음)을 돌려줘 조용히 잠긴다.
    // fail-closed 는 맞지만 그대로 두면 발견이 늦으므로 여기서 잡는다.
    const undeclared = GUARDED_PATHS.filter((p) => !isDeclaredPath(p));
    expect(undeclared).toEqual([]);
  });

  it('메뉴_항목이_라우트와_같은_선언_객체를_읽는다', () => {
    const items = buildMenuGroups({ devUploadEnabled: true }).flatMap((g) => g.items);
    expect(items.length).toBeGreaterThan(10);

    const routeAllow = new Map(GUARDED.map((r) => [r.path, r.allow]));
    for (const item of items) {
      // 메뉴에 뜨는 항목은 그 주소의 라우트가 반드시 있어야 한다(눌러도 안 열리는 항목 차단).
      expect(routeAllow.has(item.path), `${item.path} 메뉴 항목에 대응하는 라우트가 없다`).toBe(
        true,
      );
      // 두 축이 같은 객체를 본다 — 한쪽만 고쳐지는 상태가 구조적으로 불가능해진다.
      expect(item.allow, `${item.label} 의 메뉴·라우트 조건이 갈렸다`).toBe(
        routeAllow.get(item.path),
      );
    }
  });
});

describe('메뉴·라우트 인가 단일 진실원 — 네 역할 축 (라우터)', () => {
  it('관리자는_내부_전_경로에_닿는다', () => {
    // 관리자는 계층으로 검수자를 물려받는다 — 물려받기가 끊기면 여기가 먼저 빨개진다.
    expect(permittedPaths(Role.ADMIN)).toEqual(GUARDED_PATHS);
  });

  it('검수자는_관리자_전용_경로에만_닿지_못한다', () => {
    const expected = GUARDED_PATHS.filter((p) => !ADMIN_ONLY_PATHS.includes(p));
    expect(permittedPaths(Role.REVIEWER)).toEqual(expected);
    // 관리자 전용 목록이 실제로 라우트에 존재하는 경로인지도 함께 본다(오타 방지).
    for (const p of ADMIN_ONLY_PATHS) {
      expect(GUARDED_PATHS, `${p} 라우트가 없다`).toContain(p);
    }
  });

  it('작업자가_닿는_경로가_그대로다', () => {
    expect(permittedPaths(Role.WORKER)).toEqual(WORKER_PATHS);
  });

  it('포털_회원은_내부_경로에_하나도_닿지_못한다', () => {
    // 채널 격리는 `ChannelGuard` 가 따로 막지만, 역할 축에서도 뚫리지 않아야 한다.
    expect(permittedPaths(Role.PORTAL_USER)).toEqual([]);
  });

  it('메뉴에_없지만_주소로_도달하는_화면의_인가가_남아_있다', () => {
    for (const path of MENU_HIDDEN_PATHS) {
      expect(GUARDED_PATHS, `${path} 의 인가가 사라졌다`).toContain(path);
      expect(allowFor(path).length, `${path} 가 아무에게도 열려 있지 않다`).toBeGreaterThan(0);
    }
    // 그리고 그 경로들은 메뉴에 뜨지 않는다 — 인가만 있고 메뉴에는 없는 것이 사양이다.
    const menuPaths = buildMenuGroups({ devUploadEnabled: true })
      .flatMap((g) => g.items)
      .map((i) => i.path);
    for (const path of MENU_HIDDEN_PATHS) {
      expect(menuPaths, `${path} 가 메뉴에 노출된다`).not.toContain(path);
    }
  });
});

describe('메뉴·라우트 인가 단일 진실원 — 네 역할 축 (메뉴)', () => {
  afterEach(() => {
    useAuthStore.getState().clear();
    cleanup();
  });

  it('관리자_메뉴가_그대로다', () => {
    expect(renderLnbAs(Role.ADMIN)).toEqual([
      '대시보드',
      '영상 처리 현황',
      '작업 목록',
      '검수 목록',
      '증강 요청',
      '작업자 통계',
      '전체 구축 현황',
      '게시판',
      '시스템 설정',
      '라벨 관리',
      '프리셋 관리',
      '비식별 신고',
      '이벤트유형 관리',
      '사용자 관리',
      '연동 서버 주소',
      '산출물 가져오기',
      '파일 업로드',
      '패스워드 교체',
      '위험 액션',
    ]);
  });

  it('검수자_메뉴가_그대로다', () => {
    // 관리자 그룹 여섯 항목만 빠진다 — 나머지는 하나도 달라지지 않는다.
    expect(renderLnbAs(Role.REVIEWER)).toEqual([
      '대시보드',
      '영상 처리 현황',
      '작업 목록',
      '검수 목록',
      '증강 요청',
      '작업자 통계',
      '전체 구축 현황',
      '게시판',
      '시스템 설정',
      '라벨 관리',
      '프리셋 관리',
      '비식별 신고',
      '이벤트유형 관리',
    ]);
  });

  it('작업자_메뉴가_그대로다', () => {
    expect(renderLnbAs(Role.WORKER)).toEqual([
      '대시보드',
      '작업 목록',
      '작업자 통계',
      '게시판',
    ]);
  });

  it('포털_회원에게는_내부_메뉴가_하나도_보이지_않는다', () => {
    expect(renderLnbAs(Role.PORTAL_USER)).toEqual([]);
  });

  it('역할_미부여에게는_내부_메뉴가_하나도_보이지_않는다', () => {
    // 라우트 가드가 역할 부여 화면으로 보내므로, 그 사이에 전체 메뉴가 잠깐 펼쳐지면 안 된다.
    expect(renderLnbAs(null)).toEqual([]);
  });
});
