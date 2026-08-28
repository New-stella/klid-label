// Lnb — 좌측 메뉴 키 유일성 + 「업로드」·「관리자」 그룹 구성 검증. [@design NAV-001]
//
// 배경: Lnb 모듈은 모듈 스코프 가변 배열 MENU 의 '관리자' 그룹에 파일 업로드 항목을 등록한다.
//   Vite HMR 로 본 모듈이 재평가되면 등록이 누적되어 같은 항목이 중복 추가되고,
//   렌더 시 React "two children with the same key"(key=i.path) 경고가 발생한다.
//   registerManualUploadMenu 의 멱등 가드가 적용되면 재호출해도 항목은 1개로 유지된다.
//
// ★「업로드」 그룹은 **없어졌다**(2026-08-28). 데이터를 들여오는 두 화면이 모두 「관리자」 그룹으로
//   옮겨가 항목이 하나도 남지 않았기 때문이다 — 「산출물 가져오기」는 서버가 적재 실행·대응
//   저장/삭제를 관리자 전용으로 좁히면서, 「파일 업로드」는 관리자 패스워드 확인을 요구하면서.
//   ⚠ 구 단언 폐기 — *"업로드 그룹에는 산출물 가져오기만 남는다"* · *"업로드 그룹이 게시판과 관리
//     사이에 놓인다"*. 되살리면 검수자가 열어서 마지막 단계에만 403 을 받는 상태로 돌아간다.
//   두 자리에 다 뜨면 어느 쪽이 정본인지 알 수 없으므로 이동은 **복제가 아님**을 함께 못박는다.

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, screen, within } from '@testing-library/react';

import { Lnb, registerManualUploadMenu } from '@/components/layout/Lnb';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';

interface MenuGroupLike {
  group: string;
  items: { label: string; path: string; allow: string[] }[];
}

/** 렌더 배우를 바꾼다 — 「관리자」 그룹은 관리자에게만 보이므로 케이스마다 역할이 다르다. */
function setRole(role: 'ADMIN' | 'REVIEWER' | 'WORKER') {
  useAuthStore.setState({
    token: 'tok',
    claims: { sub: '1', role, channel: 'INTERNAL', exp: 9999999999 },
  });
}

describe('Lnb 메뉴 키 유일성', () => {
  beforeEach(() => {
    setRole('REVIEWER');
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

  it('업로드_그룹은_항목이_없어_통째로_사라진다', () => {
    // ★두 화면이 모두 「관리자」로 옮겨가 남은 항목이 없다. 그룹 헤더만 덩그러니 남으면
    //   누를 것이 없는 자리가 되므로 `visible.length === 0` 으로 통째로 빠지는 것이 맞다.
    //   ⚠ 관리자 시야로 확인한다 — 가장 많이 보이는 시야에서도 없어야 「사라졌다」가 성립한다
    //     (검수자 시야에서는 관리자 항목이 안 보여 이 단언이 공허해진다).
    setRole('ADMIN');
    renderWithProviders(<Lnb />);

    expect(screen.queryByText('업로드')).toBeNull();
  });

  it('산출물_가져오기는_관리자_그룹으로_옮겨갔고_구_주소를_쓰지_않는다', () => {
    // 옮긴 것이지 복제한 것이 아니다 — 두 자리에 다 뜨면 어느 쪽이 정본인지 알 수 없다.
    setRole('ADMIN');
    renderWithProviders(<Lnb />);

    const link = screen.getByRole('link', { name: '산출물 가져오기' });
    expect(link).toHaveAttribute('href', '/admin/imports');

    const adminGroup = screen.getByText('관리자').parentElement?.parentElement;
    expect(
      within(adminGroup as HTMLElement).getByRole('link', { name: '산출물 가져오기' }),
    ).toBe(link);
  });

  it('검수자에게는_산출물_가져오기가_보이지_않는다', () => {
    // ★서버가 적재 실행·대응 저장/삭제를 관리자 전용으로 좁혔다. 화면을 열어 폴더 탐색·검사까지
    //   진행한 뒤 마지막 단계에서만 403 을 받는 것이 이번에 고친 상태이므로, 진입 자체가 없어야 한다.
    setRole('REVIEWER');
    renderWithProviders(<Lnb />);

    expect(screen.queryByRole('link', { name: '산출물 가져오기' })).toBeNull();
    expect(document.querySelector('a[href="/admin/imports"]')).toBeNull();
    // 구 주소도 남아 있지 않다(이동이지 복제가 아니다).
    expect(document.querySelector('a[href="/manage/imports"]')).toBeNull();
  });

  it('관리자_그룹이_NAV_001_순서대로_여섯_항목을_갖는다', () => {
    // vitest 는 DEV 빌드라 isDevUploadEnabled() 가 true → 실제 MENU 에 파일 업로드가 등록된다.
    // 순서는 NAV-001 이 정한다 — 파일 업로드는 끝에 붙는 것이 아니라 패스워드 교체 **앞**이다.
    // ⚠ 이 그룹은 관리자에게만 보인다 — 검수자로 두면 그룹이 없어 순서 확인 자체가 성립하지 않는다.
    setRole('ADMIN');
    renderWithProviders(<Lnb />);
    const adminGroup = screen.getByText('관리자').parentElement?.parentElement;
    expect(adminGroup).toBeTruthy();

    const links = within(adminGroup as HTMLElement).getAllByRole('link');
    expect(links.map((a) => a.textContent)).toEqual([
      '사용자 관리',
      '연동 서버 주소',
      '산출물 가져오기',
      '파일 업로드',
      '패스워드 교체',
      '위험 액션',
    ]);
    expect(links.map((a) => a.getAttribute('href'))).toEqual([
      '/admin/users',
      '/admin/endpoints',
      '/admin/imports',
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

  it('게시판_다음이_관리이고_관리자_그룹이_맨_뒤다', () => {
    // 그룹 순서는 NAV-001 이 정한다 — 검수자가 쓰는 「관리」 다음에 관리자 전용 「관리자」를 둔다.
    // ⚠ 전체 순서를 보려면 모든 그룹이 보이는 관리자로 렌더해야 한다.
    // ⚠ 구 단언 폐기 — 「업로드」가 게시판과 관리 사이에 있었으나 그 그룹이 없어졌다.
    setRole('ADMIN');
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
      '관리',
      '관리자',
    ]);
    expect(groupHeaders).not.toContain('업로드');
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

  it('WORKER_에게는_관리와_관리자_그룹이_통째로_보이지_않는다', () => {
    // 항목이 전부 그 자리보다 넓은 역할을 요구해 visible.length === 0 으로 헤더까지 사라진다.
    setRole('WORKER');
    renderWithProviders(<Lnb />);

    expect(screen.queryByText('관리')).toBeNull();
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
    // 항목이 가장 많은 시야(관리자)로 돌린다 — 중복 등록은 「관리자」 그룹에서 일어난다.
    setRole('ADMIN');
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
    setRole('WORKER');
    renderWithProviders(<Lnb />);

    expect(screen.queryByRole('link', { name: '이벤트유형 관리' })).toBeNull();
  });

  it('REVIEWER_렌더_시_관리_그룹과_비식별_신고_메뉴가_중복_없이_표시된다', () => {
    renderWithProviders(<Lnb />);
    expect(screen.getAllByText('관리')).toHaveLength(1);
    expect(screen.getAllByRole('link', { name: '비식별 신고' })).toHaveLength(1);
  });
});
