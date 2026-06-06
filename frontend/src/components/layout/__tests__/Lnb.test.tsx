// Lnb — 좌측 메뉴 키 유일성 검증 (R11-2).
//
// 배경: Lnb 모듈은 모듈 스코프 가변 배열 MENU 에 DEV 전용 '개발 도구' 그룹을 등록한다.
//   Vite HMR 로 본 모듈이 재평가되면 등록이 누적되어 '개발 도구' 그룹이 중복 추가되고,
//   렌더 시 React "two children with the same key"(key=g.group) 경고가 발생한다.
//   registerDevToolsMenu 의 멱등 가드가 적용되면 재호출해도 그룹은 1개로 유지된다.

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, screen } from '@testing-library/react';

import { Lnb, registerDevToolsMenu } from '@/components/layout/Lnb';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';

interface MenuGroupLike {
  group: string;
  items: { label: string; path: string; allow: string[] }[];
}

describe('Lnb 메뉴 키 유일성', () => {
  beforeEach(() => {
    useAuthStore.setState({
      token: 'tok',
      claims: { sub: '1', role: 'REVIEWER', channel: 'INTERNAL', exp: 9999999999 },
    });
  });

  afterEach(() => {
    useAuthStore.getState().clear();
    cleanup();
  });

  it('registerDevToolsMenu_재호출_시_개발도구_그룹이_중복되지_않는다', () => {
    // HMR 로 모듈이 여러 번 재평가되는 상황을 모사 — 동일 배열에 반복 등록
    const menu: MenuGroupLike[] = [];
    registerDevToolsMenu(menu as never);
    registerDevToolsMenu(menu as never);
    registerDevToolsMenu(menu as never);

    const devGroups = menu.filter((g) => g.group === '개발 도구');
    expect(devGroups).toHaveLength(1);

    // group(=key) 의 유일성 — 중복 key 가 없어야 React 경고가 발생하지 않음
    const groupKeys = menu.map((g) => g.group);
    expect(new Set(groupKeys).size).toBe(groupKeys.length);
  });

  it('반복_렌더_시_중복_key_React_경고가_발생하지_않는다', () => {
    const errorSpy = vi.spyOn(console, 'error').mockImplementation(() => {});
    try {
      for (let i = 0; i < 3; i += 1) {
        const { unmount } = renderWithProviders(<Lnb />);
        unmount();
      }
      renderWithProviders(<Lnb />);

      const keyWarning = errorSpy.mock.calls.some((args) =>
        args.some(
          (a) => typeof a === 'string' && /same key|two children with the same key/i.test(a),
        ),
      );
      expect(keyWarning).toBe(false);
    } finally {
      errorSpy.mockRestore();
    }
  });

  it('REVIEWER_렌더_시_관리_그룹과_비식별_신고_메뉴가_중복_없이_표시된다', () => {
    renderWithProviders(<Lnb />);
    expect(screen.getAllByText('관리')).toHaveLength(1);
    expect(screen.getAllByRole('link', { name: '비식별 신고' })).toHaveLength(1);
  });
});
