import { Suspense, useEffect } from 'react';
import { RouterProvider } from 'react-router-dom';

import { ErrorBoundary } from '@/components/common/ErrorBoundary';
import { Spinner } from '@/components/common/Spinner';
import { restoreSession } from '@/features/auth/sessionBootstrap';
import { router } from '@/router';
import { useAuthStore } from '@/stores/useAuthStore';

export function App() {
  const isHydrated = useAuthStore((s) => s.isHydrated);

  // [@design ADR-063] [@design SEQ-034] [@design UC-041]
  // 화면을 다시 불러올 때(새로고침 포함) 지나는 <유일한 자리>다. 진입 화면(`/ingress`)은
  // 최초 인계에만 지나므로, 여기서 서버 인가 역할을 다시 확보하지 않으면 새로고침마다
  // 그 값이 사라진다 — 토큰 복원과 역할 확보를 한 묶음으로 두는 이유가 그것이다.
  //
  // ⚠ `hydrate()` 만 부르던 것으로 되돌리지 말 것. 복원은 토큰만 되살리고 서버 역할은
  //   되살리지 않는다(저장소에 보관하지 않는 값이다).
  useEffect(() => {
    restoreSession();
  }, []); // mount-once: 세션 복원은 앱 초기화 시 1회만 실행

  if (!isHydrated) {
    return (
      <div className="flex min-h-screen items-center justify-center">
        <Spinner size="lg" />
      </div>
    );
  }

  return (
    <ErrorBoundary>
      <Suspense
        fallback={
          <div className="flex min-h-screen items-center justify-center">
            <Spinner size="lg" />
          </div>
        }
      >
        <RouterProvider router={router} />
      </Suspense>
    </ErrorBoundary>
  );
}
