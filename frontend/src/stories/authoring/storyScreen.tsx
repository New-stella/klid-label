import { useEffect, useState } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import MockAdapter from 'axios-mock-adapter';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';

import { ToastProvider } from '@/components/common/ToastProvider';
import { apiClient } from '@/lib/api/client';
import { PORTAL_MOUNT_BASENAME } from '@/lib/remoteMount';
import { PORTAL_EMBED_ANCHOR_CLASS } from '@/lib/portalEmbedAnchor';
import type { TokenClaims } from '@/lib/api/types';
import { routes } from '@/router';
import { useAuthStore } from '@/stores/useAuthStore';

/**
 * 저작도구 화면 스토리 한 장 — **주소와 가짜 응답만 준다.**
 *
 * 화면을 여기서 다시 짜지 않고 앱의 라우트 트리를 그대로 태운다(포털 스토리북의 화면 스토리와 같은 방식).
 * 서버 대신 가짜 응답을 끼워 상태를 세운다 — 응답이 오지 않는 상태는 `pending`, 실패는 `fail`.
 * 포털 스토리북(6008)이 이 스토리를 포털 카드 안 iframe 으로 불러온다.
 */

/** 가짜 응답 설정 — 화면이 부르는 주소마다 응답을 건다(주소는 `/api/v1` 뒤 경로). */
export type ApiMocks = (mock: MockAdapter) => void;

/** 서버 봉투 그대로 — 요청 도구가 `data` 만 꺼내 화면에 준다 */
export const ok = (data: unknown): [number, unknown] => [
  200,
  { success: true, data, message: null, errorCode: null },
];

/** 실패 응답 — 화면은 서버가 준 문구를 안내에 쓰기도 한다 */
export const fail = (
  status = 500,
  message: string | null = null,
  errorCode = 'INTERNAL_ERROR',
): [number, unknown] => [status, { success: false, data: null, message, errorCode }];

/** 끝나지 않는 응답 — 불러오는 중 · 보내는 중 · 받는 중 상태를 멈춰 세운다 */
export const pending = (): Promise<never> => new Promise<never>(() => undefined);

/** 쪽 응답 */
export function pageOf<T>(content: T[], total = content.length, size = 20) {
  return {
    content,
    totalElements: total,
    totalPages: Math.max(1, Math.ceil(total / size)),
    number: 0,
    size,
  };
}

/** 스토리의 로그인 — 포털 사용자 홍길동 (개발용 로그인 화면의 기본값과 같다) */
const STORY_CLAIMS: TokenClaims = {
  sub: '3001',
  name: '홍길동',
  role: 'PORTAL_USER' as TokenClaims['role'],
  channel: 'PORTAL',
  exp: 4102444800,
};

function StoryScreen({ path, api }: { path: string; api?: ApiMocks }) {
  // 요청이 나가기 전에 가짜 응답 · 로그인 · 라우터가 서 있어야 한다 — 첫 렌더에서 한 번만 만든다
  const [setup] = useState(() => {
    const mock = new MockAdapter(apiClient);
    api?.(mock);
    // 걸어 두지 않은 주소는 콘솔에 남기고 404 로 돌려준다 — 빠진 응답을 찾기 쉽게
    mock.onAny().reply((config) => {
      console.warn('[저작도구 스토리] 가짜 응답이 없는 요청', config.method, config.url);
      return fail(404, null, 'NOT_FOUND');
    });
    useAuthStore.setState({
      token: 'story-token',
      claims: STORY_CLAIMS,
      isHydrated: true,
      serverRoleStatus: 'ready',
    });
    const queryClient = new QueryClient({
      defaultOptions: {
        queries: { retry: false, refetchOnWindowFocus: false },
        mutations: { retry: false },
      },
    });
    const router = createMemoryRouter(routes, {
      initialEntries: [`${PORTAL_MOUNT_BASENAME}${path}`],
      basename: PORTAL_MOUNT_BASENAME,
    });
    return { mock, queryClient, router };
  });

  useEffect(() => () => setup.mock.restore(), [setup]);

  // 포털 스킨·리셋은 이 감싸개 안에서만 먹는다 — 앱 진입점(main.tsx)·Remote 진입점과 같은 감싸개다. 빠지면 맨 화면이 뜬다.
  return (
    <div className={`${PORTAL_EMBED_ANCHOR_CLASS} h-full`}>
      <QueryClientProvider client={setup.queryClient}>
        <ToastProvider>
          <RouterProvider router={setup.router} />
        </ToastProvider>
      </QueryClientProvider>
    </div>
  );
}

/** 스토리 `render` 에 쓴다 — `render: () => 화면('/portal', (mock) => …)` */
export function 화면(path: string, api?: ApiMocks) {
  return <StoryScreen path={path} api={api} />;
}
