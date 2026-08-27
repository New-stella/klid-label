import { NavLink } from 'react-router-dom';

import { cn } from '@/lib/cn';
import { isDevUploadEnabled } from '@/lib/devUpload';
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
 * 그룹 순서: 대시보드 / 영상 / 작업 / 데이터 / 통계 / 게시판 / 업로드 / 관리. [@design NAV-001]
 *
 * 「업로드」는 데이터를 들여오는 화면만 모은 자리다 — 서버에 이미 있는 폴더를 가져오는 길
 * (산출물 가져오기)과 내려받아 둔 파일을 올리는 길(파일 업로드)이라는 **방식 차이**로 나뉜다.
 * 둘 다 설정을 다루는 화면이 아니라 「관리」에 있을 때 찾기 어려웠고, 서로 짝이라는 사실도
 * 메뉴에 드러나지 않았다.
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
    // [@design NAV-001] [@design SCREEN-039] [@design SCREEN-027]
    // 「업로드」는 게시판과 관리 **사이**다. 항목이 둘 다 REVIEWER 전용이라 WORKER 에게는
    // `visible.length === 0` 으로 그룹째 사라진다(그룹 헤더만 남는 일이 없다).
    group: '업로드',
    items: [
      // ⚠ `allow` 는 라우트 가드(internalReviewerOnly)와 <b>같은 조건</b>이어야 한다 — 갈리면
      //   「메뉴는 없는데 주소로는 들어가진다」(또는 그 반대)가 된다.
      { label: '산출물 가져오기', path: '/manage/imports', allow: ['REVIEWER'] },
      // 「파일 업로드」(`/dev/upload`)는 아래 registerManualUploadMenu 가 토글 조건과 함께 넣는다.
    ],
  },
  {
    group: '관리',
    items: [
      { label: '사용자 관리', path: '/manage/users', allow: ['REVIEWER'] },
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
];

const UPLOAD_GROUP = '업로드';
const MANUAL_UPLOAD_PATH = '/dev/upload';

/**
 * 파일 업로드(`/dev/upload`)를 「업로드」 그룹 끝에 멱등 등록한다. [@design SCREEN-027] [@design NAV-001]
 *
 * 노출 판정은 라우트(`router/index.tsx`)와 **같은 `isDevUploadEnabled()`** 를 쓴다. 두 판정이
 * 갈리면 «메뉴는 없는데 URL 로는 들어가진다»(또는 그 반대)는 비대칭이 생긴다 — 실제로 이 메뉴가
 * `import.meta.env.DEV` 로만 가려져 있어, 토글을 켠 운영 산출물에서 라우트는 사는데 메뉴만 통째로
 * dead-code 제거된 적이 있다.
 *
 * MENU 는 모듈 스코프 가변 배열이라 Vite HMR 로 본 모듈이 재평가될 때마다 무조건 push 하면 같은
 * 항목이 중복 누적되어 렌더 시 React "two children with the same key"(key=i.path) 경고가 발생한다.
 * 이미 있으면 다시 넣지 않도록 경로 존재 여부를 가드한다(멱등).
 *
 * 「업로드」 그룹이 없으면 아무것도 하지 않는다 — 엉뚱한 자리에 그룹을 새로 만드는 것보다 메뉴가
 * 없는 편이 안전하다. 실제 MENU 에 그룹이 있는지는 Lnb 렌더 테스트가 고정한다.
 *
 * ⚠ 토글이 꺼진 산출물에서는 이 항목이 통째로 빠지지만 「업로드」 그룹 자체는 남는다 —
 *   산출물 가져오기가 MENU 배열에 직접 들어 있기 때문이다(그룹이 사라지지 않는다).
 */
export function registerManualUploadMenu(menu: MenuGroup[]): void {
  const upload = menu.find((g) => g.group === UPLOAD_GROUP);
  if (!upload) return;
  if (upload.items.some((i) => i.path === MANUAL_UPLOAD_PATH)) return;
  upload.items.push({ label: '파일 업로드', path: MANUAL_UPLOAD_PATH, allow: ['REVIEWER'] });
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
          const visible = g.items.filter((i) => !role || i.allow.includes(role));
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
