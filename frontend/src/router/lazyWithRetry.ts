import { ComponentType, lazy, LazyExoticComponent } from 'react';

/**
 * React.lazy 를 감싼 헬퍼 — Vite dev 서버의 optimized-deps 재최적화 레이스로 인한
 * `Failed to fetch dynamically imported module` 동적 import 실패를 흡수한다.
 *
 * 방어 단계:
 *  1) 1차 import 실패 시 짧은 지연 후 1회 재시도 (transient 재최적화 레이스 흡수).
 *  2) 재시도도 실패하면 "실패 버스트당 1회"만 `window.location.reload()` 로 새 모듈 그래프를 받는다.
 *     - sessionStorage 플래그로 같은 버스트 내 무한 reload 루프를 차단한다.
 *     - reload 진행 중에는 영원히 pending 인 Promise 를 반환해 깜빡임을 막는다.
 *  3) 성공 시 플래그를 제거한다 → 다음에 또 실패하면 다시 1회 reload 가 가능하다.
 *     (즉 "세션당 1회"가 아니라 "성공 시 리셋되는 실패 버스트당 1회"다.)
 *
 * 사용법은 기존 라우터 패턴과 동일:
 *   const Page = lazyWithRetry(() => import('@/pages/X').then((m) => ({ default: m.X })));
 */

const RELOAD_FLAG_KEY = 'klid-lazy-retry-reloaded';
const RETRY_DELAY_MS = 300;

function delay(ms: number): Promise<void> {
  return new Promise((resolve) => {
    setTimeout(resolve, ms);
  });
}

// React.lazy 원본 시그니처와 정합하도록 ComponentType<any> 제약을 사용한다.
// (props 타입을 unknown 으로 좁히면 strict 모드에서 일부 페이지 컴포넌트의 props
//  추론이 깨질 수 있어, lazy 의 오버로드와 동일한 any 제약을 채택)
// eslint-disable-next-line @typescript-eslint/no-explicit-any
export function lazyWithRetry<T extends ComponentType<any>>(
  factory: () => Promise<{ default: T }>,
): LazyExoticComponent<T> {
  return lazy(async () => {
    try {
      const mod = await factory();
      // 성공 — 다음 실패 때 다시 reload 할 수 있도록 플래그 정리.
      try {
        sessionStorage.removeItem(RELOAD_FLAG_KEY);
      } catch {
        // sessionStorage 접근 불가(프라이빗 모드 등) 시 무시.
      }
      return mod;
    } catch {
      // 1차 실패 — 짧은 지연 후 1회 재시도로 transient 재최적화 레이스를 흡수.
      try {
        await delay(RETRY_DELAY_MS);
        const mod = await factory();
        try {
          sessionStorage.removeItem(RELOAD_FLAG_KEY);
        } catch {
          // 무시
        }
        return mod;
      } catch (retryError) {
        // 재시도도 실패 — 세션당 1회만 전체 reload 로 stale 모듈 그래프를 갱신.
        let alreadyReloaded = false;
        try {
          alreadyReloaded = sessionStorage.getItem(RELOAD_FLAG_KEY) === '1';
        } catch {
          // sessionStorage 접근 불가 시 reload 시도하지 않고 에러를 그대로 전파.
          throw retryError;
        }

        if (!alreadyReloaded) {
          try {
            sessionStorage.setItem(RELOAD_FLAG_KEY, '1');
          } catch {
            // 무시
          }
          window.location.reload();
          // reload 진행 중 — 영원히 pending 인 Promise 로 fallback 깜빡임 방지.
          return new Promise<{ default: T }>(() => {});
        }

        // 이미 이번 세션에서 reload 했는데도 실패 — 더 이상 reload 하지 않고
        // ErrorBoundary 가 처리하도록 에러 전파.
        throw retryError;
      }
    }
  });
}
