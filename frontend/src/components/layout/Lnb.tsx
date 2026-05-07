import { NavLink } from 'react-router-dom';
import { clsx } from 'clsx';

import { useAuthStore } from '@/stores/useAuthStore';
import { useUiStore } from '@/stores/useUiStore';
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

const MENU: MenuGroup[] = [
  {
    group: '영상',
    items: [
      { label: '영상 목록', path: '/video/completed', allow: ['REVIEWER', 'WORKER'] },
      { label: '처리 현황', path: '/video/status', allow: ['REVIEWER', 'WORKER'] },
    ],
  },
  {
    group: '작업 관리',
    items: [
      { label: '작업 목록', path: '/task', allow: ['REVIEWER', 'WORKER'] },
      { label: '작업 배정', path: '/task/assign', allow: ['REVIEWER'] },
    ],
  },
  {
    group: '검수',
    items: [{ label: '검수 대기', path: '/review', allow: ['REVIEWER'] }],
  },
  {
    group: '통계',
    items: [
      { label: '내 통계', path: '/stat', allow: ['REVIEWER', 'WORKER'] },
      { label: '전체 구축 현황', path: '/stat/overall', allow: ['REVIEWER'] },
    ],
  },
  {
    group: '데이터',
    items: [
      { label: '비식별화 결과', path: '/deident', allow: ['REVIEWER', 'WORKER'] },
      { label: '데이터 증강', path: '/augment', allow: ['REVIEWER'] },
      { label: '내보내기', path: '/export', allow: ['REVIEWER'] },
    ],
  },
  {
    group: '관리',
    items: [
      { label: '사용자 관리', path: '/manage/users', allow: ['REVIEWER'] },
      { label: '시스템 설정', path: '/manage/settings', allow: ['REVIEWER'] },
      { label: '라벨링 프리셋', path: '/manage/presets', allow: ['REVIEWER'] },
    ],
  },
];

export function Lnb() {
  const role = useAuthStore((s) => s.claims?.role);
  const open = useUiStore((s) => s.sidebarOpen);

  return (
    <aside
      aria-label="좌측 메뉴"
      className={clsx(
        'shrink-0 border-r border-border bg-white transition-[width] duration-100',
        open ? 'w-56' : 'w-0 overflow-hidden',
      )}
    >
      <nav className="flex flex-col gap-4 p-4">
        {MENU.map((g) => {
          const visible = g.items.filter((i) => !role || i.allow.includes(role));
          if (visible.length === 0) return null;
          return (
            <div key={g.group}>
              <p className="mb-1 text-sub font-semibold text-neutral">{g.group}</p>
              <ul className="flex flex-col gap-1">
                {visible.map((i) => (
                  <li key={i.path}>
                    <NavLink
                      to={i.path}
                      className={({ isActive }) =>
                        clsx(
                          'block rounded px-2 py-1 text-body transition-colors duration-100',
                          isActive
                            ? 'bg-bgLight font-medium text-primary'
                            : 'text-neutral hover:bg-bgLight',
                        )
                      }
                    >
                      {i.label}
                    </NavLink>
                  </li>
                ))}
              </ul>
            </div>
          );
        })}
      </nav>
    </aside>
  );
}
