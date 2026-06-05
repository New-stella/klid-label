// 게시판(공지) 도메인 API — BE: /v1/notices (apiClient baseURL = /api/v1 → 상대 경로 /notices)
//
// 보안:
// - 모든 입력은 zod noticeSchema 로 검증 후 호출 (XSS/Injection 1차 방어)
// - 사용자 입력은 axios params/body/FormData 로만 전달 — 문자열 직접 연결/URL 구성 금지
// - 다운로드 파일명은 BE Content-Disposition(RFC 5987) 을 신뢰 (사용자 입력 미반영)

import { apiClient } from '@/lib/api/client';
import type { PageResponse } from '@/lib/api/types';

import { noticeSchema } from './schemas';
import type {
  Notice,
  NoticeAttach,
  NoticeForm,
  NoticeListParams,
  NoticeSummary,
} from './types';

/** 공지 목록 조회 (고정 우선 → 최신순, BE 정렬). field/keyword 는 선택. */
export function listNotices(params: NoticeListParams) {
  return apiClient
    .get<PageResponse<NoticeSummary>>('/notices', { params })
    .then((r) => r.data);
}

/** 공지 상세 조회 (본문 + 첨부 목록). */
export function getNotice(id: number) {
  return apiClient.get<Notice>(`/notices/${id}`).then((r) => r.data);
}

/** 공지 작성 (REVIEWER) — 기본 DRAFT. zod 검증 실패는 rejected promise 로 전파. */
export function createNotice(form: NoticeForm) {
  return Promise.resolve()
    .then(() => noticeSchema.parse(form))
    .then(() => apiClient.post<Notice>('/notices', form))
    .then((r) => r.data);
}

/** 공지 수정 (REVIEWER). zod 검증 실패는 rejected promise 로 전파. */
export function updateNotice(id: number, form: NoticeForm) {
  return Promise.resolve()
    .then(() => noticeSchema.parse(form))
    .then(() => apiClient.put<Notice>(`/notices/${id}`, form))
    .then((r) => r.data);
}

/** 공지 삭제 (REVIEWER). */
export function deleteNotice(id: number) {
  return apiClient.delete(`/notices/${id}`).then(() => undefined);
}

/** 공지 발행 (REVIEWER) — 멱등. */
export function publishNotice(id: number) {
  return apiClient.post<Notice>(`/notices/${id}/publish`).then((r) => r.data);
}

/** 공지 발행취소 (REVIEWER) — 멱등. */
export function unpublishNotice(id: number) {
  return apiClient.post<Notice>(`/notices/${id}/unpublish`).then((r) => r.data);
}

/** 첨부파일 업로드 (REVIEWER) — multipart/form-data, 파트명 file. */
export function uploadAttachment(id: number, file: File) {
  const formData = new FormData();
  formData.append('file', file);
  return apiClient
    .post<NoticeAttach>(`/notices/${id}/attachments`, formData)
    .then((r) => r.data);
}

/** 첨부파일 삭제 (REVIEWER). */
export function deleteAttachment(id: number, attachId: number) {
  return apiClient
    .delete(`/notices/${id}/attachments/${attachId}`)
    .then(() => undefined);
}

/**
 * 첨부파일 다운로드 (REVIEWER/WORKER).
 *
 * blob 응답을 받아 Content-Disposition 의 원본 파일명으로 저장한다.
 * 파일명은 BE 가 RFC 5987(filename*) 로 인코딩한 값을 우선 사용하고, 파싱 실패 시 fallback 사용.
 */
export function downloadAttachment(
  id: number,
  attachId: number,
  fallbackName: string,
): Promise<void> {
  return apiClient
    .get(`/notices/${id}/attachments/${attachId}/download`, {
      responseType: 'blob',
      // ApiResponse 래핑 우회 — blob 응답을 그대로 받는다.
      transformResponse: (raw) => raw,
    })
    .then((res) => {
      const data = res.data as unknown;
      const blob =
        data instanceof Blob
          ? data
          : new Blob([typeof data === 'string' ? data : JSON.stringify(data)]);
      const disposition =
        (res.headers?.['content-disposition'] as string | undefined) ?? '';
      const fileName = parseContentDispositionFilename(disposition) ?? fallbackName;
      triggerBrowserDownload(blob, fileName);
    });
}

/**
 * Content-Disposition 헤더에서 파일명 추출.
 * RFC 5987 `filename*=UTF-8''...` 우선, 없으면 `filename="..."` fallback.
 */
function parseContentDispositionFilename(disposition: string): string | null {
  if (!disposition) return null;
  // filename*=UTF-8''%ED%95%9C... (RFC 5987)
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
