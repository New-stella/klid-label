// Lnb — 좌측 메뉴 키 유일성 검증 (R11-2).
//
// 배경: Lnb 모듈은 모듈 스코프 가변 배열 MENU 의 '관리' 그룹에 수동 업로드 항목을 등록한다.
//   Vite HMR 로 본 모듈이 재평가되면 등록이 누적되어 같은 항목이 중복 추가되고,
//   렌더 시 React "two children with the same key"(key=i.path) 경고가 발생한다.
//   registerManualUploadMenu 의 멱등 가드가 적용되면 재호출해도 항목은 1개로 유지된다.

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, screen, within } from '@testing-library/react';

import { Lnb, registerManualUploadMenu } from '@/components/layout/Lnb';
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

  it('registerManualUploadMenu_재호출_시_수동_업로드_항목이_중복되지_않는다', () => {
    // HMR 로 모듈이 여러 번 재평가되는 상황을 모사 — 동일 배열에 반복 등록
    const menu: MenuGroupLike[] = [{ group: '관리', items: [] }];
    registerManualUploadMenu(menu as never);
    registerManualUploadMenu(menu as never);
    registerManualUploadMenu(menu as never);

    const items = menu.flatMap((g) => g.items);
    expect(items.filter((i) => i.path === '/dev/upload')).toHaveLength(1);

    // path(=key) 의 유일성 — 중복 key 가 없어야 React 경고가 발생하지 않음
    const itemKeys = items.map((i) => i.path);
    expect(new Set(itemKeys).size).toBe(itemKeys.length);
  });

  it('관리_그룹이_없는_메뉴에는_아무것도_등록하지_않는다', () => {
    // fail-closed — 엉뚱한 자리에 '관리' 그룹을 새로 만들지 않는다.
    const menu: MenuGroupLike[] = [{ group: '통계', items: [] }];
    registerManualUploadMenu(menu as never);

    expect(menu).toHaveLength(1);
    expect(menu.flatMap((g) => g.items)).toHaveLength(0);
  });

  it('수동_업로드가_관리_그룹의_외부_산출물_이관_다음에_놓인다', () => {
    // vitest 는 DEV 빌드라 isDevUploadEnabled() 가 true → 실제 MENU 에 등록된다.
    // 그룹 헤더(span) → 래퍼 div → 그룹 컨테이너 순으로 거슬러 올라간다.
    renderWithProviders(<Lnb />);
    const manageGroup = screen.getByText('관리').parentElement?.parentElement;
    expect(manageGroup).toBeTruthy();

    const labels = within(manageGroup as HTMLElement)
      .getAllByRole('link')
      .map((a) => a.textContent);

    // 그룹 소속과 순서를 함께 못 박는다 — NAV-001 이 「외부 산출물 이관」 다음으로 정했다.
    expect(labels).toContain('수동 업로드');
    expect(labels.indexOf('수동 업로드')).toBe(labels.indexOf('외부 산출물 이관') + 1);
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

  it('이벤트유형_관리가_관리_그룹의_비식별_신고_다음에_놓인다', () => {
    // ★ 이 항목이 빠져 있어 라우트(REVIEWER 전용)는 사는데 <b>도달 경로가 0</b>이었다
    //   (주소를 직접 입력해야만 들어갈 수 있었다). 설계에는 이미 있던 항목이라 순수 구현 드리프트다.
    //   순서도 함께 못박는다 — NAV-001 이 「비식별 신고」 다음, 「외부 산출물 이관」 앞으로 정했다.
    renderWithProviders(<Lnb />);
    const manageGroup = screen.getByText('관리').parentElement?.parentElement;
    const labels = within(manageGroup as HTMLElement)
      .getAllByRole('link')
      .map((a) => a.textContent);

    expect(labels).toContain('이벤트유형 관리');
    expect(labels.indexOf('이벤트유형 관리')).toBe(labels.indexOf('비식별 신고') + 1);
    expect(labels.indexOf('외부 산출물 이관')).toBe(labels.indexOf('이벤트유형 관리') + 1);
  });

  it('WORKER_에게는_이벤트유형_관리_메뉴가_보이지_않는다', () => {
    // ⚠ 메뉴 노출 조건은 라우트 가드(internalReviewerOnly)와 <b>같은 조건</b>이어야 한다 —
    //   갈리면 「메뉴는 없는데 주소로는 들어가진다」(또는 그 반대)가 된다.
    useAuthStore.setState({
      token: 'tok',
      claims: { sub: '2', role: 'WORKER', channel: 'INTERNAL', exp: 9999999999 },
    });
    renderWithProviders(<Lnb />);

    expect(screen.queryByRole('link', { name: '이벤트유형 관리' })).toBeNull();
  });

  it('REVIEWER_렌더_시_관리_그룹과_비식별_신고_메뉴가_중복_없이_표시된다', () => {
    renderWithProviders(<Lnb />);
    expect(screen.getAllByText('관리')).toHaveLength(1);
    expect(screen.getAllByRole('link', { name: '비식별 신고' })).toHaveLength(1);
  });
});
