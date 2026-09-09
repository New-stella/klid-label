import { Suspense, useEffect } from 'react';
import { RouterProvider } from 'react-router-dom';

import { ErrorBoundary } from '@/components/common/ErrorBoundary';
import { syncPortalSessionFromHandoff } from '@/features/auth/portalSession';
import { Spinner } from '@/components/common/Spinner';
import { router } from '@/router';
import { useAuthStore } from '@/stores/useAuthStore';

export function App() {
  const isHydrated = useAuthStore((s) => s.isHydrated);
  const hydrate = useAuthStore((s) => s.hydrate);

  useEffect(() => {
    // [@design INT-013]
    // 포털 채널은 저장소에서 복원할 것이 없다 — 세션의 진실원이 Host 메모리라, 창구에 물어야
    // 채워진다. hydrate <b>앞에</b> 한 번 맞춰 두면 첫 라우트 가드가 이미 세션을 갖고 렌더돼
    // 인증 안내가 한 프레임 비치지 않는다. 관제 채널에서는 통째로 no-op 이다.
    syncPortalSessionFromHandoff();
    hydrate();
  }, []); // mount-once: hydrate는 앱 초기화 시 1회만 실행

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
