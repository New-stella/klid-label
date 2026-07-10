import { Outlet } from 'react-router-dom';

import { Footer } from './Footer';
import { Gnb } from './Gnb';
import { Lnb } from './Lnb';

/**
 * mock 정합 — fixed GNB + fixed LNB + main(pl-60 pt-14).
 * 공공 웹 표준(KRDS) 필수 정보(근거법령·문의처)를 담은 Footer 를 콘텐츠 하단에 렌더한다.
 * LNB(fixed w-60) 와 겹치지 않도록 pl-60 영역 안에서 main+Footer 를 flex-col 로 배치한다.
 */
export function AppLayout() {
  return (
    <div className="min-h-screen bg-gray-50">
      <Gnb />
      <Lnb />
      <div className="flex min-h-screen flex-col pl-60 pt-14">
        <main className="flex-1">
          <div className="p-6">
            <Outlet />
          </div>
        </main>
        <Footer />
      </div>
    </div>
  );
}
