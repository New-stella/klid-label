// 회귀 가드 — 「영상」 메뉴·라우트는 REVIEWER 전용이다. [@design NAV-001] [@design SCREEN-008] [@design SCREEN-009]
//
// 사양: 영상 처리 현황(SCREEN-008)과 그 하위 영상 상세(SCREEN-009)는 배치 처리 상태를 보는 데
//   그치지 않고 재시도·건너뛰기·재수행 같은 **운영 조치**를 제공하는 자리다. 파이프라인을 다시
//   돌리거나 단계를 건너뛰게 하는 것은 운영 행위이고, 라벨 수정·검수 제출을 맡는 WORKER 의 역할
//   축이 아니다. 두 화면의 required_roles 가 REVIEWER 하나로 확정됐다.
//
// ★이 가드는 **실제 라우트 트리**(`@/router` 의 router.routes)와 **실제 LNB 렌더**를 본다.
//   테스트 안에서 라우트를 새로 선언해 단언하면 프로덕션 코드를 한 줄도 실행하지 않는 동어반복이
//   되어, 허용 역할이 되돌아가도 초록으로 남는다.
//
// ★두 축을 **함께** 고정하는 것이 핵심이다 — 메뉴만 좁히고 라우트를 안 좁히면 「메뉴는 없는데
//   주소로는 들어가진다」가 되고, 반대면 「메뉴는 보이는데 누르면 거부된다」가 된다.
//
// ⚠ 마킹(`/marking/:rawSn`)은 이 제한 대상이 아니다 — 같은 「영상」 개념에 속하지만 WORKER 가
//   들어가는 화면이다. 제한을 그룹 단위로 넓히면 작업자의 마킹 진입이 막힌다.

import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import type { ReactElement } from 'react';
import { cleanup, render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import type { RouteObject } from 'react-router-dom';

import { Lnb } from '@/components/layout/Lnb';
import { router } from '@/router';
import { Role } from '@/lib/api/types';
import { renderWithProviders } from '@/test/renderWithProviders';
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

/** 실제 라우트 element(`<InternalRoute allow={...}>`) 가 선언한 허용 역할. */
function allowOf(fullPath: string): unknown {
  const element = routeAt(fullPath).element as ReactElement<{ allow?: unknown }>;
  expect(element, `라우트 ${fullPath} 에 element 가 없다`).toBeTruthy();
  return element.props.allow;
}

/** 현재 렌더된 LNB 의 그룹 헤더 텍스트를 위에서 아래 순서로 모은다. */
function groupHeaders(): (string | null)[] {
  return Array.from(
    screen
      .getByRole('navigation', { name: '좌측 메뉴' })
      .querySelectorAll('span.uppercase'),
  ).map((el) => el.textContent);
}

function setRole(role: 'REVIEWER' | 'WORKER') {
  useAuthStore.setState({
    // 값은 쓰이지 않는다 — 가드가 보는 것은 claims 뿐이다.
    token: 'dummy-token',
    claims: { sub: 'u', role, channel: 'INTERNAL', exp: 9999999999 },
  });
}

/** 실제 라우트 element 를 그대로 마운트한다 — 가드 판정이 프로덕션 배선 그대로 돈다. */
function renderRealRoute(fullPath: string, entry: string) {
  return render(
    <MemoryRouter initialEntries={[entry]}>
      <Routes>
        <Route path={fullPath} element={routeAt(fullPath).element} />
        <Route path="/forbidden" element={<div>FORBIDDEN_PAGE</div>} />
        <Route path="/ingress" element={<div>INGRESS_PAGE</div>} />
        <Route path="/role-claim" element={<div>ROLE_CLAIM_PAGE</div>} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('영상 라우트 — REVIEWER 전용', () => {
  beforeEach(() => {
    useAuthStore.getState().clear();
  });

  afterEach(() => {
    // ⚠ 순서가 중요하다 — 먼저 언마운트하지 않고 store 를 비우면 아직 마운트된 Lnb 가 그 변경에
    //   반응해 act(...) 경고가 난다.
    cleanup();
    useAuthStore.getState().clear();
  });

  it('영상_처리_현황과_영상_상세의_허용_역할이_REVIEWER_하나다', () => {
    // 구 상태는 [REVIEWER, WORKER] 였다.
    expect(allowOf('/video/status')).toEqual([Role.REVIEWER]);
    expect(allowOf('/video/:id')).toEqual([Role.REVIEWER]);
  });

  it('WORKER_가_두_주소로_직접_들어가면_다른_검수자_전용_화면과_같이_forbidden_이다', () => {
    setRole('WORKER');

    const { unmount } = renderRealRoute('/video/status', '/video/status');
    expect(screen.getByText('FORBIDDEN_PAGE')).toBeInTheDocument();
    unmount();

    renderRealRoute('/video/:id', '/video/12');
    expect(screen.getByText('FORBIDDEN_PAGE')).toBeInTheDocument();
  });

  it('마킹은_이_제한_대상이_아니다_작업자가_계속_들어간다', () => {
    // 같은 「영상」 개념이지만 WORKER 가 들어가는 화면이다 — 그룹 단위로 넓히면 여기가 막힌다.
    // ⚠ 여기서는 실제 마운트를 하지 않는다 — 허용되면 지연 로딩된 마킹 화면(konva 캔버스)이
    //   실제로 import 되어 이 가드의 관심사 밖에서 실패할 수 있다. 거부 케이스는 자식이 아예
    //   렌더되지 않아 안전하지만 허용 케이스는 그렇지 않다.
    expect(allowOf('/marking/:rawSn')).toEqual([Role.REVIEWER, Role.WORKER]);
  });
});

describe('영상 메뉴 — REVIEWER 전용', () => {
  afterEach(() => {
    // ⚠ 순서가 중요하다 — 먼저 언마운트하지 않고 store 를 비우면 아직 마운트된 Lnb 가 그 변경에
    //   반응해 act(...) 경고가 난다.
    cleanup();
    useAuthStore.getState().clear();
  });

  it('WORKER_에게는_영상_그룹이_헤더까지_통째로_사라진다', () => {
    setRole('WORKER');
    renderWithProviders(<Lnb />);

    expect(screen.queryByText('영상')).toBeNull();
    expect(screen.queryByRole('link', { name: '영상 처리 현황' })).toBeNull();
  });

  it('REVIEWER_에게는_영상_그룹과_항목이_그대로_보인다', () => {
    setRole('REVIEWER');
    renderWithProviders(<Lnb />);

    expect(screen.getByText('영상')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '영상 처리 현황' })).toHaveAttribute(
      'href',
      '/video/status',
    );
  });

  it('그룹_순서는_그대로다_WORKER_에게서는_영상_자리만_새로_빠진다', () => {
    // 그룹 순서는 NAV-001 이 정한다 — 이번 변경은 접근 권한 축이라 순서를 건드리지 않는다.
    setRole('REVIEWER');
    const { unmount } = renderWithProviders(<Lnb />);
    const reviewerGroups = groupHeaders();
    expect(reviewerGroups).toEqual([
      '대시보드',
      '영상',
      '작업',
      '데이터',
      '통계',
      '게시판',
      '업로드',
      '관리',
    ]);
    unmount();

    setRole('WORKER');
    renderWithProviders(<Lnb />);
    const workerGroups = groupHeaders();

    // 이번 변경으로 「영상」이 빠진다. 나머지 차이(데이터·업로드·관리)는 원래부터 REVIEWER 전용인
    // 그룹이라 이 변경과 무관하다 — 그래서 목록 전체를 그대로 못박는다.
    expect(workerGroups).toEqual(['대시보드', '작업', '통계', '게시판']);
    // 남은 그룹의 상대 순서는 REVIEWER 와 동일하다(부분수열).
    expect(workerGroups).toEqual(reviewerGroups.filter((g) => workerGroups.includes(g)));
  });
});

describe('메뉴와 라우트가 같은 조건을 쓴다', () => {
  afterEach(() => {
    // ⚠ 순서가 중요하다 — 먼저 언마운트하지 않고 store 를 비우면 아직 마운트된 Lnb 가 그 변경에
    //   반응해 act(...) 경고가 난다.
    cleanup();
    useAuthStore.getState().clear();
  });

  it('메뉴에_보이는_역할과_라우트가_허용하는_역할이_일치한다', () => {
    // 한쪽만 좁히면 「메뉴는 없는데 주소로는 들어가진다」(또는 그 반대)가 된다.
    const routeAllows = allowOf('/video/status') as string[];

    for (const role of ['REVIEWER', 'WORKER'] as const) {
      setRole(role);
      const { unmount } = renderWithProviders(<Lnb />);
      const menuVisible =
        screen.queryByRole('link', { name: '영상 처리 현황' }) !== null;
      expect(
        menuVisible,
        `${role}: 메뉴 노출(${menuVisible}) 과 라우트 허용(${routeAllows.includes(role)}) 이 갈렸다`,
      ).toBe(routeAllows.includes(role));
      unmount();
    }
  });
});
