// Phase 5 — 포털 업로드/자산 API 모듈 (PORTAL_USER 전용).
//
// BE: kr.co.cudo.authoring.portal.controller.PortalUploadController (/v1/portal/uploads)
//   POST   /images              → 이미지 다중 업로드(multipart files[]) → 201 List
//   GET    /?type&page&size     → 본인 자산 목록(Page)
//   GET    /{uldSn}             → 자산 상세 + 프레임 요약
//   GET    /{uldSn}/frames      → 프레임 목록(Page)
//   DELETE /{uldSn}            → 자산 삭제(PROCESSING 이면 409)
//
// 보안: apiClient(baseURL /api/v1) 가 Authorization 자동 첨부 + ApiResponse unwrap. 경로/쿼리는
// axios 가 인코딩(문자열 연결 금지). IDOR(CWE-639) 방어는 BE 소유자 스코프 리포지토리가 담당.

import { apiClient } from '@/lib/api/client';
import {
  parseContentDispositionFilename,
  toDownloadBlob as toBlob,
  triggerBrowserDownload,
} from '@/lib/api/download';
import type { PageResponse } from '@/lib/api/types';

import type { PortalUpload, PortalUploadDetail, PortalUploadFrame } from './types';

export interface ListUploadsParams {
  /** IMAGE | VIDEO. 미지정 시 전체. */
  type?: string;
  page?: number;
  size?: number;
}

/** 진행률(0~1) 콜백. */
export type UploadProgressFn = (ratio: number) => void;

/**
 * 이미지 다중 업로드 — multipart `files` 필드. all-or-nothing(BE 가 전 파일 사전검증 통과 시에만 저장).
 * 저장명은 BE 가 UUID 강제, 원본명은 표시용만 보관한다(CWE-22).
 */
export function uploadImages(
  files: File[],
  onProgress?: UploadProgressFn,
): Promise<PortalUpload[]> {
  const form = new FormData();
  for (const f of files) {
    form.append('files', f);
  }
  return apiClient
    .post<PortalUpload[]>('/portal/uploads/images', form, {
      headers: { 'Content-Type': 'multipart/form-data' },
      onUploadProgress: (e) => {
        if (onProgress && e.total) {
          onProgress(Math.min(1, e.loaded / e.total));
        }
      },
    })
    .then((r) => r.data);
}

/** 본인 업로드 자산 목록(페이징). type 로 IMAGE/VIDEO 필터. */
export function listUploads(params: ListUploadsParams = {}): Promise<PageResponse<PortalUpload>> {
  return apiClient
    .get<PageResponse<PortalUpload>>('/portal/uploads', { params })
    .then((r) => r.data);
}

/** 자산 상세 + 프레임 요약. 본인 자산만(타 사용자/부재 자산은 403). */
export function getUpload(uldSn: number): Promise<PortalUploadDetail> {
  return apiClient
    .get<PortalUploadDetail>(`/portal/uploads/${uldSn}`)
    .then((r) => r.data);
}

/** 자산 프레임 목록(페이징). */
export function listFrames(
  uldSn: number,
  params: { page?: number; size?: number } = {},
): Promise<PageResponse<PortalUploadFrame>> {
  return apiClient
    .get<PageResponse<PortalUploadFrame>>(`/portal/uploads/${uldSn}/frames`, { params })
    .then((r) => r.data);
}

/** 자산 삭제. PROCESSING 상태면 BE 가 409(CONFLICT). */
export function deleteUpload(uldSn: number): Promise<void> {
  return apiClient.delete(`/portal/uploads/${uldSn}`).then(() => undefined);
}

// ── Phase 6 — 업로드 프레임 라벨 CRUD + 내보내기/원본 다운로드 ──────────────
// BE: kr.co.cudo.authoring.portal.controller.PortalUploadLabelController
//   GET /frames/{uldFrmeSn}/labels      → 프레임 라벨 목록
//   PUT /frames/{uldFrmeSn}/labels      → 전체교체(멱등). ⚠ body 는 **최상위 raw 배열**
//                                          [{lblTypeCd,label,points}] — {items:[...]} 래퍼 금지.
//   GET /{uldSn}/export                 → 라벨 JSON attachment
//   GET /{uldSn}/file                   → 원본 파일 attachment

/**
 * BE PortalUploadLabelResponse 미러. 좌표는 저장 JSON 을 파싱한 `[[x,y], ...]` 중첩 배열
 * (BBOX=2점, POLYGON=[[x,y]...]) — points 는 문자열이 아니라 number[][] 이다.
 */
export interface UploadFrameLabel {
  uldLblSn: number;
  uldFrmeSn: number;
  lblTypeCd: string;
  label: string | null;
  points: number[][];
  regDt: string;
  mdfcnDt: string | null;
}

/**
 * BE PortalUploadLabelRequest 미러 — PUT 전체교체 1행.
 * points 는 number[][] 로 그대로 전송한다(문자열 직렬화 금지 — axios 가 배열째 JSON 인코딩).
 */
export interface UploadLabelRequest {
  lblTypeCd: string;
  label: string;
  points: number[][];
}

/** 프레임 라벨 목록 조회(본인 자산만 — IDOR 방어는 BE). */
export function getUploadFrameLabels(uldFrmeSn: number): Promise<UploadFrameLabel[]> {
  return apiClient
    .get<UploadFrameLabel[]>(`/portal/uploads/frames/${uldFrmeSn}/labels`)
    .then((r) => r.data ?? []);
}

/**
 * 프레임 라벨 전체교체(PUT). body 는 최상위 raw 배열 — 빈 배열이면 전체 삭제(멱등).
 * READY 아닌 자산은 BE 가 409. 라벨≤500/프레임·label≤80·BBOX 2점/POLYGON 3~200점 검증은 BE.
 */
export function replaceUploadFrameLabels(
  uldFrmeSn: number,
  labels: UploadLabelRequest[],
): Promise<UploadFrameLabel[]> {
  return apiClient
    .put<UploadFrameLabel[]>(`/portal/uploads/frames/${uldFrmeSn}/labels`, labels)
    .then((r) => r.data ?? []);
}

/**
 * 라벨 내보내기(JSON) 다운로드.
 *
 * 보안: apiClient(blob) 경유 — Authorization 헤더 자동 첨부. `<a href>` 직링크는 토큰이
 * 미첨부되어 401 이므로 사용 금지. 파일명은 BE Content-Disposition(서버 생성 고정명) 신뢰.
 *
 * ★ 형제 경로(원본 파일)와 달리 **전용 제한시간도 취소도 두지 않는다** — 라벨 JSON 은 작아서
 *   공용 기본값 안에 끝나고, 취소 버튼이 뜨기도 전에 완료된다(사양). 두면 «작아서 두지 않는다» 는
 *   판단이 코드에서 지워지고, 실제로는 아무도 쓰지 않는 조작만 늘어난다.
 */
export function downloadUploadExport(uldSn: number, fallbackName = `upload-${uldSn}.json`): Promise<void> {
  return apiClient
    .get(`/portal/uploads/${uldSn}/export`, {
      responseType: 'blob',
      transformResponse: (raw) => raw,
    })
    .then((res) => {
      const fileName =
        parseContentDispositionFilename(
          (res.headers?.['content-disposition'] as string | undefined) ?? '',
        ) ?? fallbackName;
      triggerBrowserDownload(toBlob(res.data as unknown), fileName);
    });
}

/**
 * 원본 파일 다운로드 응답의 제한시간(ms). @design SCREEN-034
 *
 * ★ 공용 기본값(30초)을 그대로 쓰면 **구조적으로 끊긴다.** 포털 업로드 영상은 최대 5GB 이고
 *   브라우저 XHR 의 timeout 은 **응답이 끝날 때까지의 총 경과 시간**이라 전송 시간이 그대로 잡힌다.
 *   회선이 아무리 좋아도 GB 급은 30초 안에 끝나지 않는다. 형제 경로(데이터마트 작업 데이터 묶음)에서
 *   이미 고친 것과 **똑같은 결함이 이 경로에 그대로 남아 있었다.**
 *
 * ★ 왜 3시간인가 — 상한인 5GB(=40,960Mb)를 보수적으로 잡은 실효 대역 5Mbps 에서 받아내는 데
 *   약 137분이 걸린다. 거기에 여유를 둔 값이다(형제 상수 `DATAMART_DOWNLOAD_TIMEOUT_MS` 가 약
 *   1.1GB 를 같은 대역으로 상정해 30분을 잡은 것과 같은 계산이며, 이 경로는 상한이 더 크다).
 *
 * ★ 왜 0(무제한)이 아닌가 — 연결이 조용히 멈췄을 때 버튼이 영구히 잠기는 것을 끝내 주는 최후 장치다.
 *   취소 조작이 생겼어도 그것은 **사용자가 화면을 보고 있을 때만** 동작하는 수동 장치라 대체가 아니다.
 *
 * ⚠ **경로 중 가장 작은 상한이 실제 상한이다.** 이 값만 늘려도 앞단 상한이 더 짧으면 여전히 잘린다.
 *   다만 **이 경로에 관여하는 계층을 정확히 알고 봐야 한다** —
 *   - 서버 응답이 **동기**(`ResponseEntity<Resource>`)라, 비동기 스트리밍 응답의 절대 제한시간
 *     (`spring.mvc.async.request-timeout`)은 **이 경로에 적용되지 않는다.** 그 키가 적용되는 것은
 *     `StreamingResponseBody` 를 쓰는 형제 경로(데이터마트 작업 데이터 묶음 내려받기)뿐이다.
 *     여기서 끊긴다고 그 설정을 만지면 엉뚱한 곳을 고치는 것이다.
 *   - 그래서 이 경로에 실제로 걸리는 상한은 **이 값**과 **앞단 프록시·게이트웨이의 응답 상한**이다.
 *     여기 값을 늘렸는데도 대용량이 끊긴다면 그 인프라 계층을 먼저 확인할 것.
 */
export const UPLOAD_FILE_DOWNLOAD_TIMEOUT_MS = 3 * 60 * 60 * 1000;

/**
 * 원본 파일 다운로드.
 *
 * 보안: apiClient(blob) 경유 — Authorization 자동 첨부. 파일명은 BE Content-Disposition 우선,
 * 없으면 화면이 보유한 원본 파일명(fallback). 사용자 입력 경로/URL 미구성.
 *
 * @param signal 사용자 취소용 중단 신호(선택). 넘기면 전송이 실제로 중단된다 — 싣지 않으면 화면의
 *   '취소' 는 버튼만 있고 전송은 계속되는 거짓 조작이 된다. ⚠ 중단되면 응답이 없어 일반 실패와 같은
 *   모양으로 올라오므로, **사용자 취소인지의 판정은 신호를 쥔 호출측(화면)이** 한다.
 */
export function downloadUploadFile(
  uldSn: number,
  fallbackName: string,
  signal?: AbortSignal,
): Promise<void> {
  return apiClient
    .get(`/portal/uploads/${uldSn}/file`, {
      responseType: 'blob',
      transformResponse: (raw) => raw,
      // 공용 기본값(30초)을 덮어쓴다 — 위 상수 주석 참조. 빼면 5GB 전송이 30초에 끊긴다.
      timeout: UPLOAD_FILE_DOWNLOAD_TIMEOUT_MS,
      signal,
    })
    .then((res) => {
      const fileName =
        parseContentDispositionFilename(
          (res.headers?.['content-disposition'] as string | undefined) ?? '',
        ) ?? fallbackName;
      triggerBrowserDownload(toBlob(res.data as unknown), fileName);
    });
}
