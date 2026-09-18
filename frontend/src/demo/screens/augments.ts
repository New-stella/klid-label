import type MockAdapter from 'axios-mock-adapter';

import type { PortalAugmentDetail, PortalAugmentSummary } from '@/features/portal/augments/types';
import type { PortalUploadDetail } from '@/features/portal/uploads/types';

import { type ApiMocks, fail, ok, pageOf } from '../mockReply';

/* 증강 견본 — 스토리북(6010)과 시연판이 같이 쓴다 */

/** 결과물 자산 — 결과가 도착한 요청의 결과물 위치 */
export const RESULT_ULD_SN = 77;
export const RESULT_FRAME_SN = 7701;

export const cond = (time: string, season: string, weather: string, terrain: string, severity: string) => ({
  time,
  season,
  weather,
  terrain,
  severity,
});

/** 최근 요청이 위다 — 같은 영상에 조건을 달리해 세 번 낸 모습 (포털 견본 `AUTHORING_AUGMENTS` 와 같은 값) */
export const ROWS: PortalAugmentSummary[] = [
  {
    augSn: 13,
    uldSn: 1,
    augSttsCd: 'REQUESTED',
    orgnlFileNm: '실종자추적_v0.7_20260910.mp4',
    requestedAt: '2026-09-11T11:32:00',
    resultReady: false,
    resultArrivedAt: null,
    generationCondition: cond('NIGHT', 'WINTER', 'SNOW', 'URBAN', 'HIGH'),
    failRsnCn: null,
  },
  {
    augSn: 12,
    uldSn: 1,
    augSttsCd: 'COMPLETED',
    orgnlFileNm: '실종자추적_v0.7_20260910.mp4',
    requestedAt: '2026-09-11T11:02:00',
    resultReady: true,
    resultArrivedAt: '2026-09-11T11:09:00',
    generationCondition: cond('DUSK', 'AUTUMN', 'RAIN', 'ROAD', 'MEDIUM'),
    failRsnCn: null,
  },
  {
    augSn: 11,
    uldSn: 1,
    augSttsCd: 'FAILED',
    orgnlFileNm: '실종자추적_v0.7_20260910.mp4',
    requestedAt: '2026-09-11T10:55:00',
    resultReady: false,
    resultArrivedAt: null,
    generationCondition: cond('DAY', 'SUMMER', 'FOG', 'RIVER', 'LOW'),
    failRsnCn: '외부 위탁 거절',
  },
];

/** 여러 쪽 — 한 쪽(20건)을 세 줄 견본으로 채운다. 전체 47건 · 3쪽 */
export const MANY_ROWS: PortalAugmentSummary[] = Array.from({ length: 20 }, (_, i) => ({
  ...ROWS[i % ROWS.length],
  augSn: 100 - i,
}));

export const detailOf = (row: PortalAugmentSummary): PortalAugmentDetail => ({
  ...row,
  resultUldSn: row.resultReady ? RESULT_ULD_SN : null,
});

/** 결과물 자산 — 미리보기가 첫 장면 한 장을 여기서 고른다 */
export const RESULT_ASSET: PortalUploadDetail = {
  uldSn: RESULT_ULD_SN,
  uldTypeCd: 'VIDEO',
  orgnlFileNm: 'augment-12-실종자추적_v0.7_20260910.mp4',
  fileSz: 48_213_504,
  mimeTypeNm: 'video/mp4',
  uldSttsCd: 'READY',
  frmeCnt: 1,
  vdoLenSec: 229,
  fps: 30,
  regDt: '2026-09-11T11:09:00',
  mdfcnDt: null,
  frames: [{ uldFrmeSn: RESULT_FRAME_SN, uldSn: RESULT_ULD_SN, frmeNo: 1, regDt: '2026-09-11T11:09:00' }],
  expiresAt: '2026-10-11T11:09:00',
};

/** 목록 — 세 줄 */
export const 목록응답: ApiMocks = (mock) => {
  mock.onGet('/portal/augments').reply(200, ok(pageOf(ROWS))[1]);
};

/** 결과 확인 창이 부르는 것 — 요청 단건 · 결과물 자산 · 첫 장면 그림 */
export const 결과응답: ApiMocks = (mock) => {
  mock.onGet(/^\/portal\/augments\/\d+$/).reply((config) => {
    const augSn = Number(config.url?.split('/').pop());
    const row = ROWS.find((r) => r.augSn === augSn);
    return row ? ok(detailOf(row)) : fail(403, null, 'FORBIDDEN');
  });
  mock.onGet(`/portal/uploads/${RESULT_ULD_SN}`).reply(200, ok(RESULT_ASSET)[1]);
  mock.onGet(`/portal/uploads/frames/${RESULT_FRAME_SN}/image`).reply(async () => {
    const blob = await fetch('/samples/dummy-5.jpg').then((r) => r.blob());
    return [200, blob];
  });
};

/**
 * 시연판용 — 목록을 기억해 두어, 내 업로드에서 낸 증강 요청이 이 목록 맨 위에 붙는다.
 * 결과 확인 창 응답은 위 `결과응답` 과 같되 기억해 둔 목록에서 찾는다.
 */
export function 증강시연(mock: MockAdapter) {
  const rows = [...ROWS];
  let nextSn = Math.max(...rows.map((r) => r.augSn)) + 1;

  mock.onGet('/portal/augments').reply(() => ok(pageOf(rows)));
  mock.onGet(/^\/portal\/augments\/\d+$/).reply((config) => {
    const augSn = Number(config.url?.split('/').pop());
    const row = rows.find((r) => r.augSn === augSn);
    return row ? ok(detailOf(row)) : fail(403, null, 'FORBIDDEN');
  });
  mock.onGet(`/portal/uploads/${RESULT_ULD_SN}`).reply(200, ok(RESULT_ASSET)[1]);
  mock.onGet(`/portal/uploads/frames/${RESULT_FRAME_SN}/image`).reply(async () => {
    const blob = await fetch('/samples/dummy-5.jpg').then((r) => r.blob());
    return [200, blob];
  });

  return {
    /** 새 요청 한 건을 맨 위에 붙이고 그 번호를 돌려준다 */
    add(uldSn: number, orgnlFileNm: string | null, generationCondition: Record<string, unknown>, requestedAt: string) {
      const augSn = nextSn++;
      rows.unshift({
        augSn,
        uldSn,
        augSttsCd: 'REQUESTED',
        orgnlFileNm,
        requestedAt,
        resultReady: false,
        resultArrivedAt: null,
        generationCondition,
        failRsnCn: null,
      });
      return augSn;
    },
  };
}
