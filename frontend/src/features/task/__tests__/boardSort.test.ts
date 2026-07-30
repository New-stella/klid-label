import { describe, expect, it } from 'vitest';

import {
  BOARD_SORT_KEY_BY_COLUMN,
  DEFAULT_BOARD_SORT,
  MAX_BOARD_SORT_KEYS,
  applyBoardSort,
  parseBoardSort,
  sortDirectionOf,
  toBoardSortParams,
  toggleBoardSort,
} from '../boardSort';

describe('boardSort — BE 정렬 allowlist 정합 (H-5)', () => {
  it('컬럼키_videoId_는_서버_정렬키_rawSn_으로_매핑된다', () => {
    // given/when: FE 컬럼 key 는 videoId, BE allowlist 는 rawSn
    // then
    expect(BOARD_SORT_KEY_BY_COLUMN.videoId).toBe('rawSn');
    expect(BOARD_SORT_KEY_BY_COLUMN.capturedAt).toBe('shtDt');
    expect(toBoardSortParams([{ column: 'videoId', direction: 'asc' }])).toEqual([
      'rawSn,asc',
    ]);
  });

  it('정렬_키는_최대_3개까지만_서버로_전달된다', () => {
    // given: 4개 컬럼을 차례로 클릭 (videoId/rawSn 은 같은 서버 키)
    let entries: ReturnType<typeof applyBoardSort> = [...DEFAULT_BOARD_SORT];
    entries = applyBoardSort(entries, 'capturedAt', 'asc');
    entries = applyBoardSort(entries, 'rawSn', 'asc');
    entries = applyBoardSort(entries, 'videoId', 'desc');

    // when
    const params = toBoardSortParams(entries);

    // then: 상한 3 (BE 는 4개부터 400)
    expect(entries.length).toBeLessThanOrEqual(MAX_BOARD_SORT_KEYS);
    expect(params.length).toBeLessThanOrEqual(MAX_BOARD_SORT_KEYS);
    expect(params).toContain('rawSn,desc');
  });

  it('상한을_넘긴_입력은_직렬화_단계에서_잘린다', () => {
    // given: 호출부 실수로 4개 이상이 들어와도 400 을 만들지 않는다(최종 방어).
    const params = toBoardSortParams([
      { column: 'regDt', direction: 'desc' },
      { column: 'shtDt', direction: 'asc' },
      { column: 'rawSn', direction: 'asc' },
      { column: 'capturedAt', direction: 'desc' },
    ]);

    // then: shtDt 중복 제거 + 상한 클램프
    expect(params.length).toBeLessThanOrEqual(MAX_BOARD_SORT_KEYS);
    expect(params).toEqual(['regDt,desc', 'shtDt,asc', 'rawSn,asc']);
  });

  it('같은_서버키는_중복되지_않고_방향만_갱신된다', () => {
    // given: videoId 와 rawSn 은 같은 서버 정렬키(rawSn)
    const entries = applyBoardSort(
      [{ column: 'rawSn', direction: 'asc' }],
      'videoId',
      'desc',
    );

    // when
    const params = toBoardSortParams(entries);

    // then: rawSn 이 두 번 나가면 BE 정렬 항목 수가 부풀어 400 위험
    expect(params).toEqual(['rawSn,desc']);
  });

  it('기본_정렬은_등록일_최신순이다', () => {
    expect(toBoardSortParams(DEFAULT_BOARD_SORT)).toEqual(['regDt,desc']);
  });

  it('allowlist_밖_정렬키는_파싱에서_제거된다', () => {
    // given: URL 로 임의 키가 들어와도 BE 400 을 유발하지 않아야 한다.
    const parsed = parseBoardSort(['cctvName,asc', 'regDt,desc', 'DROP TABLE']);

    // then
    expect(toBoardSortParams(parsed)).toEqual(['regDt,desc']);
  });

  it('파싱_결과도_3개로_잘린다', () => {
    const parsed = parseBoardSort([
      'regDt,desc',
      'shtDt,asc',
      'rawSn,desc',
      'capturedAt,asc',
    ]);
    expect(parsed.length).toBeLessThanOrEqual(MAX_BOARD_SORT_KEYS);
  });

  it('방금_클릭한_컬럼이_1순위가_된다', () => {
    // given: 기본 정렬(regDt) 이 앞에 있으면 뒤에 붙은 키는 정렬에 영향을 주지 못한다(무효 클릭).
    const entries = applyBoardSort(DEFAULT_BOARD_SORT, 'capturedAt', 'desc');

    // then
    expect(toBoardSortParams(entries)).toEqual(['shtDt,desc', 'regDt,desc']);
  });

  it('파싱은_입력_순서를_보존한다', () => {
    // given: URL 왕복에서 1순위 키가 뒤바뀌면 결과 집합의 순서가 달라진다.
    const parsed = parseBoardSort(['rawSn,asc', 'shtDt,desc']);

    // then
    expect(toBoardSortParams(parsed)).toEqual(['rawSn,asc', 'shtDt,desc']);
  });

  it('정렬_방향_조회는_서버키_기준이다', () => {
    // given: URL 왕복 뒤 videoId 는 rawSn 으로 돌아온다.
    const entries = parseBoardSort(['rawSn,asc']);

    // then: 컬럼 이름으로만 비교하면 새로고침 후 헤더의 aria-sort 표시가 사라진다.
    expect(sortDirectionOf(entries, 'videoId')).toBe('asc');
    expect(sortDirectionOf(entries, 'capturedAt')).toBeUndefined();
  });

  it('같은_컬럼_재클릭은_방향을_토글한다', () => {
    // given
    const first = toggleBoardSort(DEFAULT_BOARD_SORT, 'capturedAt');
    // when
    const second = toggleBoardSort(first, 'capturedAt');

    // then: 첫 클릭 내림차순 → 재클릭 오름차순
    expect(sortDirectionOf(first, 'capturedAt')).toBe('desc');
    expect(sortDirectionOf(second, 'capturedAt')).toBe('asc');
    expect(toBoardSortParams(second)).toEqual(['shtDt,asc', 'regDt,desc']);
  });
});
