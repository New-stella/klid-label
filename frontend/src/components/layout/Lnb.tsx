import { NavLink } from 'react-router-dom';

import { cn } from '@/lib/cn';
import { isDevUploadEnabled } from '@/lib/devUpload';
import { roleSatisfiesAny } from '@/lib/authz';
import { KRDS_FOCUS } from '@/lib/focusRing';
import { useAuthStore } from '@/stores/useAuthStore';
import type { Role } from '@/lib/api/types';

interface MenuItem {
  label: string;
  path: string;
  allow: Role[];
}

interface MenuGroup {
  group: string;
  items: MenuItem[];
}

/**
 * 그룹 순서: 대시보드 / 영상 / 작업 / 데이터 / 통계 / 게시판 / 관리 / 관리자. [@design NAV-001]
 *
 * ⚠ **「업로드」 그룹은 없어졌다.** 데이터를 들여오는 두 화면(산출물 가져오기 · 파일 업로드)이
 *   모두 「관리자」 그룹으로 옮겨가 항목이 하나도 남지 않았기 때문이다. 구 서술 폐기 —
 *   *"「업로드」는 게시판과 관리 사이이고 산출물 가져오기가 그 자리에 있다"*. 되살리지 말 것.
 *
 * 「관리자」는 관리자 역할에게만 열리는 화면들의 자리다. 검수자에게는 이 그룹이 통째로 보이지
 * 않는다 — 항목이 전부 관리자 전용이라 `visible.length === 0` 으로 헤더까지 사라진다.
 * ★**가르는 축은 역할이다.** 예전에는 이 항목들이 검수자에게도 보이고 진입 시점에 관리자
 *   패스워드로 걸렀는데, 관리자 역할이 생기면서 노출 단계에서 가를 수 있게 됐다. 유효창은
 *   없어지지 않고 역할 **위에** 그대로 가산된다(라우트 안쪽 게이트).
 * ★**유효창을 메뉴 노출 조건으로 쓰지 않는다.** 결론은 그대로다 — 유효창은 관리자 페이지에
 *   들어가 패스워드를 넣어야 열리는데 그것을 노출 조건으로 삼으면 **들어갈 길 자체가 사라진다.**
 *   바뀐 것은 「무엇으로 가르는가」이지 「유효창으로 가르지 않는다」가 아니다.
 * ★**진입 화면(`/admin`)은 메뉴에 두지 않는다** — 눌러서 가는 곳이 아니라 유효창이 없을 때
 *   대신 열리는 자리다.
 */
const MENU: MenuGroup[] = [
  {
    group: '대시보드',
    items: [{ label: '대시보드', path: '/dashboard', allow: ['REVIEWER', 'WORKER'] }],
  },
  {
    // [@design NAV-001] [@design SCREEN-008] [@design SCREEN-009]
    // 「영상 처리 현황」과 그 하위 영상 상세는 배치 처리 상태를 보는 데 그치지 않고 재시도·
    // 건너뛰기·재수행 같은 **운영 조치**를 제공하는 자리라 REVIEWER 전용이다 — 파이프라인을
    // 다시 돌리거나 단계를 건너뛰게 하는 것은 라벨 수정·검수 제출을 맡는 WORKER 의 역할 축이
    // 아니다. 항목이 이 하나뿐이라 WORKER 에게는 `visible.length === 0` 으로 그룹째 사라진다
    // (그룹 헤더만 남는 일이 없다).
    // ★그룹 노드 자체에 역할을 걸지 않는다 — 이 그룹의 개념 범위에는 마킹(`/marking/:rawSn`)도
    //   들어가고 그건 WORKER 가 진입하는 화면이다(LNB 미노출 진입 맥락이라 이 배열엔 없다).
    //   그룹에 걸면 나중에 WORKER 용 항목이 늘 때 통째로 막힌다.
    // ⚠ `allow` 는 라우트 가드(`router/index.tsx` 의 video/status·video/:id → internalReviewerOnly)와
    //   <b>같은 조건</b>이어야 한다 — 갈리면 「메뉴는 없는데 주소로는 들어가진다」(또는 그 반대)가 된다.
    group: '영상',
    items: [
      { label: '영상 처리 현황', path: '/video/status', allow: ['REVIEWER'] },
    ],
  },
  {
    group: '작업',
    items: [
      { label: '작업 목록', path: '/task', allow: ['REVIEWER', 'WORKER'] },
      { label: '검수 목록', path: '/review/pending', allow: ['REVIEWER'] },
    ],
  },
  {
    group: '데이터',
    items: [
      { label: '증강 요청', path: '/augment/request', allow: ['REVIEWER'] },
    ],
  },
  {
    group: '통계',
    items: [
      { label: '작업자 통계', path: '/stat/worker', allow: ['REVIEWER', 'WORKER'] },
      { label: '전체 구축 현황', path: '/stat/overall', allow: ['REVIEWER'] },
    ],
  },
  {
    group: '게시판',
    items: [{ label: '게시판', path: '/notice', allow: ['REVIEWER', 'WORKER'] }],
  },
  {
    // ⚠ 「사용자 관리」는 이 그룹에서 **관리자 그룹으로 옮겨갔다**(`/admin/users`) — 역할을 바꾸는
    //   일이라 관리자 패스워드 확인을 함께 요구한다. 삭제가 아니라 이동이다.
    group: '관리',
    items: [
      { label: '시스템 설정', path: '/manage/settings', allow: ['REVIEWER'] },
      { label: '라벨 관리', path: '/manage/labels', allow: ['REVIEWER'] },
      { label: '프리셋 관리', path: '/manage/presets', allow: ['REVIEWER'] },
      { label: '비식별 신고', path: '/manage/deident-reports', allow: ['REVIEWER'] },
      // [@design NAV-001] [@design SCREEN-038] 라우트(`router/index.tsx` 의 manage/event-types)는
      //   진작 REVIEWER 전용으로 등록돼 있었는데 이 배열에만 빠져 있어, 주소를 직접 입력하지 않으면
      //   도달할 수 없었다(도달 경로 0). 설계에는 이미 있던 항목이라 순수 구현 드리프트다.
      //   ⚠ `allow` 는 라우트 가드(internalReviewerOnly)와 <b>같은 조건</b>이어야 한다 — 갈리면
      //     「메뉴는 없는데 주소로는 들어가진다」(또는 그 반대)가 된다.
      { label: '이벤트유형 관리', path: '/manage/event-types', allow: ['REVIEWER'] },
    ],
  },
  {
    // [@design NAV-001] [@design SCREEN-024] [@design SCREEN-041] [@design SCREEN-042]
    // [@design SCREEN-043] [@design ADR-046] [@design ROLE-004]
    // 관리자 페이지 — 관리자 역할 **위에** 관리자 패스워드 유효창이 가산되는 화면들이다.
    // ★`allow` 가 ADMIN 이라 검수자에게는 이 그룹이 통째로 보이지 않는다. 관리자는 계층으로
    //   검수자 권한을 물려받으므로 검수·배정 메뉴도 함께 보인다(그쪽 항목은 REVIEWER 그대로).
    // ★유효창 보유 여부를 노출 조건으로 쓰지 않는다(그러면 들어갈 길이 사라진다).
    // ★진입 화면(`/admin`)은 여기에 두지 않는다.
    // ⚠ `allow` 는 라우트 가드(internalAdminOnly)와 <b>같은 조건</b>이어야 한다.
    group: '관리자',
    items: [
      { label: '사용자 관리', path: '/admin/users', allow: ['ADMIN'] },
      { label: '연동 서버 주소', path: '/admin/endpoints', allow: ['ADMIN'] },
      // ⚠ 「산출물 가져오기」가 **「업로드」 그룹에서 여기로 옮겨왔다**(`/manage/imports` →
      //   `/admin/imports`). 서버가 적재 실행·대응 저장/삭제를 관리자 전용으로 좁혀, 검수자가
      //   화면을 열어 폴더 탐색·검사까지 마친 뒤 마지막 단계에서만 거부되던 상태였다.
      //   ★그 결과 「업로드」 그룹은 항목이 비어 **통째로 사라졌다** — 삭제가 아니라 이동이다.
      { label: '산출물 가져오기', path: '/admin/imports', allow: ['ADMIN'] },
      // 「파일 업로드」(`/admin/uploads`)는 아래 registerManualUploadMenu 가 토글 조건과 함께 넣는다.
      { label: '패스워드 교체', path: '/admin/password', allow: ['ADMIN'] },
      { label: '위험 액션', path: '/admin/maintenance', allow: ['ADMIN'] },
    ],
  },
];

const ADMIN_GROUP = '관리자';
const MANUAL_UPLOAD_PATH = '/admin/uploads';
/** 파일 업로드가 놓일 자리 — 이 항목 **바로 앞**에 끼운다(NAV-001 순서). */
const MANUAL_UPLOAD_ANCHOR_PATH = '/admin/password';

/**
 * 파일 업로드(`/admin/uploads`)를 「관리자」 그룹에 멱등 등록한다. [@design SCREEN-027] [@design NAV-001]
 *
 * 노출 판정은 라우트(`router/index.tsx`)와 **같은 `isDevUploadEnabled()`** 를 쓴다. 두 판정이
 * 갈리면 «메뉴는 없는데 URL 로는 들어가진다»(또는 그 반대)는 비대칭이 생긴다 — 실제로 이 메뉴가
 * `import.meta.env.DEV` 로만 가려져 있어, 토글을 켠 운영 산출물에서 라우트는 사는데 메뉴만 통째로
 * dead-code 제거된 적이 있다.
 *
 * ⚠ **대상 그룹이 「업로드」에서 「관리자」로 바뀌었다.** 이 화면은 관리자 패스워드 확인을 거쳐야
 *   도달하므로 「업로드」 그룹의 다른 항목(산출물 가져오기)과 요구 조건이 다르다.
 *
 * 끝에 붙이지 않고 **패스워드 교체 앞에** 끼우는 것은 NAV-001 이 정한 순서(사용자 관리 · 연동
 * 서버 주소 · 파일 업로드 · 패스워드 교체 · 위험 액션)를 지키기 위해서다. 그 기준 항목이 없으면
 * 끝에 붙인다 — 순서가 어긋나도 메뉴가 사라지는 것보다 낫다.
 *
 * MENU 는 모듈 스코프 가변 배열이라 Vite HMR 로 본 모듈이 재평가될 때마다 무조건 넣으면 같은
 * 항목이 중복 누적되어 렌더 시 React "two children with the same key"(key=i.path) 경고가 발생한다.
 * 이미 있으면 다시 넣지 않도록 경로 존재 여부를 가드한다(멱등).
 *
 * 「관리자」 그룹이 없으면 아무것도 하지 않는다 — 엉뚱한 자리에 그룹을 새로 만드는 것보다 메뉴가
 * 없는 편이 안전하다. 실제 MENU 에 그룹이 있는지는 Lnb 렌더 테스트가 고정한다.
 *
 * ⚠ 토글이 꺼진 산출물에서는 이 항목이 통째로 빠지지만 「관리자」 그룹 자체는 남는다 —
 *   나머지 네 항목이 MENU 배열에 직접 들어 있기 때문이다(그룹이 사라지지 않는다).
 */
export function registerManualUploadMenu(menu: MenuGroup[]): void {
  const admin = menu.find((g) => g.group === ADMIN_GROUP);
  if (!admin) return;
  if (admin.items.some((i) => i.path === MANUAL_UPLOAD_PATH)) return;
  const item: MenuItem = {
    label: '파일 업로드',
    path: MANUAL_UPLOAD_PATH,
    // 「관리자」 그룹의 다른 항목과 같은 조건이다 — 한 그룹 안에서 항목마다 조건이 갈리면
    // 그룹이 반쪽만 보이는 상태가 생긴다.
    allow: ['ADMIN'],
  };
  const anchor = admin.items.findIndex((i) => i.path === MANUAL_UPLOAD_ANCHOR_PATH);
  if (anchor === -1) {
    admin.items.push(item);
    return;
  }
  admin.items.splice(anchor, 0, item);
}

// 라우트와 같은 조건이고 빌드 시 상수로 접힌다 — 토글이 꺼진 산출물에서는 통째로 제거된다.
if (isDevUploadEnabled()) {
  registerManualUploadMenu(MENU);
}

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
