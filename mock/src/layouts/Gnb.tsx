import { Video } from 'lucide-react';
import { useSessionStore } from '../store/sessionStore';
import { RoleSwitcher } from '../components/RoleSwitcher';

export function Gnb() {
  const currentUser = useSessionStore((s) => s.currentUser);

  // Generate initials for avatar
  const initials = currentUser.name.slice(0, 1);

  return (
    <header className="fixed top-0 left-0 right-0 z-40 h-14 bg-white border-b border-gray-200 flex items-center px-4">
      {/* Left: Logo */}
      <div className="flex items-center gap-2.5 w-60 shrink-0">
        <div className="flex items-center justify-center w-8 h-8 rounded-lg bg-primary-600">
          <Video size={16} className="text-white" />
        </div>
        <span className="font-bold text-gray-900 text-sm leading-tight">
          학습데이터 저작도구
        </span>
        <span className="text-[10px] font-medium bg-gray-100 text-gray-400 px-1.5 py-0.5 rounded-full shrink-0">
          목업
        </span>
      </div>

      {/* Spacer */}
      <div className="flex-1" />

      {/* Right: Role switcher + User */}
      <div className="flex items-center gap-3">
        <RoleSwitcher />
        <div className="flex items-center gap-2 pl-3 border-l border-gray-200">
          <div className="flex items-center justify-center w-8 h-8 rounded-full bg-primary-100 text-primary-700 text-sm font-bold shrink-0">
            {initials}
          </div>
          <span className="text-sm font-medium text-gray-700 hidden sm:block">
            {currentUser.name}
          </span>
        </div>
      </div>
    </header>
  );
}

export default Gnb;
