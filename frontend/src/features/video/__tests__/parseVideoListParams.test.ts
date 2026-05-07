import { describe, expect, it } from 'vitest';

import {
  parseVideoListParams,
  videoListParamsToSearchParams,
} from '../parseVideoListParams';

describe('parseVideoListParams', () => {
  it('빈_searchParams는_기본값_page_0_size_20', () => {
    const out = parseVideoListParams(new URLSearchParams());
    expect(out.page).toBe(0);
    expect(out.size).toBe(20);
  });

  it('정상_파라미터_파싱', () => {
    const sp = new URLSearchParams(
      'page=2&size=50&cctvNameKeyword=강남&eventTypeCd=FIRE&from=2026-05-01&to=2026-05-07',
    );
    const out = parseVideoListParams(sp);
    expect(out.page).toBe(2);
    expect(out.size).toBe(50);
    expect(out.cctvNameKeyword).toBe('강남');
    expect(out.eventTypeCd).toBe('FIRE');
    expect(out.from).toBe('2026-05-01');
    expect(out.to).toBe('2026-05-07');
  });

  it('잘못된_size는_기본값_적용', () => {
    const out = parseVideoListParams(new URLSearchParams('size=abc'));
    expect(out.size).toBe(20);
  });

  it('size_상한_100_초과는_제외', () => {
    const out = parseVideoListParams(new URLSearchParams('size=999'));
    expect(out.size).toBe(20);
  });

  it('잘못된_날짜_형식은_제외', () => {
    const out = parseVideoListParams(new URLSearchParams('from=invalid'));
    expect(out.from).toBeUndefined();
  });

  it('cctvNameKeyword_100자_초과는_절단', () => {
    const long = 'a'.repeat(200);
    const out = parseVideoListParams(new URLSearchParams(`cctvNameKeyword=${long}`));
    expect(out.cctvNameKeyword?.length).toBe(100);
  });

  it('videoListParamsToSearchParams_역변환', () => {
    const sp = videoListParamsToSearchParams({
      page: 1,
      size: 50,
      cctvNameKeyword: '강남',
      eventTypeCd: 'FIRE',
    });
    expect(sp.get('page')).toBe('1');
    expect(sp.get('size')).toBe('50');
    expect(sp.get('cctvNameKeyword')).toBe('강남');
    expect(sp.get('eventTypeCd')).toBe('FIRE');
  });

  it('기본값_page_0_size_20은_URL에_미포함', () => {
    const sp = videoListParamsToSearchParams({ page: 0, size: 20, cctvNameKeyword: '강남' });
    expect(sp.has('page')).toBe(false);
    expect(sp.has('size')).toBe(false);
    expect(sp.get('cctvNameKeyword')).toBe('강남');
  });
});
