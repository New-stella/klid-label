import { NavLink } from 'react-router-dom';

import { cn } from '@/lib/cn';
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
      { label: '영상 처리 현황', path: '/video/completed', allow: ['REVIEWER', 'WORKER'] },
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
      { label: '내보내기', path: '/export', allow: ['REVIEWER'] },
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
    group: '관리',
    items: [
      { label: '사용자 관리', path: '/manage/users', allow: ['REVIEWER'] },
      { label: '시스템 설정', path: '/manage/settings', allow: ['REVIEWER'] },
      { label: '프리셋 관리', path: '/manage/presets', allow: ['REVIEWER'] },
    ],
  },
];

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
                      'flex items-center mx-2 px-3 py-2 rounded-md text-sm font-medium transition-colors',
                      isActive
                        ? 'border-l-2 border-primary-500 bg-primary-50 pl-[10px] text-primary-700'
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
