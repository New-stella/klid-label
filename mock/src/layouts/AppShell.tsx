import { Outlet } from 'react-router-dom';
import { useSessionStore } from '../store/sessionStore';
import { Gnb } from './Gnb';
import { Lnb } from './Lnb';
import { PortalShell } from './PortalShell';

export function AppShell() {
  const currentRole = useSessionStore((s) => s.currentRole);

  // PORTAL_USER gets simplified portal layout
  if (currentRole === 'PORTAL_USER') {
    return <PortalShell />;
  }

  return (
    <div className="min-h-screen bg-gray-50">
      {/* Fixed GNB */}
      <Gnb />

      {/* Fixed LNB */}
      <Lnb />

      {/* Main content area */}
      <main className="pl-60 pt-14 min-h-screen">
        <div className="p-6 min-h-full">
          <Outlet />
        </div>
      </main>
    </div>
  );
}

export default AppShell;
