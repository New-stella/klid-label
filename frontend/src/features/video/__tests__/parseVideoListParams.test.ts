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
      'page=2&size=50&cctvNameKeyword=강남&eventTypeCd=FALL&from=2026-05-01&to=2026-05-07',
    );
    const out = parseVideoListParams(sp);
    expect(out.page).toBe(2);
    expect(out.size).toBe(50);
    expect(out.cctvNameKeyword).toBe('강남');
    expect(out.eventTypeCd).toBe('FALL');
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
      eventTypeCd: 'FALL',
    });
    expect(sp.get('page')).toBe('1');
    expect(sp.get('size')).toBe('50');
    expect(sp.get('cctvNameKeyword')).toBe('강남');
    expect(sp.get('eventTypeCd')).toBe('FALL');
  });

  it('기본값_page_0_size_20은_URL에_미포함', () => {
    const sp = videoListParamsToSearchParams({ page: 0, size: 20, cctvNameKeyword: '강남' });
    expect(sp.has('page')).toBe(false);
    expect(sp.has('size')).toBe(false);
    expect(sp.get('cctvNameKeyword')).toBe('강남');
  });

  // [@design SCREEN-008] [@design ADR-050] 시계열 건너뜀 필터 — URL 왕복 계약.
  //   ★ URL 은 사람이 손으로 쓸 수 있는 입력이고 이 값은 그대로 조회 파라미터가 된다.
  //     화이트리스트 교집합만 통과시켜 미지의 문자열이 서버로 흘러가지 않게 한다.
  it('skippedStage_는_화이트리스트_값만_통과한다', () => {
    expect(parseVideoListParams(new URLSearchParams('skippedStage=VLM')).skippedStage).toBe('VLM');
    expect(
      parseVideoListParams(new URLSearchParams('skippedStage=AUTOLABEL')).skippedStage,
    ).toBe('AUTOLABEL');
  });

  it('skippedStage_미지의_값은_버린다', () => {
    for (const bogus of ["' OR 1=1", '../../etc', 'YOLO', '']) {
      const out = parseVideoListParams(
        new URLSearchParams(`skippedStage=${encodeURIComponent(bogus)}`),
      );
      expect(out.skippedStage).toBeUndefined();
    }
  });

  it('skippedStage_미지정이면_URL_에_키를_두지_않는다', () => {
    // 빈 문자열을 올리면 서버가 값으로 해석할 여지가 생기고 기존 북마크 동작이 달라진다.
    const sp = videoListParamsToSearchParams({ page: 0, size: 20 });
    expect(sp.has('skippedStage')).toBe(false);
  });

  it('skippedStage_는_URL_에_그대로_직렬화된다', () => {
    const sp = videoListParamsToSearchParams({ page: 0, size: 20, skippedStage: 'VLM' });
    expect(sp.get('skippedStage')).toBe('VLM');
  });
});
