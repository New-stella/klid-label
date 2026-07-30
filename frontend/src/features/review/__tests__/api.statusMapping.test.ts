import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';

import { apiClient } from '@/lib/api/client';

import { REVIEW_STATUS_TO_BE, getReviewSummary, listReviews } from '../api';
import type { ReviewStatus } from '../types';

/**
 * H-1 — FE→BE 상태 코드 역매핑.
 *
 * BE 는 화이트리스트 밖 status 를 **400 이 아니라 빈 결과 200** 으로 돌려준다. 그래서 역매핑이
 * 빠지면 "검수요청이 하나도 없습니다" 로 위장된다 — HTTP status 만 보는 단언으로는 절대 못 잡는다.
 * 반드시 **실제 axios params 에 실린 값**을 본다.
 */
describe('review api — 상태 코드 역매핑 (H-1)', () => {
  let mock: MockAdapter;

  const emptyPage = {
    success: true,
    data: { content: [], totalElements: 0, totalPages: 0, number: 0, size: 20 },
    message: null,
    errorCode: null,
  };

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    mock.onGet('/reviews').reply(200, emptyPage);
    mock.onGet('/reviews/summary').reply(200, {
      success: true,
      data: { total: 0, pending: 0, inReview: 0, approved: 0, rejected: 0 },
      message: null,
      errorCode: null,
    });
  });

  afterEach(() => {
    mock.restore();
  });

  function lastParams() {
    const calls = mock.history.get.filter((r) => r.url === '/reviews');
    return calls[calls.length - 1]?.params as Record<string, unknown> | undefined;
  }

  it.each([
    ['REVIEW_PENDING', 'PENDING'],
    ['REVIEWING', 'IN_REVIEW'],
    ['COMPLETED', 'APPROVED'],
    ['REJECTED', 'REJECTED'],
  ] as [ReviewStatus, string][])(
    'FE_상태코드가_BE_코드로_역매핑되어_전송된다 (%s → %s)',
    async (feStatus, beStatus) => {
      await listReviews({ page: 0, size: 20, status: feStatus });
      expect(lastParams()?.status).toBe(beStatus);
    },
  );

  it('매핑_상수는_FE_상태_4종을_모두_덮는다', () => {
    // Record<ReviewStatus, ReviewStatusParam> 이라 FE 상태가 늘면 컴파일 에러로 잡힌다.
    // 런타임에서도 키 누락을 한 번 더 확인한다.
    expect(Object.keys(REVIEW_STATUS_TO_BE).sort()).toEqual(
      ['COMPLETED', 'REJECTED', 'REVIEWING', 'REVIEW_PENDING'].sort(),
    );
  });

  it('상태_미지정이면_status_키_자체가_실리지_않는다', async () => {
    await listReviews({ page: 0, size: 20 });
    expect(lastParams()).not.toHaveProperty('status');
  });

  it('summary_는_q_만_전송한다', async () => {
    await getReviewSummary({ q: '강남' });
    const calls = mock.history.get.filter((r) => r.url === '/reviews/summary');
    const params = calls[calls.length - 1]?.params as Record<string, unknown>;
    expect(params).toEqual({ q: '강남' });
  });
});
