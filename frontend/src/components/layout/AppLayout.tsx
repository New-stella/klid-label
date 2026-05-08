import { Outlet } from 'react-router-dom';

import { Gnb } from './Gnb';
import { Lnb } from './Lnb';

/**
 * mock 정합 — fixed GNB + fixed LNB + main(pl-60 pt-14).
 * Footer는 mock에 없으므로 제거.
 */
export function AppLayout() {
  return (
    <div className="min-h-screen bg-gray-50">
      <Gnb />
      <Lnb />
      <main className="pl-60 pt-14 min-h-screen">
        <div className="p-6 min-h-full">
          <Outlet />
        </div>
      </main>
    </div>
  );
}
