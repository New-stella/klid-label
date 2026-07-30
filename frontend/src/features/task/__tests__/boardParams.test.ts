import { describe, expect, it } from 'vitest';

import {
  DEFAULT_TASK_FILTERS,
  buildBoardEventTypeParams,
  buildBoardParams,
  buildBoardSummaryParams,
  filtersToSearchParams,
  searchParamsToFilters,
  searchParamsToSort,
} from '../boardParams';
import { DEFAULT_BOARD_SORT, toBoardSortParams } from '../boardSort';

describe('boardParams — 서버 파라미터 조립 (H-1/H-2/H-4)', () => {
  it('진입시_배치상태_COMPLETED_와_등록일_최신순_파라미터로_요청한다', () => {
    // given: 기본 필터
    // when
    const params = buildBoardParams(DEFAULT_TASK_FILTERS, {
      page: 0,
      size: 20,
      sort: DEFAULT_BOARD_SORT,
    });

    // then
    expect(params.status).toBe('COMPLETED');
    expect(params.sort).toEqual(['regDt,desc']);
    expect(params.page).toBe(0);
    expect(params.size).toBe(20);
  });

  it('빈_문자열_필터값은_요청_파라미터에서_생략된다', () => {
    // given: 사용자가 필터를 모두 비운 상태
    // when
    const params = buildBoardParams(DEFAULT_TASK_FILTERS, {
      page: 0,
      size: 20,
      sort: DEFAULT_BOARD_SORT,
    });

    // then: status= / q= / eventTypeCd= 를 보내면 400 (BE @Pattern/@Size)
    expect('q' in params).toBe(false);
    expect('workStatus' in params).toBe(false);
    expect('eventTypeCd' in params).toBe(false);
    expect('workerId' in params).toBe(false);
    expect(params.status).toBe('COMPLETED');
  });

  it('미배정_카드_클릭이_status_UNASSIGNED_로_나가지_않는다', () => {
    // given: 워크플로 축에만 UNASSIGNED 를 설정
    const filters = { ...DEFAULT_TASK_FILTERS, workStatus: 'UNASSIGNED' };

    // when
    const params = buildBoardParams(filters, {
      page: 0,
      size: 20,
      sort: DEFAULT_BOARD_SORT,
    });

    // then: 배치 축은 그대로 COMPLETED 이어야 KPI 카드 숫자와 목록 총건수가 일치한다.
    expect(params.workStatus).toBe('UNASSIGNED');
    expect(params.status).toBe('COMPLETED');
  });

  it('BE_미정의_워크플로_코드는_전송되지_않는다', () => {
    // given: BE mapBoardStatus 는 IN_PROGRESS 를 반환하지 않으며 파라미터로도 400 이다.
    const filters = { ...DEFAULT_TASK_FILTERS, workStatus: 'IN_PROGRESS' };

    // when
    const params = buildBoardParams(filters, {
      page: 0,
      size: 20,
      sort: DEFAULT_BOARD_SORT,
    });

    // then
    expect('workStatus' in params).toBe(false);
  });

  it('검색어는_100자로_잘려_전송된다', () => {
    // given: BE @Size(max=100)
    const filters = { ...DEFAULT_TASK_FILTERS, q: 'ㄱ'.repeat(150) };

    // when
    const params = buildBoardParams(filters, {
      page: 0,
      size: 20,
      sort: DEFAULT_BOARD_SORT,
    });

    // then
    expect(params.q?.length).toBe(100);
  });

  it('작업자ID_는_양수만_전송된다', () => {
    const invalid = buildBoardParams(
      { ...DEFAULT_TASK_FILTERS, assigneeId: '-3' },
      { page: 0, size: 20, sort: DEFAULT_BOARD_SORT },
    );
    expect('workerId' in invalid).toBe(false);

    const valid = buildBoardParams(
      { ...DEFAULT_TASK_FILTERS, assigneeId: '42' },
      { page: 0, size: 20, sort: DEFAULT_BOARD_SORT },
    );
    expect(valid.workerId).toBe(42);
  });

  it('KPI_집계_요청에는_workStatus_를_보내지_않는다', () => {
    // given: 카드가 선택된 상태
    const filters = {
      ...DEFAULT_TASK_FILTERS,
      workStatus: 'REJECTED',
      q: '강남',
      eventTypeCd: 'EV01',
      assigneeId: '7',
    };

    // when
    const params = buildBoardSummaryParams(filters);

    // then: 카드 자체가 workStatus 선택지이므로 좁히면 1개 카드만 값을 갖는다.
    expect('workStatus' in params).toBe(false);
    expect(params.status).toBe('COMPLETED');
    expect(params.q).toBe('강남');
    expect(params.eventTypeCd).toBe('EV01');
    expect(params.workerId).toBe(7);
  });

  it('이벤트유형_옵션_요청에는_status_만_보낸다', () => {
    // given
    const filters = {
      ...DEFAULT_TASK_FILTERS,
      workStatus: 'REJECTED',
      q: '강남',
      eventTypeCd: 'EV01',
      assigneeId: '7',
    };

    // when: 배치 축은 고정이라 필터를 받지 않는다.
    void filters;
    const params = buildBoardEventTypeParams();

    // then: 필터를 건 뒤 옵션이 사라지면 되돌아갈 수 없다.
    expect(params).toEqual({ status: 'COMPLETED' });
  });

  it('배치상태축은_필터와_무관하게_항상_COMPLETED_로_전송된다', () => {
    // given: 사용자가 어떤 워크플로 값을 골라도 배치 축은 화면 부제("처리 완료된 영상만 표시")와 같아야 한다.
    const filters = { ...DEFAULT_TASK_FILTERS, workStatus: 'UNASSIGNED' };

    // when
    const list = buildBoardParams(filters, {
      page: 0,
      size: 20,
      sort: DEFAULT_BOARD_SORT,
    });
    const summary = buildBoardSummaryParams(filters);

    // then
    expect(list.status).toBe('COMPLETED');
    expect(summary.status).toBe('COMPLETED');
  });
});

/** `Record<string, string | string[]>` → URLSearchParams (배열 키는 repeat). */
function toSearchParams(record: Record<string, string | string[]>) {
  const sp = new URLSearchParams();
  Object.entries(record).forEach(([key, value]) => {
    if (Array.isArray(value)) value.forEach((v) => sp.append(key, v));
    else sp.set(key, value);
  });
  return sp;
}

describe('boardParams — URL 동기화 (H-9)', () => {
  it('URL_파라미터_왕복이_필터를_보존한다', () => {
    // given
    const filters = {
      q: '강남대로',
      workStatus: 'REVIEW_PENDING',
      assigneeId: '7',
      eventTypeCd: 'EV01000102',
    };

    // when: filters → searchParams → filters
    const sp = toSearchParams(filtersToSearchParams(filters));
    const restored = searchParamsToFilters(sp);

    // then
    expect(restored).toEqual(filters);
    // 왕복이 멱등이어야 한다.
    const twice = searchParamsToFilters(
      toSearchParams(filtersToSearchParams(restored)),
    );
    expect(twice).toEqual(filters);
  });

  it('WORKER_전용_IN_PROGRESS_도_URL_왕복에서_보존된다', () => {
    // given: WORKER 상태 select 에는 IN_PROGRESS 가 있다(클라이언트 필터).
    const filters = { ...DEFAULT_TASK_FILTERS, workStatus: 'IN_PROGRESS' };

    // when
    const restored = searchParamsToFilters(
      toSearchParams(filtersToSearchParams(filters)),
    );

    // then: URL 에서 버려지면 조회 후 새로고침에 필터가 사라진다.
    expect(restored.workStatus).toBe('IN_PROGRESS');
    // 서버로는 여전히 나가지 않는다(BE 400).
    expect(
      'workStatus' in
        buildBoardParams(restored, {
          page: 0,
          size: 20,
          sort: DEFAULT_BOARD_SORT,
        }),
    ).toBe(false);
  });

  it('구_URL_status_값은_워크플로_축으로_해석된다', () => {
    // given: 변경 전 화면의 `status` 는 워크플로 축이었다. 배치 축과 겹치는 4값이 특히 위험하다
    // (배치 축으로 해석되면 파이프라인 미완료 영상까지 목록에 올라온다).
    ['UNASSIGNED', 'PENDING', 'COMPLETED', 'REJECTED'].forEach((value) => {
      // when
      const restored = searchParamsToFilters(new URLSearchParams(`status=${value}`));
      const params = buildBoardParams(restored, {
        page: 0,
        size: 20,
        sort: DEFAULT_BOARD_SORT,
      });

      // then: 워크플로 축으로 나가고 배치 축은 COMPLETED 고정.
      expect(restored.workStatus).toBe(value);
      expect(params.workStatus).toBe(value);
      expect(params.status).toBe('COMPLETED');
    });
  });

  it('URL_의_허용되지_않은_상태값은_무시된다', () => {
    // given
    const sp = new URLSearchParams('status=NOPE');

    // when
    const restored = searchParamsToFilters(sp);

    // then: 미정의 값 제거 (400 방지)
    expect(restored.workStatus).toBe('');
  });

  it('URL_sort_파라미터가_왕복에서_보존된다', () => {
    // given: 사용자가 정렬한 화면을 북마크/새로고침한 경우
    const sp = new URLSearchParams('sort=rawSn,asc&sort=shtDt,desc');

    // when
    const sort = searchParamsToSort(sp);
    const restored = searchParamsToSort(
      toSearchParams(filtersToSearchParams(DEFAULT_TASK_FILTERS, sort)),
    );

    // then: 1순위 키 순서까지 그대로 유지된다.
    expect(toBoardSortParams(restored)).toEqual(['rawSn,asc', 'shtDt,desc']);
  });

  it('sort_가_없으면_기본_정렬로_복원된다', () => {
    expect(toBoardSortParams(searchParamsToSort(new URLSearchParams()))).toEqual([
      'regDt,desc',
    ]);
  });

  it('빈_필터와_기본정렬은_URL_에_기록되지_않는다', () => {
    const sp = filtersToSearchParams(DEFAULT_TASK_FILTERS, DEFAULT_BOARD_SORT);
    expect(sp['q']).toBeUndefined();
    expect(sp['status']).toBeUndefined();
    expect(sp['eventTypeCd']).toBeUndefined();
    expect(sp['assigneeId']).toBeUndefined();
    expect(sp['sort']).toBeUndefined();
  });
});
