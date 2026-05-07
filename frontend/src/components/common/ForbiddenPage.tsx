import { Link } from 'react-router-dom';

import { useAuthStore } from '@/stores/useAuthStore';

/**
 * 403 페이지 (UI/UX 4-1).
 * 채널별 메인 진입점으로 돌아가는 링크 제공.
 */
export function ForbiddenPage() {
  const channel = useAuthStore((s) => s.claims?.channel);
  const home = channel === 'PORTAL' ? '/portal' : '/video/completed';

  return (
    <main role="alert" className="flex min-h-[60vh] flex-col items-center justify-center p-8">
      <h1 className="text-page-title text-primary">403</h1>
      <p className="mt-4 text-section-title text-primary">접근 권한이 없습니다</p>
      <p className="mt-2 text-body text-neutral">
        이 페이지에 접근할 수 있는 권한이 없습니다. 메인 화면으로 돌아가세요.
      </p>
      <Link to={home} className="mt-6 text-secondary underline">
        메인으로 이동
      </Link>
    </main>
  );
}
