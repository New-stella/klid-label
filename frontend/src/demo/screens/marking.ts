import type MockAdapter from 'axios-mock-adapter';

import { formatMarkTimestamp } from '@/features/portal/uploads/markingPlan';
import type {
  MarkItem,
  PortalMarkingList,
  PortalMarkingSaveResult,
  PortalStreamUrl,
} from '@/features/portal/uploads/markingTypes';
import type { PortalUploadDetail } from '@/features/portal/uploads/types';

import { fail, ok, pending } from '../mockReply';

/* 업로드 영상 마킹 견본 — 스토리북(6010)과 시연판이 같이 쓴다.
   값은 포털 그림 스토리의 목업(KLID_Portal src/mocks/authoring.ts)과 같다 */

/** 내 업로드의 그 자산 한 편 — 03:49 · 초당 30 프레임 */
export const 자산번호 = 4;

export const FPS = 30;

/** 마킹 대기 중인 자산 */
export const DETAIL: PortalUploadDetail = {
  uldSn: 자산번호,
  uldTypeCd: 'VIDEO',
  orgnlFileNm: '실종자추적_v0.7_20260910.mp4',
  fileSz: 32_925_286, // 31.4 MB
  mimeTypeNm: 'video/mp4',
  uldSttsCd: 'UPLOADED',
  frmeCnt: null,
  vdoLenSec: 229,
  fps: FPS,
  regDt: '2026-09-11T10:49:00',
  mdfcnDt: null,
  frames: [],
  expiresAt: '2026-09-18T10:49:00',
};

/** 재생 주소 — 공개 폴더의 견본 영상(03:49 · 30fps) */
export const STREAM: PortalStreamUrl = {
  url: '/samples/sample-video.webm',
  expiresAt: 4102444800,
  ttlSeconds: 300,
};

/** 프레임 번호 → 지점 한 건 (표시 시각은 화면과 같은 규칙) */
export const markAt = (frameIndex: number): MarkItem => ({
  frameIndex,
  timestamp: formatMarkTimestamp(frameIndex / FPS),
});

/** 자동 간격 300 프레임으로 뽑은 지점 — 03:49 영상이면 23건 */
export const SAVED_MARKS: MarkItem[] = Array.from({ length: 23 }, (_, i) => markAt(i * 300));

/** 지점이 너무 많은 견본의 간격 — 10 프레임마다면 687건이라 목록 상한(600)을 넘는다 */
export const MANY_INTERVAL = '10';

/** 수동으로 찍은 지점(프레임 번호) — 재생하다 Space 로 찍은 모습. 세 번째 지점을 골라 둔다 */
export const MANUAL_FRAMES = [95, 610, 1488, 2230, 3705, 5120];

/** 저장 직후 일부만 쓰인 견본 — 요청한 23건 가운데 20건 */
export const PARTIAL_USED = 20;

export const saveResult = (
  marks: MarkItem[],
  requested: number,
  uldSn = 자산번호,
): PortalMarkingSaveResult => ({
  markingSn: 9,
  uldSn,
  mode: 'AUTO',
  interval: 300,
  marks,
  markCount: marks.length,
  requestedMarkCount: requested,
  truncated: marks.length < requested,
  uldSttsCd: 'PROCESSING',
  regDt: '2026-09-15T10:00:00',
});

export const markingList = (marks: MarkItem[] | null, uldSn = 자산번호): PortalMarkingList => ({
  uldSn,
  markings: marks
    ? [{ markingSn: 9, mode: 'AUTO', interval: 300, marks, markCount: marks.length, regDt: '2026-09-15T10:00:00' }]
    : [],
});

export interface 마킹응답옵션 {
  /** 어느 자산의 마킹인가 — 기본은 견본 자산 */
  uldSn?: number;
  /** 자산 상세 — 바꿀 값만 준다. `pending` 이면 오지 않는다 */
  detail?: Partial<PortalUploadDetail> | 'pending';
  /** 재생 주소 — 기본은 견본 영상 */
  stream?: 'ok' | 'pending' | 'fail' | 'not-found';
  /** 이미 저장된 지점 */
  saved?: MarkItem[];
  /** 마킹 저장 — 저장에 성공하면 자산은 추출 중이 되고 저장된 지점이 조회된다 */
  save?: 'pending' | 'fail' | PortalMarkingSaveResult;
}

/** 마킹 화면이 부르는 응답 전부를 건다 */
export function 마킹응답(
  mock: MockAdapter,
  { uldSn = 자산번호, detail = {}, stream = 'ok', saved, save }: 마킹응답옵션 = {},
) {
  const 자산주소 = `/portal/uploads/${uldSn}`;
  let 저장결과: PortalMarkingSaveResult | null = null;

  if (detail === 'pending') mock.onGet(자산주소).reply(() => pending());
  else
    mock.onGet(자산주소).reply(() =>
      ok({ ...DETAIL, uldSn, ...detail, ...(저장결과 ? { uldSttsCd: 저장결과.uldSttsCd } : {}) }),
    );

  const streamPath = `${자산주소}/stream-url`;
  if (stream === 'pending') mock.onGet(streamPath).reply(() => pending());
  else if (stream === 'fail') mock.onGet(streamPath).reply(...fail(500));
  else if (stream === 'not-found') mock.onGet(streamPath).reply(...fail(404, null, 'NOT_FOUND'));
  else mock.onGet(streamPath).reply(...ok(STREAM));

  mock
    .onGet(`${자산주소}/markings`)
    .reply(() => ok(markingList(저장결과 ? 저장결과.marks : (saved ?? null), uldSn)));

  if (save === 'pending') mock.onPost(`${자산주소}/markings`).reply(() => pending());
  // 실패 문구는 서버가 준다 — 서버의 내부 오류 응답 그대로(화면의 「마킹을 저장하지 못했습니다」는 서버 문구가 없을 때만 쓰인다)
  else if (save === 'fail')
    mock.onPost(`${자산주소}/markings`).reply(...fail(500, '서버 내부 오류가 발생했습니다.', 'INTERNAL_ERROR'));
  else if (save)
    mock.onPost(`${자산주소}/markings`).reply(() => {
      저장결과 = save;
      return [201, ok(save)[1]];
    });
}
