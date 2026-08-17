import { NavLink } from 'react-router-dom';

import { cn } from '@/lib/cn';
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
    ],
  },
];

const DEV_TOOLS_GROUP = '개발 도구';

/**
 * [개발/검수 전용] DEV 빌드에서만 노출되는 도구 메뉴를 MENU 에 멱등 등록한다.
 *
 * MENU 는 모듈 스코프 가변 배열이라 Vite HMR 로 본 모듈이 재평가될 때마다 무조건 push 하면
 * '개발 도구' 그룹이 중복 누적되어 렌더 시 React "two children with the same key" 경고가 발생한다.
 * 이미 등록되어 있으면 다시 넣지 않도록 그룹 존재 여부를 가드한다(멱등).
 */
export function registerDevToolsMenu(menu: MenuGroup[]): void {
  if (menu.some((g) => g.group === DEV_TOOLS_GROUP)) return;
  menu.push({
    group: DEV_TOOLS_GROUP,
    items: [
      { label: '영상 업로드', path: '/dev/upload', allow: ['REVIEWER'] },
    ],
  });
}

// `import.meta.env.DEV` 는 빌드 시 상수로 치환되므로 prd 산출물에서는 dead-code 로 제거된다.
if (import.meta.env.DEV) {
  registerDevToolsMenu(MENU);
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
                <span className="text-[10px] font-semibold uppercase tracking-widest text-gray-400">
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
