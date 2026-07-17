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

/** 브라우저 다운로드 트리거 — a[download] + ObjectURL. 사용 후 즉시 revoke. */
function triggerBrowserDownload(blob: Blob, fileName: string): void {
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = fileName;
  document.body.appendChild(anchor);
  anchor.click();
  document.body.removeChild(anchor);
  URL.revokeObjectURL(url);
}

/** 응답 헤더/데이터를 Blob 으로 정규화(jsdom+mock 은 string 으로 떨어질 수 있어 방어). */
function toBlob(data: unknown): Blob {
  return data instanceof Blob
    ? data
    : new Blob([typeof data === 'string' ? data : JSON.stringify(data)]);
}

/**
 * 라벨 내보내기(JSON) 다운로드.
 *
 * 보안: apiClient(blob) 경유 — Authorization 헤더 자동 첨부. `<a href>` 직링크는 토큰이
 * 미첨부되어 401 이므로 사용 금지. 파일명은 BE Content-Disposition(서버 생성 고정명) 신뢰.
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
 * 원본 파일 다운로드.
 *
 * 보안: apiClient(blob) 경유 — Authorization 자동 첨부. 파일명은 BE Content-Disposition 우선,
 * 없으면 화면이 보유한 원본 파일명(fallback). 사용자 입력 경로/URL 미구성.
 */
export function downloadUploadFile(uldSn: number, fallbackName: string): Promise<void> {
  return apiClient
    .get(`/portal/uploads/${uldSn}/file`, {
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
 * Content-Disposition 헤더에서 파일명 추출. RFC 5987 `filename*=UTF-8''...` 우선,
 * 없으면 `filename="..."` fallback. (notice 다운로드와 동일 규칙 — 로컬 재구현.)
 */
function parseContentDispositionFilename(disposition: string): string | null {
  if (!disposition) return null;
  const extended = /filename\*=(?:UTF-8'')?([^;]+)/i.exec(disposition);
  if (extended?.[1]) {
    try {
      return decodeURIComponent(extended[1].trim().replace(/^"|"$/g, ''));
    } catch {
      // decode 실패 시 plain filename 으로 폴백
    }
  }
  const plain = /filename="?([^";]+)"?/i.exec(disposition);
  if (plain?.[1]) return plain[1].trim();
  return null;
}
