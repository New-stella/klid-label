// PortalLayout — 외부 사용자(포털 채널) 전용 단순 레이아웃.
// - GNB 단순화: 제목 + 사용자 메뉴만
// - LNB 없음
// - 모바일 친화 (Tailwind md:* 분기, WCAG 2.1 AA)

import { Link, Outlet } from 'react-router-dom';

import { useAuthStore } from '@/stores/useAuthStore';

import { Footer } from './Footer';

export function PortalLayout() {
  const claims = useAuthStore((s) => s.claims);

  return (
    <div className="flex min-h-screen flex-col bg-bgLight">
      <header className="sticky top-0 z-40 flex h-14 items-center justify-between bg-primary px-4 text-white md:px-6">
        <Link to="/portal" className="text-section-title font-bold">
          AI 학습데이터 포털
        </Link>
        <span className="text-btn-label">{claims?.name ?? '사용자'}</span>
      </header>
      <main className="flex-1 px-4 py-4 md:px-6">
        <Outlet />
      </main>
      <Footer />
    </div>
  );
}
