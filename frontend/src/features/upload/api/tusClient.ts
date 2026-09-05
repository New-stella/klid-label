// TUS 1.0 재개 가능 업로드 — 경량 자체 구현 (외부 tus-js-client 미사용).
//
// 프로토콜: POST(세션 생성) → PATCH 루프(청크 append) → 재개(HEAD 로 offset 조회 후 이어서).
// BE: /api/v1/uploads (Spring context-path /api + @RequestMapping /v1/uploads).
//
// 보안:
// - 모든 응답/요청은 헤더 기반(Tus-Resumable / Upload-Offset / Location). ApiResponse 래퍼 미사용.
// - filename 은 base64(Upload-Metadata)로 표시용만 전송 — 저장명은 BE 가 UUID 강제(CWE-22).
// - apiClient 인터셉터가 인증 토큰을 주입. 세션 소유자 검증은 BE(USER_NO) 가 수행.

import { adminSessionHeaders } from '@/features/adminSession/api';
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
  /**
   * 관리자 단기 유효창 토큰 — **세션 생성(POST) 1회에만** 실린다. [@design API-158] [@design ADR-046]
   *
   * ★청크(PATCH)·취소(DELETE)·offset(HEAD)에는 싣지 않는다. 대용량 영상 업로드는 유효창(기본
   * 10분)을 넘기기 마련이라 청크마다 요구하면 <b>업로드가 도중에 끊긴다</b>. 그래도 안전한 이유는
   * 업로드 세션의 <b>소유자 검증이 이미 완비돼 있기</b> 때문이다 — 서버가 세션의 사용자 번호를
   * 토큰 주체와 대조하므로 업로드 ID 를 알아도 소유자가 아니면 이어 쓸 수 없고, 그 소유자는
   * 유효창을 연 바로 그 사람이다.
   *
   * ⚠ 포털 업로드(`/portal/uploads/tus`)는 이 값을 넘기지 않는다 — 포털 이용자 본인 자산 업로드라
   *   대상이 아니며, 묶으면 그 기능이 통째로 죽는다.
   */
  adminSessionToken?: string;
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
  adminSessionToken?: string,
): Promise<TusCreateResult> {
  const body = createPayload ?? null;
  const headers: Record<string, string> = {
    'Tus-Resumable': TUS_VERSION,
    'Upload-Length': String(file.size),
    // 관리자 유효창은 **여기(세션 생성)에만** 실린다 — 아래 청크·취소·offset 에는 붙이지 않는다.
    ...adminSessionHeaders(adminSessionToken),
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

/**
 * 청크 PATCH 제한시간의 **전송량과 무관한 고정분**(ms).
 *
 * 서버가 청크를 다 받은 뒤 하는 일(64KB 버퍼로 파일 append, 누적 offset 검증, 세션 원장 갱신)과
 * 연결 수립·TTFB 를 덮는다. 마지막 조각이 수 KB 여도 이만큼은 준다 — 전송량만으로 계산하면
 * 작은 조각에 사실상 0 에 가까운 상한이 붙어 서버가 멀쩡히 기록 중인데 끊긴다.
 */
export const TUS_CHUNK_UPLOAD_BASE_MS = 30_000;

/**
 * 청크 PATCH 제한시간의 **1MB 당 가산분**(ms) = 상향 대역 가정 1Mbps.
 *
 * ★ 이 저장소의 대역 기준은 **5Mbps**(출처: `DATAMART_DOWNLOAD_TIMEOUT_MS` ·
 *   `UPLOAD_FILE_DOWNLOAD_TIMEOUT_MS` · backend `spring.mvc.async.request-timeout` 주석)인데
 *   그것은 전부 **내려받기(하향)** 기준이다. **상향 대역은 하향보다 좁으므로** 그 값을 그대로 쓰면
 *   업로드를 낙관적으로 잡는 것이 된다. 그래서 하향 기준의 **1/5**(1Mbps)을 상향 가정으로 삼는다.
 *   1MB = 8Mb ÷ 1Mbps = **8초**.
 *   ⚠ 이 1/5 배수는 저장소에 선례가 없는 **판단값**이다(하향 기준만 선례가 있다). 실측 회선이 확인되면
 *     그 값으로 대체할 것 — 근거 없이 «너무 길다» 로 줄이면 원래 결함으로 되돌아간다.
 *
 * ★ 기본 청크 8MB 기준 30 + 64 = **94초**. 공용 기본값(30초)은 8MB 를 올리는 데 **2.13Mbps 이상
 *   업링크**를 요구했고, 그에 미달하면 **매 청크가 같은 벽에 부딪혀** 재개 업로드가 이어붙기만 할 뿐
 *   진행이 영구 정체했다. 서버 청크 상한 16MB 기준으로도 30 + 128 = 158초로 덮인다.
 */
export const TUS_CHUNK_UPLOAD_MS_PER_MB = 8_000;

/**
 * 청크 PATCH 1회의 제한시간(ms) — **실제로 보내는 청크 크기**로 계산한다.
 *
 * 고정값을 쓰지 않는 이유: 청크 크기는 호출측이 정하고(`TusUploadOptions.chunkSize`, 서버 상한 16MB)
 * 마지막 조각은 그보다 작다. 하나로 묶으면 큰 청크엔 모자라고 작은 조각엔 과하다.
 *
 * ⚠ 입력을 신뢰하지 않는다 — 0·음수·비유한 값은 고정분만 준다(0 이나 NaN 제한시간은 각각
 *   «즉시 끊김» 과 «무제한» 이라 둘 다 사고다).
 *
 * ⚠ **서버 쪽 상한과의 관계** — 이 요청은 **동기** `@PatchMapping` 이라 비동기 응답 제한
 *   (`spring.mvc.async.request-timeout`, 30분)이 **적용되지 않는다.** 서버에 요청 처리를 끊는 상한이
 *   따로 없으므로 **실효 상한은 이 값**(과 앞단 프록시 상한 중 작은 쪽)이다.
 */
export function tusChunkTimeoutMs(chunkBytes: number): number {
  if (!Number.isFinite(chunkBytes) || chunkBytes <= 0) return TUS_CHUNK_UPLOAD_BASE_MS;
  const mb = chunkBytes / (1024 * 1024);
  return TUS_CHUNK_UPLOAD_BASE_MS + Math.ceil(TUS_CHUNK_UPLOAD_MS_PER_MB * mb);
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
    // 공용 기본값(30초)을 덮어쓴다 — 위 tusChunkTimeoutMs 주석 참조. 빼면 8MB 청크가 2.13Mbps
    // 미만 업링크에서 매번 끊겨 재개 업로드가 영구 정체한다(내부·포털 두 경로 공통).
    timeout: tusChunkTimeoutMs(chunk.size),
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
    adminSessionToken,
  } = opts;

  let uploadId = opts.resumeUploadId;
  let offset = 0;
  let ingestStatus: string | undefined;
  if (uploadId) {
    // 재개 — 서버 offset 으로 동기화 (네트워크 중단 후 신뢰 가능한 진실).
    offset = await fetchOffset(uploadId, endpointBase);
  } else {
    const created = await createUpload(
      file,
      metadata,
      endpointBase,
      createPayload,
      adminSessionToken,
    );
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
