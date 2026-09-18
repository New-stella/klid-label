import type MockAdapter from 'axios-mock-adapter';

/**
 * 가짜 응답 도우미 — 서버 대신 통신 창구(`apiClient`)에 끼워 화면 상태를 세운다.
 *
 * 두 곳이 같이 쓴다: 저작도구 화면 스토리북(6010, `stories/authoring/storyScreen`)과
 * 서버 없이 띄우는 시연판(`demo/installDemo`). 응답 모양을 한 군데서만 정하기 위해 여기에 둔다.
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
