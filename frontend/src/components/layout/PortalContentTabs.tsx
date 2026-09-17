import { Link, useLocation } from 'react-router-dom';

import { cn } from '@/lib/cn';
import { KRDS_FOCUS } from '@/lib/focusRing';
import { PORTAL_CONTENT_TABS, resolveActivePortalTab } from '@/lib/portalNav';
import { portalShellAlign, portalTabsTopPadding } from '@/lib/portalShellLayout';

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
 * <h3>모양 규격 — DS-002(포털 채널) 축</h3>
 * `DS-002` 등재 사유가 정확히 이 부품이다 — *"이동 탭의 밑줄 굵기·글자 굵기·항목 간격·
 * 구분선 유무 넷이 상대 규격과 달랐고"*. 그 넷이 아래다.
 *
 * <ul>
 *   <li><b>밑줄 굵기</b> 4px (구 2px)</li>
 *   <li><b>글자 굵기</b> 활성·비활성 모두 600 (구 500 / 400 — 굵기로 활성을 말하지 않는다)</li>
 *   <li><b>항목 간격</b> 8 (구 0 — 항목이 붙어 있었다)</li>
 *   <li><b>구분선</b> ★<b>없다</b> — 바 전체 밑줄을 두지 않는다 (구 `border-b`)</li>
 * </ul>
 *
 * 나머지 규격: 높이 48 고정 · 최소너비 64 · 좌우 패딩 4 · 15px · 비활성 slate-500 ·
 * hover 는 색만(slate-800) · 활성 글자 primary-600 · 활성 밑줄 primary-500 · 배경 항상 투명 ·
 * 가로 정렬선은 본문과 «같은 한 곳»(`@/lib/portalShellLayout`)에서 받는다 — 독립 앱은
 * 최대폭 1200 + 좌우 거터 24, 임베드는 Host 슬롯 안쪽 폭을 그대로 채운다(그쪽이 이미 자기
 * 여백을 갖고 있어 한 번 더 세우면 겹친다).
 *
 * ⚠ **높이 48 은 구 44 보다 크다** — 터치 표적이 넓어지는 방향이라 접근성 후퇴가 아니다.
 *   (반대 방향, 즉 44 아래로 내리는 축은 별도 판단 대상이다.)
 *
 * ⚠ **이 부품의 치수는 `DS-002` ITEM 이 아니라 시안(SCREEN-028)의 상대 실측 기록에서 왔다.**
 *   ITEM 의 `nav-link` step(높이 40 · 좌우 14 · 라운드 8 · 밑줄 없음)은 **머리 영역 GNB 와
 *   좌측 메뉴 1단**을 규정한 것이고 본문 상단 line 탭이 아니다. `DS-002.known_gaps` 가
 *   *"부품 카탈로그가 함께 오지 않았다 — 값만 옮겼고 상대의 부품 정의는 우리 쪽에 없다"* 고
 *   인정한 공백이며, `iteration_guide` 가 그럴 때 *"가장 가까운 상대 화면의 짜임을 먼저 찾아
 *   그 규격을 따른다"* 고 지시한다. 상대가 부품 정의를 주면 그것으로 교체한다.
 *
 * @design SHELL-002
 * @design NAV-002
 * @design DS-002
 */
export function PortalContentTabs() {
  const { pathname } = useLocation();
  const active = resolveActivePortalTab(pathname);

  // 목적지가 아니면 아무것도 그리지 않는다 — 몰입 편집 화면이 여기로 떨어진다.
  if (!active) return null;

  return (
    // ★윗 여백은 임베드에서만 붙는다 — Host 가 마운트 슬롯 여백을 0 으로 만들어(계약 전문은
    //   `lib/portalShellLayout`) 탭이 카드 윗변에 그대로 붙는다. Host 자신의 저작도구 화면도
    //   같은 자리에 24 를 둔다. 독립 앱에는 위에 머리 영역이 있어 주지 않는다.
    <nav aria-label="포털 이동 탭" className={cn(portalShellAlign(), portalTabsTopPadding())}>
      {/*
        ★**KRDS 탭의 생김새를 그대로 쓴다** (2026-09-16 사용자 지적 — 「탭 컴포넌트가 좀 다른거
          같지않니」). 종전에는 같은 모양을 Tailwind 로 손수 그렸는데, 밑줄 굵기·활성 글자색이
          부모 포털과 갈려 있었다. 이제 킷 클래스를 입어 **토큰이 값을 정한다** — 우리 포털
          테마가 `--krds-tab--line-button-color-text-active` 를 부모 포털과 같은 값으로 덮어
          두었으므로 색·굵기가 저절로 맞는다(손으로 옮겨 적은 값은 테마가 바뀌어도 안 따라온다).

        ★**부품(`Tab`/`TabTrigger`)을 쓰지 않고 «클래스»만 입는다 — 이것은 탭이 아니라 이동이다.**
          킷 `TabTrigger` 는 `<button>` 이라 가운데 클릭 · 새 탭 · 주소 복사가 사라지고, ARIA 의
          `tablist`/`tab` 은 **같은 문서 안의 tabpanel** 을 전제한다. 여기는 서로 다른 주소로
          가는 길이라 그 역할을 쓰면 보조기술에 거짓말이 된다. 그래서 생김새는 킷, 의미는
          내비게이션(`nav` + `aria-current`)이다.
          ⚠ `role="tablist"`·`role="tab"` 을 붙이지 말 것.
      */}
      <div className="krds-tab-area">
        <div className="tab line">
          <ul>
            {PORTAL_CONTENT_TABS.map((tab) => {
              const isActive = tab.key === active.key;
              return (
                <li key={tab.key} className={cn('tab-item', isActive && 'active')}>
                  <Link
                    to={tab.path}
                    aria-current={isActive ? 'page' : undefined}
                    className={cn('btn-tab', KRDS_FOCUS)}
                  >
                    {tab.label}
                    {/* ★킷은 여기에 화면 밖 글(「선택됨」)을 두지만 **우리는 두지 않는다.**
                        킷 탭은 `aria-selected` 로 말하는 «탭»이고, 이쪽은 `aria-current="page"`
                        로 말하는 «이동»이다 — 보조기술이 이미 「현재 페이지」로 읽어 주므로 글을
                        더하면 링크 이름이 「내 작업 선택됨」이 되어 같은 사실이 두 번 들린다
                        (기존 가드가 이름이 바뀐 것을 잡았다). */}
                  </Link>
                </li>
              );
            })}
          </ul>
        </div>
      </div>
    </nav>
  );
}
