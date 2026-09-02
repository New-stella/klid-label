// Phase 5 — 목록 폴링 간격 산출 순수 로직. PROCESSING/UPLOADED(후처리 진행 중) 자산이 하나라도
// 있으면 3초 폴링, 모두 종결(READY/FAILED)이면 폴링 중단(false).
import { describe, expect, it } from 'vitest';

import { pollIntervalFor, POLL_MS } from '../hooks/usePortalUploads';
import type { PortalUpload } from '../types';

function up(uldSttsCd: string): PortalUpload {
  return {
    uldSn: 1,
    // 신규 접수는 영상뿐이라 픽스처도 영상이다(이 시험의 축은 상태이지 자산 종류가 아니다).
    uldTypeCd: 'VIDEO',
    orgnlFileNm: 'a.mp4',
    fileSz: 100,
    mimeTypeNm: 'video/mp4',
    uldSttsCd,
    frmeCnt: 1,
    frmeSn: 10,
    regDt: '2026-07-17T00:00:00',
    expiresAt: null,
  };
}

describe('pollIntervalFor', () => {
  it('PROCESSING_존재시_폴링', () => {
    expect(pollIntervalFor([up('READY'), up('PROCESSING')])).toBe(POLL_MS);
  });

  it('UPLOADED_존재시_폴링', () => {
    expect(pollIntervalFor([up('UPLOADED')])).toBe(POLL_MS);
  });

  it('모두_종결이면_폴링_중단', () => {
    expect(pollIntervalFor([up('READY'), up('FAILED')])).toBe(false);
  });

  it('빈_목록이면_폴링_중단', () => {
    expect(pollIntervalFor([])).toBe(false);
  });
});
