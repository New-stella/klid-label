// [개발/검수 전용] dev 영상 업로드 API — BE: POST /api/v1/dev/upload [@design API-152]
//
// 보안:
// - 파일은 `file` part 로, 메타는 `meta` part (application/json Blob) 로 분리 전송.
// - axios 가 FormData boundary 를 자동 산출하므로 `Content-Type` 헤더를 수동 설정하지 않는다
//   (수동 지정 시 boundary 가 누락되어 BE 의 multipart 파서가 400 으로 거절).
// - 사용자 입력은 JSON.stringify 만 수행 — SQL/Path Injection 방어는 BE 측 validation 에서 처리.
// - 응답은 ApiResponse<T> 래퍼를 client.ts 인터셉터가 풀어 `r.data` 가 곧 result.

import { apiClient } from '@/lib/api/client';

import type { AutolabelTestMeta, AutolabelTestResult } from './types';

/**
 * 영상 파일 + 메타데이터 업로드 → 오토라벨 파이프라인 백그라운드 트리거.
 *
 * @throws ApiError — BE 4xx/5xx 응답을 그대로 전파한다 (호출자 측에서 BE message 추출).
 */
export function uploadAutolabelTest(
  file: File,
  meta: AutolabelTestMeta,
): Promise<AutolabelTestResult> {
  const fd = new FormData();
  fd.append('file', file);
  fd.append(
    'meta',
    new Blob([JSON.stringify(meta)], { type: 'application/json' }),
  );
  return apiClient
    .post<AutolabelTestResult>('/dev/upload', fd)
    .then((r) => r.data);
}
