// [개발/검수 전용] dev 파일 업로드 API — BE: POST /api/v1/dev/upload [@design API-152]
//
// 보안:
// - 파일은 `file` part 로, 메타는 `meta` part (application/json Blob) 로 분리 전송.
// - axios 가 FormData boundary 를 자동 산출하므로 `Content-Type` 헤더를 수동 설정하지 않는다
//   (수동 지정 시 boundary 가 누락되어 BE 의 multipart 파서가 400 으로 거절).
// - 사용자 입력은 JSON.stringify 만 수행 — SQL/Path Injection 방어는 BE 측 validation 에서 처리.
// - 응답은 ApiResponse<T> 래퍼를 client.ts 인터셉터가 풀어 `r.data` 가 곧 result.

import { adminSessionHeaders } from '@/features/adminSession/api';
import { apiClient } from '@/lib/api/client';

import type { AutolabelTestMeta, AutolabelTestResult } from './types';

/**
 * 영상 파일 + 메타데이터 업로드 → 오토라벨 파이프라인 백그라운드 트리거. [@design API-152]
 *
 * <p>업로드 시작은 운영·관리 성격의 쓰기라 검수자 권한 <b>위에</b> 관리자 단기 유효창이 가산된다.
 * 유효창이 없거나 끝났으면 서버가 403 으로 거부한다.
 *
 * <p>⚠ <b>화면 주소만 관리자 페이지로 옮겼고 이 창구의 경로(`/dev/upload`)는 그대로다</b> —
 * 이 변경의 축은 화면 배치이지 창구 개명이 아니다.
 *
 * @throws ApiError — BE 4xx/5xx 응답을 그대로 전파한다 (호출자 측에서 BE message 추출).
 */
export function uploadAutolabelTest(
  file: File,
  meta: AutolabelTestMeta,
  adminSessionToken?: string,
): Promise<AutolabelTestResult> {
  const fd = new FormData();
  fd.append('file', file);
  fd.append(
    'meta',
    new Blob([JSON.stringify(meta)], { type: 'application/json' }),
  );
  return apiClient
    .post<AutolabelTestResult>('/dev/upload', fd, {
      headers: adminSessionHeaders(adminSessionToken),
    })
    .then((r) => r.data);
}
