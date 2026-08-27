// Lnb — 좌측 메뉴 키 유일성 + 「업로드」·「관리자」 그룹 구성 검증. [@design NAV-001]
//
// 배경: Lnb 모듈은 모듈 스코프 가변 배열 MENU 의 '관리자' 그룹에 파일 업로드 항목을 등록한다.
//   Vite HMR 로 본 모듈이 재평가되면 등록이 누적되어 같은 항목이 중복 추가되고,
//   렌더 시 React "two children with the same key"(key=i.path) 경고가 발생한다.
//   registerManualUploadMenu 의 멱등 가드가 적용되면 재호출해도 항목은 1개로 유지된다.
//
// 「업로드」 그룹은 서버에 이미 있는 폴더를 데이터로 들여오는 자리이며 게시판과 관리 **사이**에
//   온다. 「파일 업로드」는 관리자 패스워드 확인을 거쳐야 하는 화면이라 「관리자」 그룹으로
//   옮겨갔다 — 두 자리에 다 뜨면 어느 쪽이 정본인지 알 수 없다.

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

  it('registerManualUploadMenu_재호출_시_파일_업로드_항목이_중복되지_않는다', () => {
    // HMR 로 모듈이 여러 번 재평가되는 상황을 모사 — 동일 배열에 반복 등록
    const menu: MenuGroupLike[] = [{ group: '관리자', items: [] }];
    registerManualUploadMenu(menu as never);
    registerManualUploadMenu(menu as never);
    registerManualUploadMenu(menu as never);

    const items = menu.flatMap((g) => g.items);
    expect(items.filter((i) => i.path === '/admin/uploads')).toHaveLength(1);

    // path(=key) 의 유일성 — 중복 key 가 없어야 React 경고가 발생하지 않음
    const itemKeys = items.map((i) => i.path);
    expect(new Set(itemKeys).size).toBe(itemKeys.length);
  });

  it('관리자_그룹이_없는_메뉴에는_아무것도_등록하지_않는다', () => {
    // fail-closed — 엉뚱한 자리에 '관리자' 그룹을 새로 만들지 않는다.
    //
    // ★픽스처를 **옛 대상 그룹('업로드')** 으로 둔다. 무관한 제3의 그룹('통계')을 쓰면 대상
    //   상수가 바뀌어도 단언이 그대로 참이라, 개명을 안 해도 초록으로 남아 「가드가 낡은 상수를
    //   고정하고 있다」는 사실 자체를 숨긴다(부정 케이스는 개명에 둔감하다).
    const menu: MenuGroupLike[] = [{ group: '업로드', items: [] }];
    registerManualUploadMenu(menu as never);

    expect(menu).toHaveLength(1);
    expect(menu.flatMap((g) => g.items)).toHaveLength(0);
  });

  it('업로드_그룹에는_산출물_가져오기만_남는다', () => {
    // 파일 업로드는 관리자 그룹으로 옮겨갔다 — 옮긴 것이지 복제한 것이 아니다.
    // 그룹 헤더(span) → 래퍼 div → 그룹 컨테이너 순으로 거슬러 올라간다.
    renderWithProviders(<Lnb />);
    const uploadGroup = screen.getByText('업로드').parentElement?.parentElement;
    expect(uploadGroup).toBeTruthy();

    const labels = within(uploadGroup as HTMLElement)
      .getAllByRole('link')
      .map((a) => a.textContent);

    expect(labels).toEqual(['산출물 가져오기']);
  });

  it('관리자_그룹이_NAV_001_순서대로_다섯_항목을_갖는다', () => {
    // vitest 는 DEV 빌드라 isDevUploadEnabled() 가 true → 실제 MENU 에 파일 업로드가 등록된다.
    // 순서는 NAV-001 이 정한다 — 파일 업로드는 끝에 붙는 것이 아니라 패스워드 교체 **앞**이다.
    renderWithProviders(<Lnb />);
    const adminGroup = screen.getByText('관리자').parentElement?.parentElement;
    expect(adminGroup).toBeTruthy();

    const links = within(adminGroup as HTMLElement).getAllByRole('link');
    expect(links.map((a) => a.textContent)).toEqual([
      '사용자 관리',
      '연동 서버 주소',
      '파일 업로드',
      '패스워드 교체',
      '위험 액션',
    ]);
    expect(links.map((a) => a.getAttribute('href'))).toEqual([
      '/admin/users',
      '/admin/endpoints',
      '/admin/uploads',
      '/admin/password',
      '/admin/maintenance',
    ]);
  });

  it('진입_게이트는_메뉴에_두지_않는다', () => {
    // 눌러서 가는 곳이 아니라 유효창이 없을 때 대신 열리는 자리다. 메뉴에 두면 확인을 마친
    // 사용자가 그것을 눌러 아무 일도 일어나지 않는 화면으로 되돌아간다.
    renderWithProviders(<Lnb />);
    const hrefs = screen
      .getAllByRole('link')
      .map((a) => a.getAttribute('href'));
    expect(hrefs).not.toContain('/admin');
  });

  it('업로드_그룹이_게시판과_관리_사이에_놓이고_관리자_그룹이_맨_뒤다', () => {
    // 그룹 순서는 NAV-001 이 정한다 — 데이터를 들여오는 자리를 설정을 다루는 「관리」 앞에 두고,
    // 관리자 패스워드 확인을 요구하는 「관리자」를 맨 뒤에 둔다.
    renderWithProviders(<Lnb />);
    const nav = screen.getByRole('navigation', { name: '좌측 메뉴' });
    const groupHeaders = Array.from(
      nav.querySelectorAll('span.uppercase'),
    ).map((el) => el.textContent);

    expect(groupHeaders).toEqual([
      '대시보드',
      '영상',
      '작업',
      '데이터',
      '통계',
      '게시판',
      '업로드',
      '관리',
      '관리자',
    ]);
  });

  it('관리_그룹에_이관_업로드_항목이_남아있지_않다', () => {
    // 옮긴 것이지 복제한 것이 아니다 — 두 자리에 다 뜨면 어느 쪽이 정본인지 알 수 없다.
    renderWithProviders(<Lnb />);
    const manageGroup = screen.getByText('관리').parentElement?.parentElement;
    const labels = within(manageGroup as HTMLElement)
      .getAllByRole('link')
      .map((a) => a.textContent);

    expect(labels).not.toContain('산출물 가져오기');
    expect(labels).not.toContain('파일 업로드');
    expect(labels).not.toContain('사용자 관리');
    expect(labels).not.toContain('외부 산출물 이관');
    expect(labels).not.toContain('수동 업로드');
  });

  it('WORKER_에게는_업로드와_관리자_그룹이_통째로_보이지_않는다', () => {
    // 모든 항목이 REVIEWER 전용이라 visible.length === 0 으로 그룹 헤더까지 사라진다.
    useAuthStore.setState({
      token: 'tok',
      claims: { sub: '2', role: 'WORKER', channel: 'INTERNAL', exp: 9999999999 },
    });
    renderWithProviders(<Lnb />);

    expect(screen.queryByText('업로드')).toBeNull();
    expect(screen.queryByRole('link', { name: '산출물 가져오기' })).toBeNull();
    expect(screen.queryByText('관리자')).toBeNull();
    expect(screen.queryByRole('link', { name: '파일 업로드' })).toBeNull();
    expect(screen.queryByRole('link', { name: '사용자 관리' })).toBeNull();
  });

  it('registerManualUploadMenu_는_파일_업로드만_넣는다', () => {
    // 나머지 네 항목은 MENU 배열에 직접 들어 있어 이 함수의 소관이 아니다 — 그래서 dev 업로드
    // 토글이 꺼져도 「관리자」 그룹이 통째로 사라지지 않는다(그 렌더 검증은 LnbDevUploadOff).
    const menu: MenuGroupLike[] = [{ group: '관리자', items: [] }];
    registerManualUploadMenu(menu as never);

    expect(menu[0]!.items.map((i) => i.label)).toEqual(['파일 업로드']);
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
    //   순서도 함께 못박는다 — NAV-001 이 「비식별 신고」 다음으로 정했다.
    renderWithProviders(<Lnb />);
    const manageGroup = screen.getByText('관리').parentElement?.parentElement;
    const labels = within(manageGroup as HTMLElement)
      .getAllByRole('link')
      .map((a) => a.textContent);

    expect(labels).toContain('이벤트유형 관리');
    expect(labels.indexOf('이벤트유형 관리')).toBe(labels.indexOf('비식별 신고') + 1);
    // 「이벤트유형 관리」가 관리 그룹의 마지막이다 — 산출물 가져오기는 「업로드」로,
    // 사용자 관리는 「관리자」로 옮겨갔다.
    expect(labels[labels.length - 1]).toBe('이벤트유형 관리');
    expect(labels).not.toContain('사용자 관리');
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
