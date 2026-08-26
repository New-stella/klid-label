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
 * mock 정합 — 그룹 순서: 대시보드 / 영상 / 작업 / 데이터 / 통계 / 관리.
 * 라벨/경로는 mock routes.ts와 정렬.
 */
const MENU: MenuGroup[] = [
  {
    group: '대시보드',
    items: [{ label: '대시보드', path: '/dashboard', allow: ['REVIEWER', 'WORKER'] }],
  },
  {
    group: '영상',
    items: [
      { label: '영상 처리 현황', path: '/video/status', allow: ['REVIEWER', 'WORKER'] },
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
      { label: '외부 산출물 이관', path: '/manage/imports', allow: ['REVIEWER'] },
    ],
  },
];

const MANAGE_GROUP = '관리';
const MANUAL_UPLOAD_PATH = '/dev/upload';

/**
 * 수동 업로드(`/dev/upload`)를 「관리」 그룹 끝에 멱등 등록한다. [@design SCREEN-027]
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
 * 「관리」 그룹이 없으면 아무것도 하지 않는다 — 엉뚱한 자리에 그룹을 새로 만드는 것보다 메뉴가
 * 없는 편이 안전하다. 실제 MENU 에 그룹이 있는지는 Lnb 렌더 테스트가 고정한다.
 */
export function registerManualUploadMenu(menu: MenuGroup[]): void {
  const manage = menu.find((g) => g.group === MANAGE_GROUP);
  if (!manage) return;
  if (manage.items.some((i) => i.path === MANUAL_UPLOAD_PATH)) return;
  manage.items.push({ label: '수동 업로드', path: MANUAL_UPLOAD_PATH, allow: ['REVIEWER'] });
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
