// 공지 첨부 다운로드 — 요청 제한시간 배선.
//
// 이 파일이 고정하는 계약:
//  - 첨부 상한은 **20MB**(BE NoticeAttachService.MAX_FILE_SIZE)인데 이 요청은 공용 기본값(30초)을
//    그대로 썼다. 이 저장소가 다른 내려받기 경로에서 쓰는 보수적 실효 대역 5Mbps 로 계산하면
//    20MB(=160Mb)는 **약 32초**라 기본값을 아슬아슬하게 넘긴다 — 상한에 가까운 첨부는
//    **정상인데도 실패로 보인다**.
//  - 값 단언은 **기대값과 같다**로 쓴다. 「기본값과 다르다」로 쓰면 값이 우연히 기본값과 겹치는
//    순간 조용히 참이 되어 아무것도 지키지 못한다.

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import MockAdapter from 'axios-mock-adapter';

import { apiClient } from '@/lib/api/client';

import { NOTICE_ATTACHMENT_DOWNLOAD_TIMEOUT_MS, downloadAttachment } from '../api';

/** 첨부 상한 20MB 를 저장소 기준 대역 5Mbps 로 받아내는 데 걸리는 시간(ms). 20×8Mb ÷ 5Mbps = 32s. */
const TRANSFER_AT_REPO_BANDWIDTH_MS = 32_000;

describe('공지 첨부 다운로드 — 요청 제한시간', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    Object.defineProperty(URL, 'createObjectURL', {
      writable: true,
      value: vi.fn(() => 'blob:stub'),
    });
    Object.defineProperty(URL, 'revokeObjectURL', { writable: true, value: vi.fn() });
  });

  afterEach(() => {
    mock.restore();
    vi.restoreAllMocks();
  });

  it('첨부_다운로드_요청에_공용_기본값이_아닌_전용_제한시간이_실린다', async () => {
    // given
    let seenTimeout: number | undefined;
    mock.onGet('/notices/7/attachments/11/download').reply((config) => {
      seenTimeout = config.timeout;
      return [200, 'bytes'];
    });

    // when
    await downloadAttachment(7, 11, 'spec.pdf');

    // then: 기본값(30초)이면 상한에 가까운 첨부가 구조적으로 끊긴다
    expect(seenTimeout).toBe(NOTICE_ATTACHMENT_DOWNLOAD_TIMEOUT_MS);
  });

  it('제한시간은_유한하고_20MB_전송에_쓸_만큼_길다', () => {
    expect(Number.isFinite(NOTICE_ATTACHMENT_DOWNLOAD_TIMEOUT_MS)).toBe(true);
    // 유한해야 한다 — 연결이 조용히 멈췄을 때 버튼이 영구히 잠기는 것을 끝내 주는 최후 장치다
    expect(NOTICE_ATTACHMENT_DOWNLOAD_TIMEOUT_MS).toBeGreaterThan(0);
    // 저장소 기준 대역(5Mbps)으로 20MB 를 받는 시간(32초)보다 짧으면 계산이 앞뒤가 맞지 않는다
    expect(NOTICE_ATTACHMENT_DOWNLOAD_TIMEOUT_MS).toBeGreaterThanOrEqual(
      TRANSFER_AT_REPO_BANDWIDTH_MS,
    );
  });
});
