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
/** 기본 TUS endpoint base — 관제 내부 업로드(/uploads). 포털은 '/portal/uploads/tus' 주입. */
export const DEFAULT_TUS_ENDPOINT = '/uploads';

// 관제 내부 업로드 메타(vmsClipId 등)는 포털에서 미사용이라 모두 optional 로 둔다.
// encodeMetadata 가 undefined/빈값을 필터링하므로 포털은 filename 만 전송한다(BE 요구 필드와 일치).
export interface TusMetadata {
  filename?: string;
  vmsClipId?: string;
  cctvId?: string;
  eventTypeCd?: string;
  localGovCd?: string;
  prvcTypeCd?: string;
  capturedAt?: string; // ISO-8601
}

/**
 * 내부 업로드(/uploads) 세션 생성 JSON 바디 — 관제 인입 29컬럼 재현.
 *
 * 이 바디를 넘길 때만 POST 가 `application/json` 으로 나가고 Upload-Metadata 헤더는 생략된다.
 * 포털(/portal/uploads/tus)은 이 인자를 넘기지 않으므로 **기존 헤더 방식 그대로**다.
 *
 * 서버가 이미 아는 값(파일크기·파일형식)과 비운 기술메타는 보내지 않아도 된다 —
 * BE 가 Upload-Length/확장자로 채우고, 나머지 빈 키는 적재 후 ffprobe 가 키 단위로 채운다.
 */
export interface InternalUploadCreatePayload {
  fileName: string;
  vmsClipId: string;
  cctvId: string;
  lclgvCd: string;
  srcType?: string;
  /** `YYYY-MM-DDTHH:mm` (LocalDateTime — timezone 없음). */
  shtDt?: string;
  fileFmt?: string;
  vdoCdc?: string;
  fileSz?: number;
  lclgvNm?: string;
  vdoLenSec?: number;
  fps?: string;
  frmeCnt?: number;
  asprtRt?: string;
  wdth?: number;
  vrtc?: number;
  resl?: string;
  bit?: string;
  pxl?: string;
  wgs84Lat?: number;
  wgs84Lot?: number;
  cctvNm?: string;
  cctvHgt?: number;
  mainSurvPanAng?: number;
  evntId?: string;
  evntNm?: string;
  /**
   * 이벤트유형코드(예 `EV01000101`) — 관제가 인입 평면값으로 싣는 값이며 적재 시
   * `LS_DATA_RAW.EVNT_TYPE_CD` 로 복사된다. 미지정이면 키를 보내지 않으며, 그 영상은 마킹
   * 진입에서 400 으로 막힌다. 검증이벤트유형과 축이 다르므로 서로 유도하지 않는다.
   */
  evntTypeCd?: string;
  mntrCn?: string;
  /**
   * 검증이벤트유형 — 외부 VLM 검증 API 의 `event_type`(6종 소문자 enum). 미지정이면 키를 보내지
   * 않는다. BE `InternalUploadCreateRequest` 는 `@JsonProperty` 없는 record 라 **자바 필드명이 곧
   * JSON 키**이므로 키 이름은 `vrfcEvntTypeCd` 여야 한다. [req: R7]
   */
  vrfcEvntTypeCd?: string;
}

export interface TusUploadOptions {
  file: File;
  metadata: TusMetadata;
  chunkSize?: number;
  /** 진행률(0~1) 콜백. */
  onProgress?: (uploaded: number, total: number) => void;
  /** 일시정지 신호 — true 반환 시 다음 청크 전송을 멈추고 현재 offset 을 반환한다. */
  shouldPause?: () => boolean;
  /** TUS endpoint base (기본 {@link DEFAULT_TUS_ENDPOINT}). 포털은 '/portal/uploads/tus'. */
  endpointBase?: string;
  /** 내부 업로드 전용 — 세션 생성 JSON 바디(관제 인입 29컬럼). 포털은 미지정(헤더 방식 유지). */
  createPayload?: InternalUploadCreatePayload;
}

export interface TusUploadResult {
  /** 완료 시 BE 가 발급한 업로드 세션 식별자. */
  uploadId: string;
  /** 업로드 종료 시점의 offset. */
  uploadOffset: number;
  /** 전체 업로드 완료 여부 (false = 일시정지로 중단). */
  completed: boolean;
  /**
   * 인입 대기 상태(`X-Ingest-Status`) — 내부 업로드에서만 내려온다.
   * `PENDING_SCAN_DISABLED` 면 인입 폴링이 꺼져 있어 **영영 적재되지 않는다**(운영 형상 오류).
   */
  ingestStatus?: string;
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

/**
 * POST {base} — 세션 생성. Location 헤더에서 uploadId 추출.
 *
 * `createPayload` 를 넘기면 인입 메타를 **JSON 바디**로 보낸다(내부 업로드 전용).
 * 넘기지 않으면 기존 `Upload-Metadata` 헤더 방식이다(포털 업로드 — 동작 불변).
 */
export async function createUpload(
  file: File,
  metadata: TusMetadata,
  endpointBase: string = DEFAULT_TUS_ENDPOINT,
  createPayload?: InternalUploadCreatePayload,
): Promise<TusCreateResult> {
  const body = createPayload ?? null;
  const headers: Record<string, string> = {
    'Tus-Resumable': TUS_VERSION,
    'Upload-Length': String(file.size),
  };
  if (createPayload) {
    headers['Content-Type'] = 'application/json';
  } else {
    headers['Upload-Metadata'] = encodeMetadata(metadata);
  }
  const res = await apiClient.post(endpointBase, body, {
    headers,
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
  return { uploadId: id, ingestStatus: readIngestStatus(res.headers) };
}

/** 세션 생성 결과 — uploadId + (내부 업로드) 인입 대기 상태. */
export interface TusCreateResult {
  uploadId: string;
  /** `PENDING` | `PENDING_SCAN_DISABLED` | undefined(포털 등 미제공). */
  ingestStatus?: string;
}

/** BE 비표준 헤더 `X-Ingest-Status` — 업로드 완료 != 적재임을 화면에 드러내기 위한 신호. */
function readIngestStatus(headers: unknown): string | undefined {
  if (typeof headers !== 'object' || headers === null) return undefined;
  const h = headers as Record<string, unknown>;
  const raw = h['x-ingest-status'] ?? h['X-Ingest-Status'];
  return typeof raw === 'string' && raw !== '' ? raw : undefined;
}

/** HEAD {base}/{id} — 재개를 위한 현재 offset 조회. */
export async function fetchOffset(
  uploadId: string,
  endpointBase: string = DEFAULT_TUS_ENDPOINT,
): Promise<number> {
  const res = await apiClient.head(`${endpointBase}/${encodeURIComponent(uploadId)}`, {
    headers: { 'Tus-Resumable': TUS_VERSION },
  });
  const raw = (res.headers['upload-offset'] ?? res.headers['Upload-Offset']) as string | undefined;
  return raw ? Number(raw) : 0;
}

/** PATCH {base}/{id} — 단일 청크 append. 새 offset(+완료 시 인입 상태) 반환. */
async function patchChunk(
  uploadId: string,
  offset: number,
  chunk: Blob,
  endpointBase: string = DEFAULT_TUS_ENDPOINT,
): Promise<{ offset: number; ingestStatus?: string }> {
  const res = await apiClient.patch(`${endpointBase}/${encodeURIComponent(uploadId)}`, chunk, {
    headers: {
      'Tus-Resumable': TUS_VERSION,
      'Upload-Offset': String(offset),
      'Content-Type': 'application/offset+octet-stream',
    },
    transformResponse: (d) => d,
  });
  const raw = (res.headers['upload-offset'] ?? res.headers['Upload-Offset']) as string | undefined;
  return {
    offset: raw ? Number(raw) : offset + chunk.size,
    ingestStatus: readIngestStatus(res.headers),
  };
}

/** DELETE {base}/{id} — 세션 취소. */
export async function cancelUpload(
  uploadId: string,
  endpointBase: string = DEFAULT_TUS_ENDPOINT,
): Promise<void> {
  await apiClient.delete(`${endpointBase}/${encodeURIComponent(uploadId)}`, {
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
  const {
    file,
    metadata,
    chunkSize = DEFAULT_CHUNK_SIZE,
    onProgress,
    shouldPause,
    endpointBase = DEFAULT_TUS_ENDPOINT,
    createPayload,
  } = opts;

  let uploadId = opts.resumeUploadId;
  let offset = 0;
  let ingestStatus: string | undefined;
  if (uploadId) {
    // 재개 — 서버 offset 으로 동기화 (네트워크 중단 후 신뢰 가능한 진실).
    offset = await fetchOffset(uploadId, endpointBase);
  } else {
    const created = await createUpload(file, metadata, endpointBase, createPayload);
    uploadId = created.uploadId;
    ingestStatus = created.ingestStatus;
  }

  onProgress?.(offset, file.size);

  while (offset < file.size) {
    if (shouldPause?.()) {
      return { uploadId, uploadOffset: offset, completed: false, ingestStatus };
    }
    const end = Math.min(offset + chunkSize, file.size);
    const chunk = file.slice(offset, end);
    const patched = await patchChunk(uploadId, offset, chunk, endpointBase);
    offset = patched.offset;
    ingestStatus = patched.ingestStatus ?? ingestStatus;
    onProgress?.(offset, file.size);
  }

  return { uploadId, uploadOffset: offset, completed: offset >= file.size, ingestStatus };
}
