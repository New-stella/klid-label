// P2b — 프레임 폐기·복원 가능 여부 판정(화면 동선).
//
// 서버가 400 으로 거부하는 조건을 화면이 <b>미리</b> 알아 버튼을 비활성화하고 사유를 안내한다.
// 누른 뒤 저장까지 갔다가 실패하는 동선을 없앤다.

import { describe, expect, it } from 'vitest';

import { resolveFrameDiscardUnsupportedReason } from '../utils/frameDiscardEligibility';

describe('resolveFrameDiscardUnsupportedReason', () => {
  it('한번이라도_승인된_영상은_사유를_돌려준다', () => {
    expect(resolveFrameDiscardUnsupportedReason({ everApproved: true })).toBe(
      '한번이라도 검수가 완료된 영상은 프레임을 새로 폐기하거나 복원할 수 없습니다',
    );
  });

  it('재제출로_상태가_내려간_구간에도_사유를_돌려준다 — 지금_상태가_아니라_이력으로_본다', () => {
    expect(
      resolveFrameDiscardUnsupportedReason({ everApproved: true, reviewSttsCd: 'PENDING' }),
    ).toBeDefined();
  });

  it('승인_이력_필드가_없는_구_응답은_현재_상태로_폴백한다 — fail_closed', () => {
    expect(resolveFrameDiscardUnsupportedReason({ reviewSttsCd: 'APPROVED' })).toBeDefined();
  });

  it('승인_이력이_없으면_사유가_없다 — 과잉_차단_방지', () => {
    expect(
      resolveFrameDiscardUnsupportedReason({ everApproved: false, reviewSttsCd: 'ASSIGNED' }),
    ).toBeUndefined();
  });

  it('영상_정보를_모르면_막지_않는다 — 로딩_구간마다_동선이_끊기지_않게', () => {
    expect(resolveFrameDiscardUnsupportedReason(undefined)).toBeUndefined();
    expect(resolveFrameDiscardUnsupportedReason(null)).toBeUndefined();
  });
});
