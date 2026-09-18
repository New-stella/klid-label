import type MockAdapter from 'axios-mock-adapter';

import type { RequestUploadAugmentBody } from '@/features/portal/uploads/api';
import { PortalUploadStatus, PortalUploadType, type PortalUpload } from '@/features/portal/uploads/types';

import { ok, pageOf, pending } from '../mockReply';
import { OBJECTS } from './labeling';

/* 내 업로드 견본 — 스토리북(6010)과 시연판이 같이 쓴다 */

export const KB = 1024;
export const MB = KB * 1024;
export const GB = MB * 1024;

/** 준비 완료 — 마킹을 마쳐 프레임 23장을 뽑은 영상 */
export const READY: PortalUpload = {
  uldSn: 4,
  uldTypeCd: PortalUploadType.VIDEO,
  orgnlFileNm: '실종자추적_v0.7_20260910.mp4',
  fileSz: Math.round(31.4 * MB),
  mimeTypeNm: 'video/mp4',
  uldSttsCd: PortalUploadStatus.READY,
  frmeCnt: 23,
  frmeSn: 41,
  failRsnCn: null,
  regDt: '2026-09-11T10:49:00',
  expiresAt: '2026-09-18T10:49:00',
};

/** 고른 영상 — 아직 올리기 전 */
export const PICKED_NAME = 'M-02_v0.8_20260910.mp4';
export const PICKED_SIZE = 224 * KB;

/** 골라 둔 그 영상을 다 올린 뒤의 한 건 — 마킹 전이라 뽑힌 프레임이 없다 */
export const UPLOADED: PortalUpload = {
  uldSn: 5,
  uldTypeCd: PortalUploadType.VIDEO,
  orgnlFileNm: PICKED_NAME,
  fileSz: PICKED_SIZE,
  mimeTypeNm: 'video/mp4',
  uldSttsCd: PortalUploadStatus.UPLOADED,
  frmeCnt: null,
  frmeSn: null,
  failRsnCn: null,
  regDt: '2026-09-11T11:17:00',
  expiresAt: '2026-09-18T11:17:00',
};

/** 마킹을 마쳐 프레임을 뽑는 중 — 이때만 만료일이 없다 */
export const PROCESSING: PortalUpload = {
  uldSn: 3,
  uldTypeCd: PortalUploadType.VIDEO,
  orgnlFileNm: '침수도로_야간_v0.3_20260908.mp4',
  fileSz: Math.round(48.2 * MB),
  mimeTypeNm: 'video/mp4',
  uldSttsCd: PortalUploadStatus.PROCESSING,
  frmeCnt: null,
  frmeSn: null,
  failRsnCn: null,
  regDt: '2026-09-10T16:20:00',
  expiresAt: null,
};

/** 뽑다가 실패 — 서버가 사유를 주지 않아 화면의 기본 문구가 선다 */
export const FAILED: PortalUpload = {
  uldSn: 2,
  uldTypeCd: PortalUploadType.VIDEO,
  orgnlFileNm: '산불연기_v0.1_20260905.mp4',
  fileSz: Math.round(1.24 * GB),
  mimeTypeNm: 'video/mp4',
  uldSttsCd: PortalUploadStatus.FAILED,
  frmeCnt: null,
  frmeSn: null,
  failRsnCn: null,
  regDt: '2026-09-09T09:41:00',
  expiresAt: '2026-09-16T09:41:00',
};

/** 기본 목록 — 다 올린 영상이 맨 위 「마킹 대기」 */
export const UPLOADS = [UPLOADED, READY];

/** 목록 응답 — 줄이 없는 쪽만 다른 응답을 건다 */
export const 목록응답 = (mock: MockAdapter, uploads: PortalUpload[], total = uploads.length) =>
  mock.onGet('/portal/uploads').reply(200, ok(pageOf(uploads, total))[1]);

/* ── 올리기 ──────────────────────────────────────────────────────────────── */

export const TUS = '/portal/uploads/tus';
export const TUS_SESSION = /\/portal\/uploads\/tus\/.+/;
/** 끊기거나 멈춘 자리 — 224 KB 가운데 62% */
export const SENT = Math.round(PICKED_SIZE * 0.62);

/** 세션을 만들고, 첫 조각은 62% 까지 받았다고 답한다. 그다음 조각의 결말만 스토리가 정한다 */
export function 올리기(mock: MockAdapter, 다음조각: '멈춤' | '끊김' | '끝') {
  mock.onPost(TUS).reply(201, '', { location: `/api/v1${TUS}/story-upload` });
  if (다음조각 === '끝') {
    mock.onPatch(TUS_SESSION).reply(204, '', { 'upload-offset': String(PICKED_SIZE) });
    return;
  }
  mock.onPatch(TUS_SESSION).replyOnce(204, '', { 'upload-offset': String(SENT) });
  if (다음조각 === '멈춤') mock.onPatch(TUS_SESSION).reply(() => pending());
  else mock.onPatch(TUS_SESSION).networkError();
}

/* ── 시연판 ─────────────────────────────────────────────────────────────── */

const pad = (n: number) => String(n).padStart(2, '0');

/** 지금 시각 — 서버가 주는 모양(초까지, 시간대 없음) */
export function stamp(d = new Date()): string {
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
}

const DAY_MS = 24 * 60 * 60 * 1000;

/** 요청 머리글 하나를 읽는다 — 이름의 대소문자를 가리지 않는다 */
function header(headers: unknown, name: string): string | undefined {
  if (typeof headers !== 'object' || headers === null) return undefined;
  const h = headers as Record<string, unknown> & { get?: (k: string) => unknown };
  const v = typeof h.get === 'function' ? h.get(name) : (h[name] ?? h[name.toLowerCase()]);
  return v === undefined || v === null ? undefined : String(v);
}

/** 이어올리기 규약의 base64(UTF-8) 를 되돌린다 */
function decodeBase64Utf8(b64: string): string {
  const binary = atob(b64);
  return decodeURIComponent(
    Array.from(binary)
      .map((c) => '%' + c.charCodeAt(0).toString(16).padStart(2, '0'))
      .join(''),
  );
}

/** 세션을 만들 때 실린 파일 이름·크기 — 없으면 견본 값 */
function readPicked(headers: unknown): { name: string; size: number } {
  let name = PICKED_NAME;
  const meta = header(headers, 'Upload-Metadata');
  if (meta) {
    for (const pair of meta.split(',')) {
      const [key, value] = pair.trim().split(' ');
      if (key === 'filename' && value) {
        try {
          name = decodeBase64Utf8(value);
        } catch {
          // 읽지 못하면 견본 이름
        }
      }
    }
  }
  const length = Number(header(headers, 'Upload-Length'));
  return { name, size: Number.isFinite(length) && length > 0 ? length : PICKED_SIZE };
}

/** 견본 영상 한 벌 — 원본 파일 내려받기가 이걸 준다 */
let 견본영상: Promise<Blob> | null = null;
function sampleVideo(): Promise<Blob> {
  if (!견본영상) 견본영상 = fetch('/samples/sample-video.webm').then((r) => r.blob());
  return 견본영상;
}

export interface 내업로드시연옵션 {
  /** AI 증강 요청이 접수될 때 — 증강 목록에 붙이고 요청 번호를 돌려준다 */
  onAugment?: (
    uldSn: number,
    orgnlFileNm: string | null,
    generationCondition: Record<string, unknown>,
    requestedAt: string,
  ) => number;
}

/**
 * 시연판용 — 목록을 기억해 두어 올리기 · 삭제 · 증강 요청이 목록에 그대로 반영된다.
 * 돌려주는 목록은 살아 있는 값이라, 자산 상세 대역(`업로드자산대역`)이 새로 올린 줄도 찾는다.
 */
export function 내업로드시연(mock: MockAdapter, { onAugment }: 내업로드시연옵션 = {}): PortalUpload[] {
  const rows: PortalUpload[] = [...UPLOADS];
  let nextSn = Math.max(...rows.map((r) => r.uldSn)) + 1;

  mock.onGet('/portal/uploads').reply(() => ok(pageOf(rows)));

  // 올리기 — 세션을 만들 때 파일 이름·크기를 받아 두고, 첫 조각에 «다 받았다» 고 답하며 목록 맨 위에 붙인다
  let picked = { name: PICKED_NAME, size: PICKED_SIZE };
  mock.onPost(TUS).reply((config) => {
    picked = readPicked(config.headers);
    return [201, '', { location: `/api/v1${TUS}/demo-upload` }];
  });
  mock.onPatch(TUS_SESSION).reply(() => {
    const now = new Date();
    rows.unshift({
      ...UPLOADED,
      uldSn: nextSn++,
      orgnlFileNm: picked.name,
      fileSz: picked.size,
      regDt: stamp(now),
      expiresAt: stamp(new Date(now.getTime() + 7 * DAY_MS)),
    });
    return [204, '', { 'upload-offset': String(picked.size) }];
  });

  mock.onDelete(/^\/portal\/uploads\/(\d+)$/).reply((config) => {
    const sn = Number(/(\d+)$/.exec(config.url ?? '')?.[1]);
    const at = rows.findIndex((r) => r.uldSn === sn);
    if (at >= 0) rows.splice(at, 1);
    return [204];
  });

  // 라벨 내보내기 — 견본 객체를 담은 JSON 파일
  mock.onGet(/^\/portal\/uploads\/(\d+)\/export$/).reply((config) => {
    const sn = Number(/uploads\/(\d+)\/export$/.exec(config.url ?? '')?.[1]);
    const body = JSON.stringify({ uldSn: sn, labels: OBJECTS }, null, 2);
    return [
      200,
      new Blob([body], { type: 'application/json' }),
      { 'content-disposition': `attachment; filename="upload-${sn}-labels.json"` },
    ];
  });

  // 원본 파일 — 견본 영상
  mock.onGet(/^\/portal\/uploads\/(\d+)\/file$/).reply(async () => [
    200,
    await sampleVideo(),
    { 'content-disposition': 'attachment; filename="sample-video.webm"' },
  ]);

  // AI 증강 요청 — 접수하고 증강 목록에 붙인다
  mock.onPost(/^\/portal\/uploads\/(\d+)\/augments$/).reply((config) => {
    const sn = Number(/uploads\/(\d+)\/augments$/.exec(config.url ?? '')?.[1]);
    const body = JSON.parse(String(config.data ?? '{}')) as Partial<RequestUploadAugmentBody>;
    const requestedAt = stamp();
    const row = rows.find((r) => r.uldSn === sn);
    const augSn =
      onAugment?.(sn, row?.orgnlFileNm ?? null, { ...(body.generationCondition ?? {}) }, requestedAt) ?? 901;
    return ok({ augSn, uldSn: sn, requestedAt });
  });

  return rows;
}
