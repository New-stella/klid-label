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
 * 첨부 다운로드 응답의 제한시간(ms).
 *
 * ★ 공용 기본값(30초)을 그대로 쓰면 **상한에 가까운 첨부가 정상인데도 끊긴다.** 첨부 상한은 20MB 이고
 *   (BE `NoticeAttachService.MAX_FILE_SIZE`) 브라우저 XHR 의 timeout 은 **응답이 끝날 때까지의 총
 *   경과 시간**이라 전송 시간이 그대로 잡힌다. 이 저장소가 다른 내려받기 경로에서 쓰는 보수적
 *   실효 대역 **5Mbps**(출처: `DATAMART_DOWNLOAD_TIMEOUT_MS` · `UPLOAD_FILE_DOWNLOAD_TIMEOUT_MS` ·
 *   backend `spring.mvc.async.request-timeout` 주석)로 계산하면
 *   20MB = 160Mb ÷ 5Mbps = **32초** — 기본값 30초를 아슬아슬하게 넘긴다.
 *
 * ★ 왜 60초인가 — 위 32초에 여유를 얹은 값이다. 여유가 덮는 것은 전송 자체가 아니라 앞뒤의 서버측
 *   작업이다(권한 검증, 첨부 원장 조회, 디스크 열기, TTFB). 형제 경로들이 5Mbps 계산값에 1.0~1.3배
 *   여유를 둔 것과 같은 계산이며, 이 경로는 절대 시간이 짧아 여유의 절대량이 작아지므로 배수를 더 준다.
 *
 * ★ 왜 0(무제한)이 아닌가 — 연결이 조용히 멈췄을 때 버튼이 영구히 잠기는 것을 끝내 주는 최후 장치다.
 *
 * ⚠ **서버 쪽 상한과의 관계** — 이 응답은 **동기**(`ResponseEntity<Resource>`)라 비동기 스트리밍
 *   응답의 제한(`spring.mvc.async.request-timeout`, 30분)이 **이 경로에 적용되지 않는다.** 그 키가
 *   적용되는 것은 `StreamingResponseBody` 를 쓰는 포털 작업 데이터 내려받기뿐이다. 서버에는 이 경로를
 *   끊는 상한이 따로 없으므로 **실효 상한은 이 값**(과 앞단 프록시 상한 중 작은 쪽)이다.
 */
export const NOTICE_ATTACHMENT_DOWNLOAD_TIMEOUT_MS = 60_000;

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
      // 공용 기본값(30초)을 덮어쓴다 — 위 상수 주석 참조. 빼면 20MB 첨부가 32초에 끊긴다.
      timeout: NOTICE_ATTACHMENT_DOWNLOAD_TIMEOUT_MS,
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
