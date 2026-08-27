import { useMutation } from '@tanstack/react-query';

import { extractBeMessage } from '@/lib/api/extractBeMessage';

import { uploadAutolabelTest } from '../api';
import type { AutolabelTestMeta, AutolabelTestResult } from '../types';

// 공용 에러 메시지 추출기를 재노출 — 기존 import 경로(useAutolabelTest) 호환 유지.
export { extractBeMessage };

export interface UseAutolabelTestOptions {
  onSuccess?: (data: AutolabelTestResult) => void;
  onError?: (error: unknown) => void;
}

/** 업로드 시작에 실을 관리자 유효창 토큰 — 없으면 서버가 403 으로 거부한다. */
export interface AutolabelTestVariables {
  file: File;
  meta: AutolabelTestMeta;
  adminSessionToken?: string;
}

/**
 * 오토라벨 테스트 업로드 mutation 훅.
 *
 * - 성공: `data.rawSn` 으로 후속 polling 시작 (호출자에서 처리).
 * - 실패: BE `ApiResponse.message` 를 `ApiError.message` 로 전파 (사용자 친화적 텍스트).
 */
export function useAutolabelTest(opts?: UseAutolabelTestOptions) {
  return useMutation<AutolabelTestResult, unknown, AutolabelTestVariables>({
    mutationFn: ({ file, meta, adminSessionToken }) =>
      uploadAutolabelTest(file, meta, adminSessionToken),
    onSuccess: (data) => {
      opts?.onSuccess?.(data);
    },
    onError: (err) => {
      opts?.onError?.(err);
    },
  });
}

