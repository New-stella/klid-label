import { describe, expect, it } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';

import { PORTAL_CONTENT_TABS } from '@/lib/portalNav';

import { PortalContentTabs } from '../PortalContentTabs';

/**
 * 포털 본문 상단 이동 탭 — Host 가 머리 영역과 좌측 주 메뉴를 소유하므로 목적지 이동은 본문
 * 상단 탭이 맡는다. [@design SHELL-002] [@design NAV-002]
 *
 * ★값 축을 고정한다 — 「탭이 셋이다」 같은 형식 단언만 두면 라벨·경로가 서로 뒤바뀌어도 통과한다.
 *   그래서 (라벨, 경로) 쌍을 **확정값 그대로** 못 박는다. 선언 상수와 비교하면 방금 내가 쓴 값을
 *   되읽는 것이라 회귀를 못 잡는다.
 */
const CONFIRMED_TABS: ReadonlyArray<readonly [string, string]> = [
  ['내 작업', '/portal'],
  ['내 업로드', '/portal/uploads'],
  ['증강', '/portal/augment'],
];

function renderAt(pathname: string) {
  return render(
    <MemoryRouter initialEntries={[pathname]}>
      <Routes>
        <Route path="*" element={<PortalContentTabs />} />
      </Routes>
    </MemoryRouter>,
  );
}

function tabNav() {
  return screen.queryByRole('navigation', { name: '포털 이동 탭' });
}

describe('PortalContentTabs — 포털 본문 상단 이동 탭', () => {
  it('목적지_셋이_확정된_라벨과_경로로_뜬다', () => {
    renderAt('/portal');

    const nav = tabNav();
    expect(nav).not.toBeNull();

    const links = within(nav as HTMLElement).getAllByRole('link');
    expect(links.map((a) => a.textContent)).toEqual(CONFIRMED_TABS.map(([label]) => label));
    expect(links.map((a) => a.getAttribute('href'))).toEqual(CONFIRMED_TABS.map(([, p]) => p));
  });

  it('선언_상수가_그_확정값을_담는다_화면은_목록을_다시_나열하지_않는다', () => {
    // 화면이 자기 목록을 갖지 않고 이 선언에서만 파생한다는 것이 사양이다. 선언이 흔들리면
    // 위 렌더 케이스가 함께 빨개진다 — 두 케이스는 서로를 대체하지 않는다.
    expect(PORTAL_CONTENT_TABS.map((t) => [t.label, t.path])).toEqual(
      CONFIRMED_TABS.map(([label, p]) => [label, p]),
    );
  });

  it.each(CONFIRMED_TABS.map(([label, p]) => ({ label, p })))(
    '$p 에서는 $label 탭이 현재 위치로 표시된다',
    ({ label, p }) => {
      renderAt(p);

      const nav = tabNav() as HTMLElement;
      const current = within(nav).getByRole('link', { current: 'page' });
      expect(current).toHaveTextContent(label);
      // 현재 위치 표시는 하나뿐이다 — 여럿이면 접두 일치로 새어 나온 것이다.
      expect(within(nav).getAllByRole('link').filter((a) => a.getAttribute('aria-current'))).toHaveLength(1);
    },
  );

  it('끝에_슬래시가_붙어도_같은_자리로_본다', () => {
    renderAt('/portal/uploads/');

    const nav = tabNav() as HTMLElement;
    expect(within(nav).getByRole('link', { current: 'page' })).toHaveTextContent('내 업로드');
  });

  /*
   * ★몰입 편집 화면 미노출 가드 (수용 기준 2).
   *
   * 두 화면은 목적지가 아니라 목록에서 행을 눌러 들어가는 자리다 — 라벨링은 편집 중 이탈을
   * 부르고, 마킹은 특정 자산에 매인 화면이라 영상 재생이 세로를 차지한다.
   *
   * ⚠ 판정은 **허용 목록**이라 이 경로들이 선언 어디에도 없다. 그래서 이 케이스는 「빠뜨린
   *   경로가 없다」가 아니라 「접두 일치로 새어 나오지 않는다」를 지킨다.
   */
  it.each([
    ['포털 라벨링', '/portal/label/42'],
    ['업로드 영상 마킹', '/portal/uploads/7/marking'],
    // 폐기된 목적지 — 포털 라벨링 화면으로 합쳐진다(별도 단계). 여기서는 탭에 넣지 않는 것까지만 지킨다.
    ['업로드 자산 라벨링(폐기된 목적지)', '/portal/uploads/7/label'],
  ])('%s 화면에서는 이동 탭이 뜨지 않는다', (_name, pathname) => {
    renderAt(pathname);

    expect(tabNav()).toBeNull();
  });

  /*
   * ★표시는 탭이지만 탭 위젯이 아니다 — 다른 주소로 가므로 `role="tab"`/`tablist` 를 쓰지 않는다.
   *   보조기술이 「탭 3개 중 1번」으로 읽으면 실제 동작(페이지 이동)과 어긋난다.
   */
  it('탭_위젯_역할을_쓰지_않고_이동_링크로_만든다', () => {
    renderAt('/portal');

    expect(screen.queryAllByRole('tab')).toHaveLength(0);
    expect(screen.queryByRole('tablist')).toBeNull();
    expect(screen.getAllByRole('link')).toHaveLength(CONFIRMED_TABS.length);
  });
});
