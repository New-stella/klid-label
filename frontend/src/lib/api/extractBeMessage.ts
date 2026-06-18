/**
 * BE 에러에서 사용자에게 보여줄 메시지를 안전하게 추출한다.
 *
 * 우선순위: ApiError.userMessage → Error.message → axios 원본 response.data.message → fallback.
 * 보안: BE GlobalExceptionHandler 가 내부 경로/스택트레이스를 미노출하도록 보장하므로
 * 본 함수는 추출만 담당하고, JSX 렌더링은 React 자동 이스케이프에 위임(XSS 방어).
 */
export function extractBeMessage(err: unknown, fallback: string): string {
  if (typeof err === 'object' && err !== null) {
    // ApiError (lib/api/errors.ts) — userMessage/message 우선
    const e = err as { userMessage?: unknown; message?: unknown; response?: { data?: unknown } };
    if (typeof e.userMessage === 'string' && e.userMessage.trim() !== '') {
      return e.userMessage;
    }
    if (typeof e.message === 'string' && e.message.trim() !== '' && e.message !== 'ApiError') {
      return e.message;
    }
    // axios 원본 응답 (인터셉터 우회 케이스 대비)
    const data = e.response?.data;
    if (typeof data === 'object' && data !== null && 'message' in data) {
      const m = (data as { message?: unknown }).message;
      if (typeof m === 'string' && m.trim() !== '') return m;
    }
  }
  return fallback;
}
