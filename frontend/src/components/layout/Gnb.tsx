import { Link } from 'react-router-dom';
import { Bell, ChevronDown } from 'lucide-react';

import { useAuthStore } from '@/stores/useAuthStore';

export function Gnb() {
  const claims = useAuthStore((s) => s.claims);

  return (
    <header className="sticky top-0 z-40 flex h-14 items-center justify-between bg-primary px-6 text-white">
      <div className="flex items-center gap-8">
        <Link to="/video/completed" className="text-section-title font-bold">
          AI 학습데이터 저작도구
        </Link>
        <nav aria-label="주요 메뉴" className="hidden items-center gap-6 md:flex">
          <Link to="/video/completed" className="text-btn-label hover:opacity-80">
            영상
          </Link>
          <Link to="/task" className="text-btn-label hover:opacity-80">
            작업관리
          </Link>
          <Link to="/stat/overall" className="text-btn-label hover:opacity-80">
            통계
          </Link>
          <Link to="/manage" className="text-btn-label hover:opacity-80">
            관리
          </Link>
        </nav>
      </div>
      <div className="flex items-center gap-4">
        <button
          type="button"
          aria-label="알림"
          className="rounded p-1 transition-colors duration-100 hover:bg-secondary"
        >
          <Bell size={18} aria-hidden />
        </button>
        <button
          type="button"
          aria-label="사용자 메뉴"
          className="flex items-center gap-1 rounded px-2 py-1 transition-colors duration-100 hover:bg-secondary"
        >
          <span className="text-btn-label">{claims?.name ?? '사용자'}</span>
          <ChevronDown size={14} aria-hidden />
        </button>
      </div>
    </header>
  );
}
