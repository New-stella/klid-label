// PortalLayout — 외부 사용자(포털 채널) 전용 단순 레이아웃.
// - GNB 단순화: 제목 + 사용자 메뉴만
// - LNB 없음
// - 모바일 친화 (Tailwind md:* 분기, WCAG 2.1 AA)

import { Link, Outlet } from 'react-router-dom';

import { cn } from '@/lib/cn';
import { KRDS_FOCUS } from '@/lib/focusRing';
import { useAuthStore } from '@/stores/useAuthStore';

// ★하단 푸터는 두지 않는다 (2026-08-18 사용자 확정 — 사양 SHELL-001·SHELL-002 `footer.enabled=false`).
//   노출 여부와 문안(근거법령·운영기관·문의처)이 아직 확정되지 않았고, 확정 전에 자리표시 문구
//   ([운영기관명]·[000-0000-0000]·[example@example.go.kr])를 내보내면 그 값이 실제 정보인 것처럼
//   읽힌다. `Footer` 컴포넌트는 재노출을 위해 **삭제하지 않고 남겨 두며**, 문안이 확정되면 여기에
//   다시 마운트한다. **푸터 부재는 결손이 아니라 이 결정의 결과다** — 정합 점검에서 누락으로
//   보고하지 말 것(회귀 가드: AppLayout.test.tsx · PortalLayout.test.tsx 의 푸터 미노출 단언).
export function PortalLayout() {
  const claims = useAuthStore((s) => s.claims);

  return (
    <div className="flex min-h-screen flex-col bg-gray-50">
      <header className="sticky top-0 z-40 flex h-14 items-center justify-between border-b border-gray-200 bg-white px-4 md:px-6">
        <Link
          to="/portal"
          className={cn(
            'rounded-md text-section-title font-bold text-gray-900 hover:text-primary-600 transition-colors',
            KRDS_FOCUS,
          )}
        >
          AI 학습데이터 포털
        </Link>
        <span className="text-btn-label text-gray-700">{claims?.name ?? '사용자'}</span>
      </header>
      <main className="flex-1 px-4 py-6 md:px-6">
        <Outlet />
      </main>
    </div>
  );
}
