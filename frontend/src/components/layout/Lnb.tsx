import { NavLink } from 'react-router-dom';

import { cn } from '@/lib/cn';
import { isDevUploadEnabled } from '@/lib/devUpload';
import { roleSatisfiesAny } from '@/lib/authz';
import { KRDS_FOCUS } from '@/lib/focusRing';
import { buildMenuGroups } from '@/lib/routeAccess';
import { useAuthStore } from '@/stores/useAuthStore';

/**
 * 좌측 메뉴. 그룹 순서: 대시보드 / 영상 / 작업 / 데이터 / 통계 / 게시판 / 관리 / 관리자.
 * [@design NAV-001] [@design SHELL-001]
 *
 * ★**메뉴는 스스로 목록을 갖지 않는다.** 항목·경로·허용 역할은 전부 `@/lib/routeAccess` 의
 *   선언에서 파생한다 — 라우트 가드도 같은 선언을 읽는다. 예전에는 이 파일이 항목마다 `allow`
 *   를 들고 라우터가 따로 허용 목록을 걸어, 두 파일의 주석이 「같은 조건이어야 한다」고 서로를
 *   향해 경고만 하고 있었다. 갈리면 「메뉴는 없는데 주소로는 들어가진다」(또는 그 반대)가 된다.
 *   셸 사양도 같은 것을 금지한다 — 메뉴 구성은 셸이 정하지 않고 내비게이션 정의를 따른다.
 *
 * ⚠ **「업로드」 그룹은 없어졌다.** 데이터를 들여오는 두 화면(산출물 가져오기 · 파일 업로드)이
 *   모두 「관리자」 그룹으로 옮겨가 항목이 하나도 남지 않았기 때문이다. 되살리지 말 것.
 *
 * 「관리자」는 관리자 역할에게만 열리는 화면들의 자리다. 검수자에게는 이 그룹이 통째로 보이지
 * 않는다 — 항목이 전부 관리자 전용이라 `visible.length === 0` 으로 헤더까지 사라진다.
 * ★**가르는 축은 역할이다.** 유효창은 없어지지 않고 역할 **위에** 그대로 가산된다(라우트 안쪽
 *   게이트). **유효창을 메뉴 노출 조건으로 쓰지 않는다** — 유효창은 관리자 페이지에 들어가
 *   패스워드를 넣어야 열리는데 그것을 노출 조건으로 삼으면 **들어갈 길 자체가 사라진다.**
 * ★**진입 화면(`/admin`)은 메뉴에 두지 않는다** — 눌러서 가는 곳이 아니라 유효창이 없을 때
 *   대신 열리는 자리다(선언에 `menu` 없이 들어 있다).
 */

/**
 * 모듈 스코프 메뉴 — 선언에서 **한 번 파생**한다.
 *
 * 파일 업로드(`/admin/uploads`)의 노출 판정은 라우트(`router/index.tsx`)와 **같은
 * `isDevUploadEnabled()`** 를 쓴다. 두 판정이 갈리면 «메뉴는 없는데 URL 로는 들어가진다»(또는
 * 그 반대)는 비대칭이 생긴다 — 실제로 이 메뉴가 `import.meta.env.DEV` 로만 가려져 있어, 토글을
 * 켠 운영 산출물에서 라우트는 사는데 메뉴만 통째로 dead-code 제거된 적이 있다.
 *
 * ⚠ 토글이 꺼진 산출물에서는 이 항목이 통째로 빠지지만 「관리자」 그룹 자체는 남는다 — 나머지
 *   다섯 항목이 토글과 무관하게 선언에 들어 있기 때문이다(그룹이 사라지지 않는다).
 *
 * ★구 구현의 `registerManualUploadMenu` 는 없어졌다. 모듈 스코프 가변 배열에 항목을 밀어 넣는
 *   방식이라 Vite HMR 로 모듈이 재평가되면 같은 항목이 누적돼 React 중복 key 경고가 났고, 그것을
 *   막으려 멱등 가드를 따로 들고 있었다. 지금은 호출마다 새 배열을 만드는 순수 파생이라 그
 *   문제가 구조적으로 사라졌다 — 되살리지 말 것.
 */
const MENU = buildMenuGroups({ devUploadEnabled: isDevUploadEnabled() });

/**
 * mock 정합 — fixed top-14 left-0 w-60 LNB.
 * active 항목은 좌측 보더 + 배경 + 텍스트 톤 모두 강조.
 */
export function Lnb() {
  const role = useAuthStore((s) => s.claims?.role);

  return (
    <nav
      aria-label="좌측 메뉴"
      className="fixed top-14 left-0 bottom-0 w-60 bg-white border-r border-gray-200 overflow-y-auto z-30"
    >
      <div className="py-3">
        {MENU.map((g) => {
          // ★판정은 `@/lib/authz` 에 위임한다 — 허용 목록에 이름이 그대로 있는지만 보면 상위
          //   역할이 하위 역할 자리에서 빠진다(관리자에게 검수 메뉴가 사라지던 결함).
          //   역할 미부여는 아무것도 보여주지 않는다 — 라우트 가드가 그 상태를 역할 부여 화면으로
          //   보내므로, 그 사이에 전체 메뉴가 잠깐 펼쳐지는 것이 오히려 어긋난 화면이다.
          const visible = g.items.filter((i) => roleSatisfiesAny(role, i.allow));
          if (visible.length === 0) return null;
          return (
            <div key={g.group} className="mb-1">
              <div className="px-4 py-1.5">
                <span className="text-[10px] font-semibold uppercase tracking-widest text-gray-600">
                  {g.group}
                </span>
              </div>
              {visible.map((i) => (
                <NavLink
                  key={i.path}
                  to={i.path}
                  className={({ isActive }) =>
                    cn(
                      // LNB 메뉴 = 내비게이션 축 → ladder `nav-link`(17px/w500).
                      // 크기·weight 모두 구 `text-sm font-medium` 과 동일.
                      'flex items-center mx-2 px-3 py-2 rounded-md text-nav-link font-medium transition-colors',
                      KRDS_FOCUS,
                      isActive
                        ? 'border-l-2 border-primary-500 bg-primary-50 pl-2.5 text-primary-700'
                        : 'text-gray-600 hover:bg-gray-50 hover:text-gray-900',
                    )
                  }
                >
                  {i.label}
                </NavLink>
              ))}
            </div>
          );
        })}
      </div>
    </nav>
  );
}
