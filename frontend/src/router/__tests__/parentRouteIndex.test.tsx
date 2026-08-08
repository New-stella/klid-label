// 회귀 가드 — 하위 라우트를 가진 부모 경로에 직접 진입해도 빈 화면이 뜨지 않는다.
//
// 결함(브라우저 실측): `/video` 로 직접 진입하면 **완전한 빈 화면**이 떴다
//   (콘솔: `Matched leaf route at location "/video" does not have an element or Component`).
//   부모가 element 없는 pathless 라우트라 자기 자신이 leaf 로 매칭되고 `<Outlet/>` 이 null 을 그린다.
//   LNB 링크는 `/video/completed` 를 가리켜 정상 동선에서는 드러나지 않고,
//   북마크·주소 직접 입력·뒤로가기로만 도달하는 조용한 결함이었다.
//
// ⚠ `path: '*'` 는 이 구멍을 메우지 못한다 — 남은 경로가 빈 문자열이면 매칭되지 않는다
//   (실측: `/manage` 도 `*` 자식이 있는데 빈 화면이었다). **반드시 index 라우트여야 한다.**
//
// 이 가드는 `/video`·`/manage` 두 건을 개별 확인하는 대신 **라우트 트리 전체를 순회**한다 —
// 앞으로 부모 라우트가 추가돼도 같은 결함이 다시 생기지 않게 하려면 구조 자체를 고정해야 한다.

import { describe, expect, it } from 'vitest';
import type { RouteObject } from 'react-router-dom';

import { router } from '@/router';

interface Offender {
  path: string;
  reason: string;
}

/**
 * element 를 갖지 않은 채 자식만 가진 부모 라우트 중, 빈 경로를 받아줄 index 가 없는 것을 모은다.
 *
 * `path: '*'` 자식은 빈 나머지 경로를 매칭하지 않으므로 대체재로 세지 않는다.
 */
function findParentsWithoutIndex(routes: RouteObject[], prefix = ''): Offender[] {
  const found: Offender[] = [];
  for (const route of routes) {
    const segment = route.path ?? '';
    const joined = segment.startsWith('/')
      ? segment
      : `${prefix}${segment ? `/${segment}` : ''}`;
    // 루트('/')와 자식 세그먼트가 만나면 '//video' 가 되어 실패 메시지가 읽기 어려워진다.
    const full = joined.replace(/\/{2,}/g, '/');
    const children = route.children;
    if (children && children.length > 0) {
      const hasIndex = children.some((c) => c.index === true);
      const selfRenders = Boolean(route.element);
      if (!hasIndex && !selfRenders) {
        found.push({ path: full || '/', reason: 'index 라우트도 없고 부모 element 도 없다' });
      }
      found.push(...findParentsWithoutIndex(children, full));
    }
  }
  return found;
}

describe('부모 라우트 직접 진입 (빈 화면 방지)', () => {
  it('자식만_가진_부모_라우트는_전부_index를_갖는다', () => {
    const offenders = findParentsWithoutIndex(router.routes as RouteObject[]);

    // 실패 시 어느 경로가 비었는지 바로 보이게 경로 목록을 메시지에 싣는다.
    expect(offenders.map((o) => `${o.path} — ${o.reason}`)).toEqual([]);
  });

  it('video와_manage는_대표_하위화면으로_리다이렉트한다', () => {
    // 위 구조 가드는 "index 가 있다"까지만 본다. 어디로 보내는지가 화면 동선이라 함께 못 박는다
    // (LNB 의 대표 항목과 같은 곳이어야 사용자가 이동한 결과를 예측할 수 있다).
    const internal = (router.routes as RouteObject[]).find((r) => r.path === '/');
    const indexTargetOf = (path: string) => {
      const parent = internal?.children?.find((c) => c.path === path);
      const index = parent?.children?.find((c) => c.index === true);
      const el = index?.element as { props?: { to?: string } } | undefined;
      return el?.props?.to;
    };

    expect(indexTargetOf('video')).toBe('/video/completed');
    expect(indexTargetOf('manage')).toBe('/manage/users');
  });
});
