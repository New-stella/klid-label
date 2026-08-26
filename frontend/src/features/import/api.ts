// 외부 산출물 이관 API 클라이언트 (REVIEWER 전용).
//
// 응답은 `ApiResponse<T>` 래퍼이며 client 인터셉터가 `data` 를 꺼내 돌려준다.
//
// 보안:
// - 인가는 BE(SecurityConfig 매처 + @PreAuthorize)가 최종 판정한다. 라우터 가드는 UX 다.
// - 경로 문자열은 주소줄이 아니라 **본문**으로 보낸다(검사·적재·비식별 기록 모두) — 구분자와
//   공백이 섞여 접근 기록·중간 경유지에 남는 것을 피한다. 허용 저장소 범위 판정은 BE 소유다.
// - 경로 변수(rawSn/mpngSn/trnsfSn)는 number 타입 강제 — 문자열 주입 통로가 없다.
//
// @design API-205 API-206 API-207 API-208 API-209 API-210 API-211 API-215 API-221 API-222

import { apiClient } from '@/lib/api/client';
import type { PageResponse } from '@/lib/api/types';

import type {
  DeidentCompleteRequest,
  ImportBrowseResult,
  DeidentCompleteResult,
  ImportCreateRequest,
  ImportCreateResult,
  ImportHistoryDetail,
  ImportHistoryItem,
  ImportMappingCreateRequest,
  ImportMappingListResult,
  ImportMappingSaveResult,
  ImportScanRequest,
  ImportScanResult,
  ListImportHistoryParams,
  ListImportMappingsParams,
} from './types';

/**
 * 산출물 폴더 검사(미리보기) — BE: POST /api/v1/imports/scan.
 *
 * POST 이지만 아무것도 저장하지 않는다(AC-041). 여러 번 보내도 결과가 같다.
 */
export function scanImportFolder(body: ImportScanRequest): Promise<ImportScanResult> {
  return apiClient.post<ImportScanResult>('/imports/scan', body).then((r) => r.data);
}

/**
 * 외부 산출물 적재 — BE: POST /api/v1/imports.
 *
 * 이미 가져온 산출물이면 409 이며, 서버 메시지에 기존 영상 번호가 담겨 있다. 화면은 그 메시지를
 * 그대로 보여준다 — 번호를 클라이언트가 다시 조립하지 않는다.
 */
export function createImport(body: ImportCreateRequest): Promise<ImportCreateResult> {
  return apiClient.post<ImportCreateResult>('/imports', body).then((r) => r.data);
}

/** 이관 이력 목록 — BE: GET /api/v1/imports?status=&page=&size=. 정렬은 서버가 고정한다. */
export function listImportHistory(
  params: ListImportHistoryParams = {},
): Promise<PageResponse<ImportHistoryItem>> {
  return apiClient
    .get<PageResponse<ImportHistoryItem>>('/imports', { params })
    .then((r) => r.data);
}

/** 이관 이력 상세 — BE: GET /api/v1/imports/{trnsfSn}. 실패 사유는 여기에만 있다. */
export function getImportHistoryDetail(trnsfSn: number): Promise<ImportHistoryDetail> {
  return apiClient.get<ImportHistoryDetail>(`/imports/${trnsfSn}`).then((r) => r.data);
}

/** 분류 대응 목록 — BE: GET /api/v1/import-mappings. 쪽 단위로만 돌려받는다. */
export function listImportMappings(
  params: ListImportMappingsParams = {},
): Promise<ImportMappingListResult> {
  return apiClient
    .get<ImportMappingListResult>('/import-mappings', { params })
    .then((r) => r.data);
}

/** 분류 대응 확정 — BE: POST /api/v1/import-mappings. 사람이 고른 값만 실어 보낸다. */
export function createImportMappings(
  body: ImportMappingCreateRequest,
): Promise<ImportMappingSaveResult> {
  return apiClient
    .post<ImportMappingSaveResult>('/import-mappings', body)
    .then((r) => r.data);
}

/**
 * 분류 대응 해제 — BE: DELETE /api/v1/import-mappings/{mpngSn} (204).
 *
 * 행을 지우지 않고 쓰지 않음으로 표시한다. 되돌리면 그 분류가 다시 처음 보는 분류가 되어
 * 다음 산출물을 가져올 때 적재가 막히므로, 화면은 확인 단계를 거친 뒤에만 이 함수를 부른다.
 */
export function disableImportMapping(mpngSn: number): Promise<void> {
  return apiClient.delete(`/import-mappings/${mpngSn}`).then(() => undefined);
}

/**
 * 비식별 완료 기록 — BE: POST /api/v1/videos/{rawSn}/deident-complete.
 *
 * 이 요청은 비식별을 수행하지 않는다. 밖에서 비식별한 산출물이 어디 있는지를 알리고, 그 산출물이
 * 실제로 있는지 서버가 확인한 뒤에만 기록이 성립한다.
 */
export function recordDeidentComplete(
  rawSn: number,
  body: DeidentCompleteRequest,
): Promise<DeidentCompleteResult> {
  return apiClient
    .post<DeidentCompleteResult>(`/videos/${rawSn}/deident-complete`, body)
    .then((r) => r.data);
}

/**
 * 탐색 두 창구의 질의 파라미터를 만든다.
 *
 * 값이 없는 축은 **키 자체를 싣지 않는다** — 빈 문자열을 실으면 「주지 않았다」가 아니라
 * 「빈 자리에서 이어받아 달라」로 읽힐 여지가 생긴다.
 */
function browseParams(
  path: string | null | undefined,
  cursor: string | null | undefined,
): Record<string, string> {
  const params: Record<string, string> = {};
  if (path) params.path = path;
  if (cursor) params.cursor = cursor;
  return params;
}

/**
 * 이관 대상 폴더 탐색 — BE: GET /api/v1/imports/folders?path=&cursor=.
 *
 * `path` 를 생략하면 허용 저장소 루트 목록을 돌려받는다. 이 창구는 직접 입력을 **대신하지 않고
 * 함께 두는 보조 수단**이므로, 응답하지 않아도 사용자는 경로를 적어 검사할 수 있어야 한다.
 *
 * 허용 저장소 범위 판정은 검사·적재와 같은 규칙을 서버가 소유한다 — 화면이 허용 목록을 따로
 * 갖지 않는다(목록이 두 벌이 되면 한쪽만 넓어진다).
 *
 * `cursor` 는 **이어받을 자리**다. 앞선 응답의 `nextCursor` 를 그대로 다시 준다. 주지 않으면
 * 그 폴더의 처음부터 본다. 서버는 그 자리 **뒤부터** 이어 살펴보므로(배타) 준 이름 자체는
 * 다시 담겨 오지 않는다.
 *
 * @design API-221
 */
export function listImportFolders(
  path?: string | null,
  cursor?: string | null,
): Promise<ImportBrowseResult> {
  return apiClient
    .get<ImportBrowseResult>('/imports/folders', { params: browseParams(path, cursor) })
    .then((r) => r.data);
}

/**
 * 이관 대상 영상 파일 탐색 — BE: GET /api/v1/imports/files?path=&cursor=.
 *
 * `path` 는 필수다(루트 목록 조회가 없다). 폴더 탐색이 돌려준 값을 그대로 쓴다.
 * 돌아오는 것은 영상 파일뿐이라, 고른 값이 원본 영상 위치 판정을 통과하지 못하는 일이 없다.
 *
 * `cursor` 의 뜻과 배타 규약은 폴더 축과 같다.
 *
 * @design API-222
 */
export function listImportVideoFiles(
  path: string,
  cursor?: string | null,
): Promise<ImportBrowseResult> {
  return apiClient
    .get<ImportBrowseResult>('/imports/files', { params: browseParams(path, cursor) })
    .then((r) => r.data);
}
