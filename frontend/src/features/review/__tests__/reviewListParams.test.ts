import { describe, expect, it } from 'vitest';

import {
  DEFAULT_REVIEW_FILTERS,
  DEFAULT_REVIEW_SORT,
  MAX_SEARCH_KEYWORD_LENGTH,
  REVIEW_SORT_COLUMNS,
  REVIEW_STATUS_ALL,
  asUiReviewStatus,
  buildReviewListParams,
  buildReviewSummaryParams,
  parseReviewSort,
  searchParamsToFilters,
  searchParamsToPage,
  searchParamsToSort,
  toReviewSearchParams,
  toReviewSortParam,
} from '../reviewListParams';

describe('reviewListParams — 검수목록 필터·정렬 매핑', () => {
  it('진입_기본값은_검수요청_과_제출일_오름차순이다', () => {
    expect(DEFAULT_REVIEW_FILTERS).toEqual({ q: '', status: 'REVIEW_PENDING' });
    expect(toReviewSortParam(DEFAULT_REVIEW_SORT)).toBe('submittedAt,asc');
  });

  it('쿼리가_없으면_진입_기본값으로_해석된다', () => {
    const sp = new URLSearchParams();
    expect(searchParamsToFilters(sp)).toEqual(DEFAULT_REVIEW_FILTERS);
    expect(searchParamsToSort(sp)).toEqual(DEFAULT_REVIEW_SORT);
    expect(searchParamsToPage(sp)).toBe(0);
  });

  it('전체_선택은_ALL_로_URL_에_기록되고_다시_읽으면_필터_미적용이다', () => {
    const url = toReviewSearchParams(
      { q: '', status: '' },
      DEFAULT_REVIEW_SORT,
      { page: 0, size: 20 },
    );
    expect(url.status).toBe(REVIEW_STATUS_ALL);
    expect(searchParamsToFilters(new URLSearchParams(url)).status).toBe('');
  });

  it('허용되지_않은_상태값은_진입_기본값으로_떨어진다', () => {
    expect(asUiReviewStatus('DROP TABLE')).toBe('REVIEW_PENDING');
    expect(asUiReviewStatus(null)).toBe('REVIEW_PENDING');
    // BE 코드(PENDING)를 URL 에 직접 넣어도 FE 축이 아니므로 기본값으로 정규화된다.
    expect(asUiReviewStatus('PENDING')).toBe('REVIEW_PENDING');
  });

  it('sortable_컬럼은_BE_allowlist_대응_컬럼만이다', () => {
    // BE SortAllowlist.REVIEW = submittedAt|updDt|videoId|status.
    expect([...REVIEW_SORT_COLUMNS]).toEqual(['submittedAt', 'videoId', 'status']);
  });

  it('allowlist_밖_정렬키는_기본_정렬로_정규화된다', () => {
    // BE 가 400 이 아니라 조용히 폴백하므로 FE 가 미리 정규화하지 않으면
    // "URL 은 바뀌었는데 순서는 그대로" 인 무효 클릭이 된다.
    expect(parseReviewSort('eventName,desc')).toEqual(DEFAULT_REVIEW_SORT);
    expect(parseReviewSort('')).toEqual(DEFAULT_REVIEW_SORT);
    expect(parseReviewSort('submittedAt,desc')).toEqual({
      column: 'submittedAt',
      direction: 'desc',
    });
  });

  it('빈_문자열_필터값은_요청_파라미터에서_생략된다', () => {
    const params = buildReviewListParams({ q: '   ', status: '' }, DEFAULT_REVIEW_SORT, {
      page: 0,
      size: 20,
    });
    expect(params).not.toHaveProperty('q');
    expect(params).not.toHaveProperty('status');
    expect(params.page).toBe(0);
    expect(params.sort).toBe('submittedAt,asc');
  });

  it('검색어는_BE_상한_100자로_잘려_전송된다', () => {
    const params = buildReviewListParams(
      { q: 'a'.repeat(200), status: '' },
      DEFAULT_REVIEW_SORT,
      { page: 0, size: 20 },
    );
    expect(params.q).toHaveLength(MAX_SEARCH_KEYWORD_LENGTH);
  });

  it('summary_파라미터에는_q_만_실린다', () => {
    const params = buildReviewSummaryParams({ q: '강남', status: 'REJECTED' });
    expect(params).toEqual({ q: '강남' });
    expect(params).not.toHaveProperty('status');
  });
});
