import type MockAdapter from 'axios-mock-adapter';

import type { PortalUserWork } from '@/features/portal/api';

import { ok, pageOf } from '../mockReply';

/* 내 작업 견본 — 스토리북(6010)과 시연판이 같이 쓴다 */

/** 세 줄 — 데이터마트 영상(저장함) · 내 업로드(아직 저장 전) · 이어서 작업할 수 없는 업로드 */
export const WORKS: PortalUserWork[] = [
  {
    rawSn: 1821,
    assetSource: 'DATAMART',
    videoName: '교차로_야간_침수_CCTV-107_20260902.mp4',
    labelCount: 3,
    lastSavedAt: '2026-09-12T16:40:00',
    entrySrcSn: 5001,
    expiresOn: '2026-09-19',
  },
  {
    rawSn: 4,
    assetSource: 'PORTAL_UPLOAD',
    videoName: '실종자추적_v0.7_20260910.mp4',
    labelCount: 0,
    lastSavedAt: null,
    entrySrcSn: 41,
    expiresOn: '2026-09-18',
  },
  {
    rawSn: 3,
    assetSource: 'PORTAL_UPLOAD',
    videoName: '침수도로_야간_v0.3_20260908.mp4',
    labelCount: 0,
    lastSavedAt: null,
    entrySrcSn: null,
    expiresOn: null,
  },
];

/** 목록 응답 */
export function 내작업목록(mock: MockAdapter, works: PortalUserWork[] = WORKS, total = works.length) {
  mock.onGet('/portal/user-works').reply(200, ok(pageOf(works, total))[1]);
}
