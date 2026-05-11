import { Suspense, useEffect } from 'react';
import { RouterProvider } from 'react-router-dom';

import { ErrorBoundary } from '@/components/common/ErrorBoundary';
import { Spinner } from '@/components/common/Spinner';
import { router } from '@/router';
import { useAuthStore } from '@/stores/useAuthStore';

export function App() {
  const isHydrated = useAuthStore((s) => s.isHydrated);
  const hydrate = useAuthStore((s) => s.hydrate);

  useEffect(() => {
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
