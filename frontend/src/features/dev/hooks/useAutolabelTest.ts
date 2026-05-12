import { useMutation } from '@tanstack/react-query';

import { uploadAutolabelTest } from '../api';
import type { AutolabelTestMeta, AutolabelTestResult } from '../types';

export interface UseAutolabelTestOptions {
  onSuccess?: (data: AutolabelTestResult) => void;
  onError?: (error: unknown) => void;
}

/**
 * 오토라벨 테스트 업로드 mutation 훅.
 *
 * - 성공: `data.rawSn` 으로 후속 polling 시작 (호출자에서 처리).
 * - 실패: BE `ApiResponse.message` 를 `ApiError.message` 로 전파 (사용자 친화적 텍스트).
 */
export function useAutolabelTest(opts?: UseAutolabelTestOptions) {
  return useMutation<
    AutolabelTestResult,
    unknown,
    { file: File; meta: AutolabelTestMeta }
  >({
    mutationFn: ({ file, meta }) => uploadAutolabelTest(file, meta),
    onSuccess: (data) => {
      opts?.onSuccess?.(data);
    },
    onError: (err) => {
      opts?.onError?.(err);
    },
  });
}

/**
 * BE 가 ApiResponse.message 로 내려준 사람 친화적 에러 메시지를 안전하게 추출한다.
 *
 * - `ApiError.userMessage` 또는 `ApiError.message` 우선
 * - axios 에러 형태(`err.response.data.message`) 보조
 * - 위 두 경로 모두 미존재 시 fallback
 *
 * 보안: BE GlobalExceptionHandler 가 내부 경로/스택트레이스를 미노출하도록 보장.
 * 본 함수는 추출만 담당하고, JSX 렌더링은 React 자동 이스케이프에 위임 (XSS 방어).
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
