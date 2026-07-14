import { describe, expect, it } from 'vitest';

import {
  FRAME_STATUS,
  FRAME_STATUS_BORDER,
  resolveFrameStatus,
} from '../frameStatus';

describe('resolveFrameStatus — 프레임 4색 우선순위', () => {
  it('프레임_4색_우선순위_현재_확인요청_반려_저장_순', () => {
    // 모든 상태가 동시에 참이어도 현재 > 확인요청 > 반려 > 저장 순으로 결정.
    expect(
      resolveFrameStatus({
        isCurrent: true,
        hasInquiry: true,
        hasRejection: true,
        hasLabel: true,
      }),
    ).toBe(FRAME_STATUS.CURRENT);

    expect(
      resolveFrameStatus({
        isCurrent: false,
        hasInquiry: true,
        hasRejection: true,
        hasLabel: true,
      }),
    ).toBe(FRAME_STATUS.INQUIRY);

    expect(
      resolveFrameStatus({
        isCurrent: false,
        hasInquiry: false,
        hasRejection: true,
        hasLabel: true,
      }),
    ).toBe(FRAME_STATUS.REJECTION);
  });

  it('이슈없으면_저장연두', () => {
    const status = resolveFrameStatus({
      isCurrent: false,
      hasInquiry: false,
      hasRejection: false,
      hasLabel: true,
    });
    expect(status).toBe(FRAME_STATUS.SAVED);
    // 저장 = 연두(green) 테두리.
    expect(FRAME_STATUS_BORDER[status]).toContain('green');
  });

  it('반려프레임_주황', () => {
    const status = resolveFrameStatus({
      isCurrent: false,
      hasInquiry: false,
      hasRejection: true,
      hasLabel: true,
    });
    expect(status).toBe(FRAME_STATUS.REJECTION);
    expect(FRAME_STATUS_BORDER[status]).toContain('orange');
  });

  it('확인요청_빨강_현재_강조', () => {
    expect(FRAME_STATUS_BORDER[FRAME_STATUS.INQUIRY]).toContain('red');
    expect(FRAME_STATUS_BORDER[FRAME_STATUS.CURRENT]).toContain('primary');
  });

  it('아무_상태도_없으면_NONE', () => {
    expect(
      resolveFrameStatus({
        isCurrent: false,
        hasInquiry: false,
        hasRejection: false,
        hasLabel: false,
      }),
    ).toBe(FRAME_STATUS.NONE);
  });
});
