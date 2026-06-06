// TUS 1.0 재개 가능 업로드 — 경량 자체 구현 (외부 tus-js-client 미사용).
//
// 프로토콜: POST(세션 생성) → PATCH 루프(청크 append) → 재개(HEAD 로 offset 조회 후 이어서).
// BE: /api/v1/uploads (Spring context-path /api + @RequestMapping /v1/uploads).
//
// 보안:
// - 모든 응답/요청은 헤더 기반(Tus-Resumable / Upload-Offset / Location). ApiResponse 래퍼 미사용.
// - filename 은 base64(Upload-Metadata)로 표시용만 전송 — 저장명은 BE 가 UUID 강제(CWE-22).
// - apiClient 인터셉터가 인증 토큰을 주입. 세션 소유자 검증은 BE(USER_NO) 가 수행.

import { apiClient } from '@/lib/api/client';

const TUS_VERSION = '1.0.0';
/** 청크 크기 — 8MB (대용량 영상 재개 단위). */
export const DEFAULT_CHUNK_SIZE = 8 * 1024 * 1024;

export interface TusMetadata {
  filename?: string;
  vmsClipId: string;
  cctvId: string;
  eventTypeCd: string;
  localGovCd: string;
  prvcTypeCd: string;
  capturedAt: string; // ISO-8601
}

export interface TusUploadOptions {
  file: File;
  metadata: TusMetadata;
  chunkSize?: number;
  /** 진행률(0~1) 콜백. */
  onProgress?: (uploaded: number, total: number) => void;
  /** 일시정지 신호 — true 반환 시 다음 청크 전송을 멈추고 현재 offset 을 반환한다. */
  shouldPause?: () => boolean;
}

export interface TusUploadResult {
  /** 완료 시 BE 가 발급한 업로드 세션 식별자. */
  uploadId: string;
  /** 업로드 종료 시점의 offset. */
  uploadOffset: number;
  /** 전체 업로드 완료 여부 (false = 일시정지로 중단). */
  completed: boolean;
}

/** RFC: UTF-8 문자열을 base64 로 인코딩 (Upload-Metadata 규약). */
function b64(value: string): string {
  // btoa 는 latin1 만 처리하므로 UTF-8 → percent-encoding → 바이트 변환.
  return btoa(
    encodeURIComponent(value).replace(/%([0-9A-F]{2})/g, (_, p1) =>
      String.fromCharCode(parseInt(p1, 16)),
    ),
  );
}

/** TUS Upload-Metadata 헤더 직렬화 — `key b64value,key2 b64value2`. */
function encodeMetadata(meta: TusMetadata): string {
  const pairs: Array<[string, string | undefined]> = [
    ['filename', meta.filename],
    ['vmsClipId', meta.vmsClipId],
    ['cctvId', meta.cctvId],
    ['eventTypeCd', meta.eventTypeCd],
    ['localGovCd', meta.localGovCd],
    ['prvcTypeCd', meta.prvcTypeCd],
    ['capturedAt', meta.capturedAt],
  ];
  return pairs
    .filter(([, v]) => v !== undefined && v !== '')
    .map(([k, v]) => `${k} ${b64(v as string)}`)
    .join(',');
}

/** POST /uploads — 세션 생성. Location 헤더에서 uploadId 추출. */
export async function createUpload(file: File, metadata: TusMetadata): Promise<string> {
  const res = await apiClient.post('/uploads', null, {
    headers: {
      'Tus-Resumable': TUS_VERSION,
      'Upload-Length': String(file.size),
      'Upload-Metadata': encodeMetadata(metadata),
    },
    // 본 컨트롤러는 TUS 헤더 기반 — ApiResponse 언랩 인터셉터를 우회하기 위해 전체 응답 사용.
    transformResponse: (d) => d,
  });
  const location = (res.headers['location'] ?? res.headers['Location']) as string | undefined;
  if (!location) {
    throw new Error('TUS 세션 생성 응답에 Location 헤더가 없습니다.');
  }
  const id = location.split('/').pop();
  if (!id) {
    throw new Error('TUS Location 헤더에서 uploadId 를 추출할 수 없습니다.');
  }
  return id;
}

/** HEAD /uploads/{id} — 재개를 위한 현재 offset 조회. */
export async function fetchOffset(uploadId: string): Promise<number> {
  const res = await apiClient.head(`/uploads/${encodeURIComponent(uploadId)}`, {
    headers: { 'Tus-Resumable': TUS_VERSION },
  });
  const raw = (res.headers['upload-offset'] ?? res.headers['Upload-Offset']) as string | undefined;
  return raw ? Number(raw) : 0;
}

/** PATCH /uploads/{id} — 단일 청크 append. 새 offset 반환. */
async function patchChunk(
  uploadId: string,
  offset: number,
  chunk: Blob,
): Promise<number> {
  const res = await apiClient.patch(`/uploads/${encodeURIComponent(uploadId)}`, chunk, {
    headers: {
      'Tus-Resumable': TUS_VERSION,
      'Upload-Offset': String(offset),
      'Content-Type': 'application/offset+octet-stream',
    },
    transformResponse: (d) => d,
  });
  const raw = (res.headers['upload-offset'] ?? res.headers['Upload-Offset']) as string | undefined;
  return raw ? Number(raw) : offset + chunk.size;
}

/** DELETE /uploads/{id} — 세션 취소. */
export async function cancelUpload(uploadId: string): Promise<void> {
  await apiClient.delete(`/uploads/${encodeURIComponent(uploadId)}`, {
    headers: { 'Tus-Resumable': TUS_VERSION },
  });
}

/**
 * 파일 전체를 청크 단위로 업로드한다. {@link TusUploadOptions.startOffset} 가 주어지면
 * 해당 위치부터 재개한다. 일시정지 시 현재 offset 을 반환하고 completed=false 로 종료.
 *
 * @param resumeUploadId 재개 시 기존 세션 ID. 미지정 시 새 세션 생성.
 */
export async function uploadFile(
  opts: TusUploadOptions & { resumeUploadId?: string },
): Promise<TusUploadResult> {
  const { file, metadata, chunkSize = DEFAULT_CHUNK_SIZE, onProgress, shouldPause } = opts;

  let uploadId = opts.resumeUploadId;
  let offset = 0;
  if (uploadId) {
    // 재개 — 서버 offset 으로 동기화 (네트워크 중단 후 신뢰 가능한 진실).
    offset = await fetchOffset(uploadId);
  } else {
    uploadId = await createUpload(file, metadata);
  }

  onProgress?.(offset, file.size);

  while (offset < file.size) {
    if (shouldPause?.()) {
      return { uploadId, uploadOffset: offset, completed: false };
    }
    const end = Math.min(offset + chunkSize, file.size);
    const chunk = file.slice(offset, end);
    offset = await patchChunk(uploadId, offset, chunk);
    onProgress?.(offset, file.size);
  }

  return { uploadId, uploadOffset: offset, completed: offset >= file.size };
}
