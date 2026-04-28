import { useState, useEffect, useRef } from 'react';
import { Outlet, useNavigate } from 'react-router-dom';
import { Globe, Menu, X, AlertTriangle } from 'lucide-react';
import { useSessionStore } from '../store/sessionStore';
import { RoleSwitcher } from '../components/RoleSwitcher';

export function PortalShell() {
  const currentUser = useSessionStore((s) => s.currentUser);
  const currentRole = useSessionStore((s) => s.currentRole);
  const setRole = useSessionStore((s) => s.setRole);
  const navigate = useNavigate();

  const [menuOpen, setMenuOpen] = useState(false);
  const drawerRef = useRef<HTMLDivElement>(null);

  // Close drawer on outside click
  useEffect(() => {
    if (!menuOpen) return;
    const handler = (e: MouseEvent) => {
      if (drawerRef.current && !drawerRef.current.contains(e.target as Node)) {
        setMenuOpen(false);
      }
    };
    document.addEventListener('mousedown', handler);
    return () => document.removeEventListener('mousedown', handler);
  }, [menuOpen]);

  // Close drawer on ESC
  useEffect(() => {
    if (!menuOpen) return;
    const handler = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setMenuOpen(false);
    };
    document.addEventListener('keydown', handler);
    return () => document.removeEventListener('keydown', handler);
  }, [menuOpen]);

  const isPortalUser = currentRole === 'PORTAL_USER';

  return (
    <div className="min-h-screen bg-gray-50 flex flex-col">
      {/* Portal GNB */}
      <header className="bg-white border-b border-gray-200 h-14 flex items-center px-4 md:px-6 shrink-0 relative z-40">
        {/* Logo */}
        <div className="flex items-center gap-2 mr-auto">
          <button
            onClick={() => navigate('/portal')}
            className="flex items-center gap-2 hover:opacity-80 transition-opacity"
            aria-label="포털 메인으로"
          >
            <div className="flex items-center justify-center w-8 h-8 rounded-lg bg-orange-500">
              <Globe size={16} className="text-white" />
            </div>
            <span className="font-bold text-gray-900 text-sm hidden sm:block">
              학습데이터 포털
            </span>
            <span className="text-[10px] font-medium bg-gray-100 text-gray-400 px-1.5 py-0.5 rounded-full hidden sm:inline-block">
              목업
            </span>
          </button>
        </div>

        {/* Desktop nav */}
        <div className="hidden md:flex items-center gap-3">
          <RoleSwitcher />
          <div className="flex items-center gap-2 pl-3 border-l border-gray-200">
            <div className="flex items-center justify-center w-8 h-8 rounded-full bg-orange-100 text-orange-700 text-sm font-bold select-none">
              {currentUser.name.slice(0, 1)}
            </div>
            <span className="text-sm font-medium text-gray-700">
              {currentUser.name}
            </span>
          </div>
        </div>

        {/* Mobile hamburger */}
        <button
          onClick={() => setMenuOpen((v) => !v)}
          className="md:hidden p-2 rounded-lg text-gray-500 hover:bg-gray-100 transition-colors"
          aria-label={menuOpen ? '메뉴 닫기' : '메뉴 열기'}
          aria-expanded={menuOpen}
        >
          {menuOpen ? <X size={20} /> : <Menu size={20} />}
        </button>
      </header>

      {/* Mobile side drawer */}
      {menuOpen && (
        <>
          {/* Backdrop */}
          <div className="fixed inset-0 bg-black/40 z-40 md:hidden" aria-hidden="true" />
          {/* Drawer */}
          <div
            ref={drawerRef}
            className="fixed top-0 right-0 h-full w-72 bg-white shadow-xl z-50 md:hidden flex flex-col"
          >
            <div className="flex items-center justify-between px-4 py-4 border-b border-gray-100">
              <span className="font-semibold text-gray-800 text-sm">메뉴</span>
              <button
                onClick={() => setMenuOpen(false)}
                className="p-1.5 rounded-lg text-gray-400 hover:bg-gray-100 transition-colors"
                aria-label="닫기"
              >
                <X size={16} />
              </button>
            </div>
            <div className="flex-1 p-4 space-y-4">
              {/* User info */}
              <div className="flex items-center gap-3 p-3 bg-orange-50 rounded-xl">
                <div className="flex items-center justify-center w-10 h-10 rounded-full bg-orange-200 text-orange-700 font-bold">
                  {currentUser.name.slice(0, 1)}
                </div>
                <div>
                  <p className="text-sm font-semibold text-gray-800">{currentUser.name}</p>
                  <p className="text-xs text-gray-500">{currentUser.email}</p>
                </div>
              </div>
              {/* Role switcher */}
              <div>
                <p className="text-xs font-semibold text-gray-400 mb-2 uppercase tracking-wide">역할 전환</p>
                <RoleSwitcher />
              </div>
            </div>
          </div>
        </>
      )}

      {/* No permission banner for non-portal users */}
      {!isPortalUser && (
        <div className="bg-yellow-50 border-b border-yellow-200 px-4 py-3">
          <div className="max-w-4xl mx-auto flex items-start gap-3">
            <AlertTriangle size={18} className="text-yellow-500 shrink-0 mt-0.5" />
            <div className="flex-1">
              <p className="text-sm font-semibold text-yellow-800">
                이 영역은 포털 사용자 전용입니다
              </p>
              <p className="text-xs text-yellow-700 mt-0.5">
                현재 역할({currentRole})로는 포털 화면을 이용할 수 없습니다.
                역할을 <strong>포털 사용자</strong>로 전환하거나 원래 화면으로 돌아가세요.
              </p>
              <div className="flex items-center gap-2 mt-2">
                <button
                  onClick={() => {
                    setRole('PORTAL_USER');
                    navigate('/portal');
                  }}
                  className="text-xs px-3 py-1 bg-orange-500 text-white rounded-lg hover:bg-orange-600 transition-colors font-medium"
                >
                  포털 사용자로 전환
                </button>
                <button
                  onClick={() => navigate('/dashboard')}
                  className="text-xs px-3 py-1 border border-yellow-300 text-yellow-700 rounded-lg hover:bg-yellow-100 transition-colors"
                >
                  대시보드로
                </button>
              </div>
            </div>
          </div>
        </div>
      )}

      {/* Main content */}
      <main className="flex-1">
        <Outlet />
      </main>

      {/* Footer */}
      <footer className="bg-white border-t border-gray-100 py-4 px-6 text-center">
        <p className="text-xs text-gray-400">
          문의:{' '}
          <a href="mailto:portal@example.com" className="hover:text-orange-500 transition-colors">
            portal@example.com
          </a>
          {' · '}
          <span className="cursor-default">개인정보처리방침</span>
          {' · '}
          <span className="cursor-default">이용약관</span>
        </p>
      </footer>
    </div>
  );
}

export default PortalShell;
