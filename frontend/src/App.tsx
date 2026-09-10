import { Suspense, useEffect } from 'react';
import { RouterProvider } from 'react-router-dom';

import { ErrorBoundary } from '@/components/common/ErrorBoundary';
import { ControlSessionMonitor } from '@/features/auth/ControlSessionMonitor';
import { syncPortalSessionThen } from '@/features/auth/portalSession';
import { isPortalEmbedChannel } from '@/lib/buildChannel';
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
    // [@design INT-013]
    // 포털 채널은 저장소에서 복원할 것이 없다 — 세션의 진실원이 Host 메모리라, 창구에 물어야
    // 채워진다. 세션 복원 <b>앞에</b> 한 번 맞춰 두면 첫 라우트 가드가 이미 세션을 갖고 렌더돼
    // 인증 안내가 한 프레임 비치지 않는다. 관제 채널에서는 통째로 no-op 이다.
    // ⚠ **순서가 의미를 가진다.** 되맞춤이 비동기가 되었으므로 `restoreSession()` 을 그냥
    //   이어 부르면 창구의 답이 오기 «전»에 복원이 돌아, 포털 채널에서 인증 안내가 한 프레임
    //   비쳤다가 화면으로 바뀐다(이 자리가 원래 없애려던 그 깜빡임이다). 그래서 잇는다.
    //   ★ 관제 채널에서는 이 헬퍼가 **동기로 즉시** 이어 주므로 부팅 순서가 그대로다.
    syncPortalSessionThen(restoreSession);
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
      {/* [@design ADR-012] [@design SHELL-001] [@design AC-1105]
          관제 채널 세션 만료 감시 + 연장 팝업 — 셸이 아니라 <앱 최상단>에 둔다. 셸(`AppLayout`) 밖
          전체 화면인 라벨링 캔버스에서도 떠야 하기 때문이다. 포털 채널은 Host 가 세션을 소유하므로
          탑재하지 않는다(감시·팝업·저장소 쓰기 전부 무동작). */}
      {!isPortalEmbedChannel() && <ControlSessionMonitor />}
    </ErrorBoundary>
  );
}
