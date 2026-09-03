// 마킹 산출물 일괄 가져오기 API 클라이언트.
//
// 응답은 `ApiResponse<T>` 래퍼이며 client 인터셉터가 `data` 를 꺼내 돌려준다.
//
// 보안:
// - 인가는 BE(SecurityConfig 매처 + @PreAuthorize)가 최종 판정한다. 라우터 가드는 UX 다.
//   ★권한이 창구마다 다르다 — 검사·진행 조회는 검수자, 적재 실행은 관리자다. 이 비대칭은
//   의도이며 화면이 한쪽으로 맞추지 않는다.
// - 폴더 경로는 주소줄이 아니라 **본문**으로 보낸다 — 구분자와 공백이 섞여 접근 기록·중간
//   경유지에 남는 것을 피한다. 허용 저장소 범위 판정은 BE 소유다.
// - 작업 식별번호는 number 타입 강제 — 문자열 주입 통로가 없다.
//
// @design SCREEN-039
// @design API-216 API-217 API-218

import { apiClient } from '@/lib/api/client';

import type {
  MarkingCreateRequest,
  MarkingCreateResult,
  MarkingItemStatus,
  MarkingProgress,
  MarkingScanRequest,
  MarkingScanResult,
} from './markingTypes';

/**
 * 마킹 산출물 폴더 검사 — BE: POST /api/v1/imports/markings/scan (REVIEWER).
 *
 * POST 이지만 아무것도 저장하지 않는다. 여러 번 보내도 결과가 같으므로 폴더를 잘못 넣어도
 * 되돌릴 것이 없다.
 */
export function scanMarkingFolder(body: MarkingScanRequest): Promise<MarkingScanResult> {
  return apiClient.post<MarkingScanResult>('/imports/markings/scan', body).then((r) => r.data);
}

/**
 * 마킹 산출물 일괄 적재 — BE: POST /api/v1/imports/markings (ADMIN).
 *
 * ★응답은 **202** 다. 뜻하는 것은 「작업이 등록되었다」뿐이며 적재는 아직 끝나지 않았다.
 * 그래서 응답에 영상 목록도 결과도 없다 — 작업 식별번호로 {@link getMarkingProgress} 를
 * 되풀이해 진행을 본다. 2xx 를 성공으로 다루므로 상태코드를 따로 비교하지 않는다.
 */
export function createMarkingImport(body: MarkingCreateRequest): Promise<MarkingCreateResult> {
  return apiClient.post<MarkingCreateResult>('/imports/markings', body).then((r) => r.data);
}

/**
 * 일괄 적재 진행 조회 — BE: GET /api/v1/imports/markings/{jobSn}?status= (REVIEWER).
 *
 * 상태는 **담기는 목록만** 거른다 — 위쪽 집계 수치는 언제나 전체 기준이다. 값이 없으면 키를
 * 싣지 않는다(빈 문자열을 실으면 계약 밖 값이 되어 거부된다).
 */
export function getMarkingProgress(
  jobSn: number,
  status?: MarkingItemStatus | null,
): Promise<MarkingProgress> {
  const params: Record<string, string> = {};
  if (status) params.status = status;
  return apiClient
    .get<MarkingProgress>(`/imports/markings/${jobSn}`, { params })
    .then((r) => r.data);
}
