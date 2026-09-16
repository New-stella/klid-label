/**
 * ★「제외됨 N건」을 눌러 제외분 보기로 전환할 때 **무엇을 빼는지는 화면마다 다르다** — 그 차이를
 * 값으로 못박는다. [@design AC-1124] [@design ADR-069]
 *
 * <h3>왜 이 가드가 필요한가</h3>
 * 세 화면의 전환 규칙이 다른 것은 **집계가 세는 방식이 다르기 때문**이다:
 *  - 영상 처리 현황: 집계 창구가 없어 **페이지 응답**이 같은 조건으로 센다 → 어떤 필터도 빼지 않는다.
 *  - 작업 목록: 집계 창구가 **작업 진행 상태 축을 빼고** 센다 → 전환 요청에서도 그 축을 뺀다.
 *  - 검수 목록: 집계 창구가 **검수 상태 축을 빼고** 센다 → 전환 요청에서도 그 축을 뺀다.
 *
 * 규칙이 달라 보이므로 다음 사람이 **「일관성」을 이유로 셋을 같게 만들** 유인이 크다. 그렇게 하는
 * 순간 어느 한 화면에서 **누른 숫자와 전환 결과가 어긋난다**(상태로 좁힌 채 누르면 결과가 더 적다).
 * 그 어긋남은 화면을 열어 숫자를 비교해야만 보이고 타입 오류도 런타임 오류도 내지 않는다.
 *
 * ⚠ 이 파일이 **못 보는 것**: 화면이 그 조립 함수를 실제로 부르는지는 판정하지 않는다(순수 함수
 * 계약만 본다). 그 배선은 각 화면의 렌더 시험이 맡는다.
 */
import { describe, expect, it } from 'vitest';

import {
  DEFAULT_REVIEW_FILTERS,
  DEFAULT_REVIEW_SORT,
  buildReviewListParams,
} from '@/features/review/reviewListParams';
import { DEFAULT_BOARD_SORT } from '@/features/task/boardSort';
import {
  DEFAULT_TASK_FILTERS,
  buildBoardParams,
  buildBoardSummaryParams,
} from '@/features/task/boardParams';
import {
  parseVideoListParams,
  videoListParamsToSearchParams,
} from '@/features/video/parseVideoListParams';

const BOARD_PAGE = { page: 0, size: 20, sort: DEFAULT_BOARD_SORT };
const REVIEW_PAGE = { page: 0, size: 20 };

/** 모든 축이 채워진 작업 목록 필터 — 무엇이 남고 무엇이 빠지는지 한 번에 본다. */
function boardFilters(excludedOnly: boolean) {
  return {
    ...DEFAULT_TASK_FILTERS,
    q: '강남',
    workStatus: 'REJECTED',
    assigneeId: '7',
    eventTypeCd: '010001',
    excludedOnly,
  };
}

describe('제외분 보기 전환 — 화면마다 빼는 축이 다르다', () => {
  describe('영상 처리 현황 — 어떤 필터도 빼지 않는다', () => {
    it('전환해도_걸린_검색_필터가_전부_그대로_남는다', () => {
      const params = parseVideoListParams(
        new URLSearchParams(
          'cctvNameKeyword=강남&dataSttsCd=COMPLETED&eventTypeCd=010001&from=2026-01-01&to=2026-01-31&excludedOnly=true',
        ),
      );

      expect(params.excludedOnly).toBe(true);
      // ★상태 축까지 그대로다 — 이 화면은 집계 창구가 없어 페이지 응답이 **같은 조건**으로 세므로,
      //   조건을 빼면 누른 숫자와 전환 결과가 도리어 어긋난다.
      expect(params.dataSttsCd).toBe('COMPLETED');
      expect(params.cctvNameKeyword).toBe('강남');
      expect(params.eventTypeCd).toBe('010001');
      expect(params.from).toBe('2026-01-01');
      expect(params.to).toBe('2026-01-31');
    });

    it('URL_왕복에서도_조건이_보존된다', () => {
      const search = videoListParamsToSearchParams({
        cctvNameKeyword: '강남',
        dataSttsCd: 'COMPLETED',
        excludedOnly: true,
      });
      expect(search.get('excludedOnly')).toBe('true');
      expect(search.get('dataSttsCd')).toBe('COMPLETED');
      expect(search.get('cctvNameKeyword')).toBe('강남');
    });
  });

  describe('작업 목록 — 작업 진행 상태 축만 뺀다', () => {
    it('전환하면_상태_축이_빠지고_나머지_필터는_남는다', () => {
      const params = buildBoardParams(boardFilters(true), BOARD_PAGE);

      expect(params.workStatus).toBeUndefined();
      // 나머지는 유지된다 — 상태 말고 다른 축까지 빼면 전환 결과가 숫자보다 **넓어진다**.
      expect(params.q).toBe('강남');
      expect(params.eventTypeCd).toBe('010001');
      expect(params.workerId).toBe(7);
      expect(params.excludedOnly).toBe(true);
    });

    it('기본_목록에서는_상태_축이_그대로_실린다', () => {
      // 전환하지 않은 평소에는 상태 필터가 살아 있어야 한다 — 축을 늘 빼면 상태 필터가 죽는다.
      const params = buildBoardParams(boardFilters(false), BOARD_PAGE);
      expect(params.workStatus).toBe('REJECTED');
    });

    it('집계_요청에도_가시_범위가_실린다', () => {
      // 카드 숫자와 목록이 같은 범위를 보게 한다(거르는 값이 아니라 가시 범위라 집계에도 간다).
      expect(buildBoardSummaryParams(boardFilters(true)).excludedOnly).toBe(true);
    });
  });

  describe('검수 목록 — 검수 상태 축만 뺀다', () => {
    it('전환하면_검수_상태가_빠지고_검색어는_남는다', () => {
      const params = buildReviewListParams(
        { q: '강남', status: 'REJECTED', excludedOnly: true },
        DEFAULT_REVIEW_SORT,
        REVIEW_PAGE,
      );

      expect(params.status).toBeUndefined();
      expect(params.q).toBe('강남');
      expect(params.excludedOnly).toBe(true);
    });

    it('기본_목록에서는_검수_상태가_그대로_실린다', () => {
      const params = buildReviewListParams(
        { q: '강남', status: 'REJECTED', excludedOnly: false },
        DEFAULT_REVIEW_SORT,
        REVIEW_PAGE,
      );
      expect(params.status).toBe('REJECTED');
    });
  });

  /**
   * ★★**이 케이스가 이 파일의 본체다.**
   *
   * 위 케이스들은 각 화면을 따로 본다 — 셋을 **같게 만드는** 회귀(한 화면의 규칙을 다른 화면에
   * 복사하는 형태)는 그것만으로 전부 잡히지 않는다. 그래서 세 축의 **관계**를 따로 못박는다.
   */
  it('★세_화면의_전환_규칙이_실제로_서로_다르다', () => {
    const board = buildBoardParams(boardFilters(true), BOARD_PAGE);
    const review = buildReviewListParams(
      { q: '강남', status: 'REJECTED', excludedOnly: true },
      DEFAULT_REVIEW_SORT,
      REVIEW_PAGE,
    );
    const video = parseVideoListParams(
      new URLSearchParams('dataSttsCd=COMPLETED&excludedOnly=true'),
    );

    // 두 목록은 자기 상태 축을 **버린다**.
    expect(board.workStatus).toBeUndefined();
    expect(review.status).toBeUndefined();
    // 영상 처리 현황은 상태 축을 **간직한다** — 여기가 undefined 가 되면 세 화면이 같아진 것이고,
    // 그 순간 이 화면에서 누른 숫자와 전환 결과가 어긋나기 시작한다.
    expect(video.dataSttsCd).toBe('COMPLETED');
  });

  /**
   * 하위호환 — 이 축을 **모르던 기존 호출**과 요청 형태가 갈리지 않아야 한다.
   *
   * `compactParams` 는 `false` 를 **보존**하므로(page=0 을 지키려고), 명시적으로 접지 않으면
   * `excludedOnly=false` 가 그대로 나가 기존 북마크·저장된 URL 과 형태가 달라진다.
   */
  it('꺼진_상태에서는_세_화면_모두_키_자체를_싣지_않는다', () => {
    expect(buildBoardParams(boardFilters(false), BOARD_PAGE)).not.toHaveProperty('excludedOnly');
    expect(buildBoardSummaryParams(boardFilters(false))).not.toHaveProperty('excludedOnly');
    expect(
      buildReviewListParams(DEFAULT_REVIEW_FILTERS, DEFAULT_REVIEW_SORT, REVIEW_PAGE),
    ).not.toHaveProperty('excludedOnly');
    expect(videoListParamsToSearchParams({ dataSttsCd: 'COMPLETED' }).has('excludedOnly')).toBe(
      false,
    );
  });

  it('URL_의_참이_아닌_값은_켜진_것으로_읽지_않는다', () => {
    // 수기 URL·구 북마크 방어 — 아무 문자열이나 truthy 로 받으면 `?excludedOnly=false` 에서
    // 기본 목록으로 되돌아갈 수 없다(「기본 목록으로」를 눌러도 같은 값이 다시 실린다).
    for (const raw of ['false', '0', '', 'TRUE', 'yes']) {
      expect(
        parseVideoListParams(new URLSearchParams(`excludedOnly=${raw}`)).excludedOnly,
        `excludedOnly=${raw}`,
      ).toBeUndefined();
    }
  });
});
