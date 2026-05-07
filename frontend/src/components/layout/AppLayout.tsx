import { Outlet } from 'react-router-dom';

import { Footer } from './Footer';
import { Gnb } from './Gnb';
import { Lnb } from './Lnb';

export function AppLayout() {
  return (
    <div className="flex min-h-screen flex-col bg-bgLight">
      <Gnb />
      <div className="flex flex-1">
        <Lnb />
        <main className="flex-1 px-6 py-4">
          <Outlet />
        </main>
      </div>
      <Footer />
    </div>
  );
}
