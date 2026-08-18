import { Outlet } from 'react-router-dom';

import { Gnb } from './Gnb';
import { Lnb } from './Lnb';

/**
 * mock 정합 — fixed GNB + fixed LNB + main(pl-60 pt-14).
 * 하단 푸터는 마운트하지 않는다(아래 주석 참조).
 */
// ★하단 푸터는 두지 않는다 (2026-08-18 사용자 확정 — 사양 SHELL-001·SHELL-002 `footer.enabled=false`).
//   노출 여부와 문안(근거법령·운영기관·문의처)이 아직 확정되지 않았고, 확정 전에 자리표시 문구
//   ([운영기관명]·[000-0000-0000]·[example@example.go.kr])를 내보내면 그 값이 실제 정보인 것처럼
//   읽힌다. `Footer` 컴포넌트는 재노출을 위해 **삭제하지 않고 남겨 두며**, 문안이 확정되면 여기에
//   다시 마운트한다. **푸터 부재는 결손이 아니라 이 결정의 결과다** — 정합 점검에서 누락으로
//   보고하지 말 것(회귀 가드: AppLayout.test.tsx · PortalLayout.test.tsx 의 푸터 미노출 단언).
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
      </div>
    </div>
  );
}
