import { Link, useLocation } from 'react-router-dom';

import { cn } from '@/lib/cn';
import { KRDS_FOCUS } from '@/lib/focusRing';
import { PORTAL_CONTENT_TABS, resolveActivePortalTab } from '@/lib/portalNav';

/**
 * 포털 채널 본문 상단 이동 탭 — 셸 사양의 `portal-content-tabs` 영역.
 *
 * Host 가 머리 영역과 좌측 주 메뉴를 소유하므로 저작도구는 **본문만** 그린다. 목적지 셋은
 * 그 본문 맨 위의 가로 탭으로 오간다.
 *
 * ★**항목을 스스로 갖지 않는다** — 라벨·경로·순서는 전부 `@/lib/portalNav` 의 선언에서
 *   파생한다. 여기에 다시 나열하면 두 곳이 되어 한쪽만 갱신될 때 조용히 어긋난다.
 *
 * ★★**탭이면서 탭 위젯이 아니다.** 표시는 탭이지만 하는 일은 **다른 주소로의 이동**이라,
 *   `role="tab"`/`role="tabpanel"`(같은 화면 안에서 패널을 갈아 끼우는 위젯)을 쓰지 않고
 *   `<nav>` 안의 링크로 만든다 — ARIA 저작 관행이 「탭이 다른 페이지로 가면 링크를 쓰라」고
 *   규정한다. 보조기술이 「탭 3개 중 1번」이 아니라 「이동 링크」로 읽어야 실제 동작과 맞다.
 *   현재 위치는 `aria-current="page"` 로 알린다.
 *   ⚠ `NavLink` 의 자동 활성 판정을 쓰지 않는다 — 그 판정은 **끝 슬래시 한 글자를 다르게 본다**
 *     (`/portal/uploads/` 에서 탭은 뜨는데 현재 위치가 하나도 표시되지 않았다). 노출 판정과 현재
 *     위치 판정이 갈리면 서로 어긋난 상태가 생기므로 **한 함수**(`resolveActivePortalTab`)가 둘을
 *     함께 정한다.
 *   ⚠ 그래서 공용 `Tabs`(UI-009, `role="tablist"`)를 재사용하지 않는다 — 시각만 같고 축이 다르다.
 *
 * ⚠ 몰입 편집 화면(포털 라벨링 · 업로드 영상 마킹)에서는 **아무것도 그리지 않는다.**
 *   판정은 선언의 허용 목록이 한다 — 이 파일이 「뜨지 않을 곳」을 따로 들지 않는다.
 *
 * @design SHELL-002
 * @design NAV-002
 */
export function PortalContentTabs() {
  const { pathname } = useLocation();
  const active = resolveActivePortalTab(pathname);

  // 목적지가 아니면 아무것도 그리지 않는다 — 몰입 편집 화면이 여기로 떨어진다.
  if (!active) return null;

  return (
    <nav aria-label="포털 이동 탭" className="mb-6 border-b border-gray-200">
      <ul className="flex">
        {PORTAL_CONTENT_TABS.map((tab) => {
          const isActive = tab.key === active.key;
          return (
            <li key={tab.key}>
              <Link
                to={tab.path}
                aria-current={isActive ? 'page' : undefined}
                className={cn(
                  // 이동 탭 = 내비게이션 축 → ladder `nav-link`(17px). 최소 높이 44px(터치 표적).
                  'inline-flex min-h-11 items-center -mb-px border-b-2 px-4 py-2.5 text-nav-link transition-colors duration-100',
                  KRDS_FOCUS,
                  isActive
                    ? // 선택 탭은 밑줄이 말한다 — 굵기까지 올리면 강조가 이중이 된다(공용 Tabs
                      // 가로 variant 와 같은 판단).
                      'border-primary-500 font-medium text-primary-600'
                    : 'border-transparent font-normal text-gray-500 hover:border-gray-300 hover:text-gray-700',
                )}
              >
                {tab.label}
              </Link>
            </li>
          );
        })}
      </ul>
    </nav>
  );
}
