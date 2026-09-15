import { describe, expect, it } from 'vitest';

import {
  claimLabel,
  claimViewOf,
  failedItems,
  failureReasonText,
  isSelectable,
  numericUserId,
  unselectableReason,
} from '@/features/review/reviewClaim';
import type { BatchApproveResultItem, Review } from '@/features/review/types';

/**
 * 검수 점유·일괄 승인 선택의 **판정 단일 지점** 가드.
 *
 * 화면 통합 시험이 덮지 못하는 축을 여기서 고정한다 — 특히 신원을 읽지 못하는 경우(관제 토큰의
 * 문자열 `sub`)와, 같은 사유 코드에 서로 다른 사유가 들어오는 경우다.
 */

/** 점유·자격 축만 쓰는 최소 행 — 나머지 필드는 이 판정에 관여하지 않는다. */
function reviewRow(
  overrides: Partial<
    Pick<Review, 'reviewingUserId' | 'reviewingUserName' | 'bulkApprovable'>
  > = {},
): Pick<Review, 'reviewingUserId' | 'reviewingUserName' | 'bulkApprovable'> {
  return {
    reviewingUserId: null,
    reviewingUserName: null,
    bulkApprovable: false,
    ...overrides,
  };
}

describe('numericUserId — 사번을 숫자로 읽는다', () => {
  it('숫자_문자열은_숫자로_읽는다', () => {
    expect(numericUserId('9001')).toBe(9001);
  });

  it('숫자가_아닌_사번은_undefined_다_NaN_을_돌려주지_않는다', () => {
    // ★관제가 발급한 토큰의 sub 는 로그인 아이디 문자열이다. NaN 을 그대로 돌려주면
    //   이후 비교가 **항상 거짓**이 되어 내가 잡은 영상이 조용히 남의 것처럼 보인다.
    expect(numericUserId('admin')).toBeUndefined();
    expect(Number.isNaN(numericUserId('admin') as number)).toBe(false);
  });

  it('빈_값은_undefined_다', () => {
    expect(numericUserId('')).toBeUndefined();
    expect(numericUserId('   ')).toBeUndefined();
    expect(numericUserId(undefined)).toBeUndefined();
    expect(numericUserId(null)).toBeUndefined();
  });
});

describe('claimViewOf — 점유 표시 세 갈래', () => {
  it('아무도_잡지_않았거나_유예로_풀렸으면_none_이다', () => {
    // 만료는 서버가 판정해 세 값을 null 로 내려보낸다 — 화면이 시각을 다시 재지 않는다.
    expect(claimViewOf(reviewRow(), 9001)).toEqual({ kind: 'none' });
    expect(claimLabel(claimViewOf(reviewRow(), 9001))).toBeNull();
  });

  it('사번이_같으면_내가_검수_중이다', () => {
    const view = claimViewOf(
      reviewRow({ reviewingUserId: 9001, reviewingUserName: '홍길동' }),
      9001,
    );
    expect(view).toEqual({ kind: 'mine' });
    expect(claimLabel(view)).toBe('내가 검수 중');
  });

  it('사번이_다르면_그_사람_이름으로_보인다', () => {
    const view = claimViewOf(
      reviewRow({ reviewingUserId: 7, reviewingUserName: '김검수' }),
      9001,
    );
    expect(view).toEqual({ kind: 'other', name: '김검수' });
    expect(claimLabel(view)).toBe('김검수 검수 중');
  });

  it('신원을_못_읽어도_서버가_내_것이라고_하면_내가_검수_중이다', () => {
    // 관제 토큰처럼 사번을 숫자로 못 읽는 경우다. 서버의 자격 판정이 신원 비교보다 믿을 만하다.
    const view = claimViewOf(
      reviewRow({ reviewingUserId: 9001, reviewingUserName: '홍길동', bulkApprovable: true }),
      undefined,
    );
    expect(view).toEqual({ kind: 'mine' });
  });

  it('신원도_못_읽고_서버_자격도_없으면_내_것이라고_단정하지_않는다', () => {
    // fail-safe 방향: 모르면 이름만 보인다. 이름은 어느 쪽이든 맞는 정보다.
    const view = claimViewOf(
      reviewRow({ reviewingUserId: 9001, reviewingUserName: '홍길동' }),
      undefined,
    );
    expect(view).toEqual({ kind: 'other', name: '홍길동' });
  });

  it('점유자_이름을_못_읽어도_점유_자체를_숨기지_않는다', () => {
    const view = claimViewOf(reviewRow({ reviewingUserId: 7, reviewingUserName: null }), 9001);
    expect(view).toEqual({ kind: 'other', name: '다른 검수자' });
  });
});

describe('isSelectable — 고를 수 있는가는 서버가 정한다', () => {
  it('서버가_참이라고_한_행만_고를_수_있다', () => {
    expect(isSelectable({ bulkApprovable: true })).toBe(true);
  });

  it('거짓_미지정은_고를_수_없다', () => {
    // ★이 한 줄이 안전장치다 — 한 번도 열어 보지 않은 영상이 무더기로 담기지 않게 한다.
    expect(isSelectable({ bulkApprovable: false })).toBe(false);
    expect(isSelectable({ bulkApprovable: undefined })).toBe(false);
  });

  it('점유가_있어도_서버_자격이_없으면_고를_수_없다', () => {
    // 화면이 점유·상태로 자격을 다시 계산하지 않는다는 것을 고정한다.
    expect(
      isSelectable(reviewRow({ reviewingUserId: 9001, reviewingUserName: '홍길동' })),
    ).toBe(false);
  });
});

describe('unselectableReason — 왜 고를 수 없는지 말한다', () => {
  it('고를_수_있는_행은_사유가_없다', () => {
    expect(unselectableReason(reviewRow({ bulkApprovable: true }), 9001)).toBeNull();
  });

  it('남이_잡고_있으면_그_사람_이름을_말한다', () => {
    expect(
      unselectableReason(reviewRow({ reviewingUserId: 7, reviewingUserName: '김검수' }), 9001),
    ).toBe('김검수 검수 중');
  });

  it('아무도_잡지_않았으면_검수를_시작하라고_말한다', () => {
    expect(unselectableReason(reviewRow(), 9001)).toBe('검수 시작 후 선택할 수 있습니다');
  });
});

describe('failureReasonText — 서버가 보낸 사유를 그대로 싣는다', () => {
  function failure(overrides: Partial<BatchApproveResultItem>): BatchApproveResultItem {
    return { videoId: 1, success: false, errorCode: 'CONFLICT', reason: null, ...overrides };
  }

  it('같은_사유_코드라도_서버_문장이_다르면_다르게_보인다', () => {
    // ★핵심 가드 — 사유 코드로 우리 문장을 지어내면 이 케이스가 죽는다.
    //   CONFLICT 하나에 「검수를 시작하지 않았다」·「다른 검수자가 검수 중이다」·「다른 사람이
    //   먼저 처리했다」·상태 전이 불가가 모두 들어온다. 코드로 문장을 고르면 결론은 맞고
    //   사유는 거짓인 안내가 되며, 결론만 보는 단언은 그것을 잡지 못한다.
    const notStarted = failure({
      reason: '검수를 시작하지 않은 영상입니다. 먼저 검수를 시작한 뒤 선택해 주세요.',
    });
    const otherHolder = failure({ reason: '다른 검수자가 검수 중인 영상입니다.' });
    const alreadyDone = failure({ reason: '다른 검수자가 먼저 처리했습니다.' });

    expect(failureReasonText(notStarted)).toBe(
      '검수를 시작하지 않은 영상입니다. 먼저 검수를 시작한 뒤 선택해 주세요.',
    );
    expect(failureReasonText(otherHolder)).toBe('다른 검수자가 검수 중인 영상입니다.');
    expect(failureReasonText(alreadyDone)).toBe('다른 검수자가 먼저 처리했습니다.');

    // 세 문장이 서로 달라야 한다 — 하나로 뭉뚱그리면 사용자가 다음에 무엇을 할지 알 수 없다.
    expect(
      new Set([
        failureReasonText(notStarted),
        failureReasonText(otherHolder),
        failureReasonText(alreadyDone),
      ]).size,
    ).toBe(3);
  });

  it('다른_사유_코드의_서버_문장도_그대로_싣는다', () => {
    expect(
      failureReasonText(
        failure({ errorCode: 'REVIEW_NO_LABEL', reason: '라벨이 없는 영상입니다.' }),
      ),
    ).toBe('라벨이 없는 영상입니다.');
  });

  it('문구가_비어_오면_그때만_코드로_최소한의_말을_만든다', () => {
    expect(failureReasonText(failure({ reason: null }))).toContain('CONFLICT');
    expect(failureReasonText(failure({ reason: '   ' }))).toContain('CONFLICT');
  });

  it('코드도_문구도_없으면_일반_문구로_떨어진다', () => {
    expect(failureReasonText(failure({ errorCode: null, reason: null }))).toBe(
      '처리하지 못했습니다',
    );
  });
});

describe('failedItems — 실패한 건만 추린다', () => {
  it('성공은_빠지고_실패만_순서대로_남는다', () => {
    const results: BatchApproveResultItem[] = [
      { videoId: 1, success: true, errorCode: null, reason: null },
      { videoId: 2, success: false, errorCode: 'CONFLICT', reason: '다른 검수자가 검수 중입니다.' },
      { videoId: 3, success: true, errorCode: null, reason: null },
      { videoId: 4, success: false, errorCode: 'CONFLICT', reason: '비식별이 끝나지 않았습니다.' },
    ];
    expect(failedItems(results).map((r) => r.videoId)).toEqual([2, 4]);
  });
});
