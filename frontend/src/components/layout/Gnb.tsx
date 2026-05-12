import { Link } from 'react-router-dom';
import { Video } from 'lucide-react';

import { cn } from '@/lib/cn';
import { Role } from '@/lib/api/types';
import { useAuthStore } from '@/stores/useAuthStore';

const ROLE_LABEL: Record<string, string> = {
  REVIEWER: '검수자',
  WORKER: '작업자',
  PORTAL_USER: '포털',
};

const ROLE_COLOR: Record<string, string> = {
  REVIEWER: 'bg-cyan-100 text-cyan-700',
  WORKER: 'bg-blue-100 text-blue-700',
  PORTAL_USER: 'bg-emerald-100 text-emerald-700',
};

/**
 * mock 정합 GNB (h-14, fixed top).
 * 좌측: 로고 + 제목, 우측: 역할 라벨 + 사용자 아바타.
 *
 * SSO 채널이라 역할 변경은 불가 — mock의 RoleSwitcher 대신 read-only 표시.
 */
export function Gnb() {
  const claims = useAuthStore((s) => s.claims);
  const role = claims?.role ?? Role.WORKER;
  const name = claims?.name ?? '사용자';
  const initials = name.slice(0, 1);

  return (
    <header className="fixed top-0 left-0 right-0 z-40 h-14 bg-white border-b border-gray-200 flex items-center px-4">
      {/* Left: Logo */}
      <div className="flex items-center gap-2.5 w-60 shrink-0">
        <Link to="/dashboard" className="flex items-center gap-2.5">
          <span className="flex items-center justify-center w-8 h-8 rounded-lg bg-primary-600">
            <Video size={16} className="text-white" aria-hidden />
          </span>
          <span className="font-bold text-gray-900 text-sm leading-tight">
            학습데이터 저작도구
          </span>
        </Link>
      </div>

      {/* Spacer */}
      <div className="flex-1" />

      {/* Right: Role label + User */}
      <div className="flex items-center gap-3">
        <span
          className={cn(
            'inline-flex items-center px-2.5 py-1 rounded-full text-xs font-semibold',
            ROLE_COLOR[role] ?? 'bg-gray-100 text-gray-600',
          )}
        >
          {ROLE_LABEL[role] ?? role}
        </span>
        <div className="flex items-center gap-2 pl-3 border-l border-gray-200">
          <div className="flex items-center justify-center w-8 h-8 rounded-full bg-primary-100 text-primary-700 text-sm font-bold shrink-0">
            {initials}
          </div>
          <div className="flex flex-col leading-tight">
            <span className="text-sm font-medium text-gray-700">{name}</span>
          </div>
        </div>
      </div>
    </header>
  );
}
