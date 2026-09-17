import { afterEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen, within } from '@testing-library/react';

import { apiClient } from '@/lib/api/client';
import { renderWithProviders } from '@/test/renderWithProviders';

import {
  HistoryDrawer,
  describeEvent,
  dotClass,
  foldConsecutiveStartReviews,
} from '../components/HistoryDrawer';
import type { AssignmentHistory, TaskEventType } from '../types';

function makeRow(over: Partial<AssignmentHistory>): AssignmentHistory {
  return {
    eventSeq: 1,
    eventTypeCd: 'SUBMIT',
    actorUserNo: 7,
    actorUserName: '홍길동',
    subjectUserNo: 7,
    subjectUserName: '홍길동',
    prevUserNo: null,
    prevUserName: null,
    reason: null,
    occurredAt: '2026-07-23T00:00:00Z',
    ...over,
  };
}

/**
 * ★알려진 이벤트 종류 **전량**.
 *
 * `Record<TaskEventType, true>` 라서 값역에 종류가 하나라도 늘면 **컴파일이 깨진다** — 표가
 * 조용히 낡지 않게 하는 장치다. 실제로 이 표가 없던 동안 값역이 세 종류(프레임 폐기·복원·시작
 * 버전 적용) 넓어졌는데 문구도 점 색상도 따라오지 않아, 화면에 코드값이 그대로 나왔다.
 */
const ALL_EVENT_TYPES = Object.keys({
  ASSIGN: true,
  REASSIGN: true,
  START_REVIEW: true,
  SUBMIT: true,
  CANCEL_SUBMIT: true,
  APPROVE: true,
  REJECT: true,
  PRIVACY_META_UPDATE: true,
  PRIVACY_META_RESET: true,
  FRAME_DISCARD: true,
  FRAME_RESTORE: true,
  START_VERSION_APPLY: true,
} satisfies Record<TaskEventType, true>) as TaskEventType[];

/** 갈래가 없는 종류가 떨어지는 자리 — 분류가 아니라 **폴백**이다. */
const FALLBACK_DOT = 'bg-gray-400';

describe('HistoryDrawer_상태색_토큰', () => {
  // ⚠ 구 테스트는 `dotClass('ASSIGN') === 'bg-primary-600'` 을 정답으로 박제하고 있었다.
  //   UI-084 는 ASSIGN·SUBMIT 을 **같은 정보군**으로 규정하는데 구현은 ASSIGN 만 다른 색이었다
  //   → 같은 의미군은 같은 토큰(info)이 되도록 기대값을 정정한다.
  it('각_이벤트_dot_은_KRDS_의미상태색_토큰으로_매핑된다', () => {
    // given/when/then: 액션의 상태 의미 → KRDS 토큰
    expect(dotClass('APPROVE')).toBe('bg-success');
    expect(dotClass('REJECT')).toBe('bg-danger');
    expect(dotClass('REASSIGN')).toBe('bg-warning');
    expect(dotClass('ASSIGN')).toBe('bg-info');
    expect(dotClass('SUBMIT')).toBe('bg-info');
  });

  it('CANCEL_SUBMIT_은_주의(warning)_토큰으로_매핑된다', () => {
    expect(dotClass('CANCEL_SUBMIT')).toBe('bg-warning');
  });

  it('START_REVIEW_는_진행_이벤트라_정보군_토큰이다', () => {
    // 승인·반려 같은 결말이 아니라 워크플로 진행이다 — 배정·제출과 같은 군.
    expect(dotClass('START_REVIEW')).toBe('bg-info');
    expect(dotClass('START_REVIEW')).toBe(dotClass('SUBMIT'));
  });

  it('같은_의미군은_같은_토큰을_쓴다', () => {
    // 정보군: 배정·제출 / 주의군: 재배정·검수취소
    expect(dotClass('ASSIGN')).toBe(dotClass('SUBMIT'));
    expect(dotClass('REASSIGN')).toBe(dotClass('CANCEL_SUBMIT'));
  });

  it('개인정보_감사_2종은_진행_이벤트와_구분되는_중립_톤이다', () => {
    // 워크플로 진행이 아니라 기록이므로 의미 상태색(success/danger/warning/info)을 쓰지 않는다.
    const neutral = 'bg-neutral-500';
    expect(dotClass('PRIVACY_META_UPDATE')).toBe(neutral);
    expect(dotClass('PRIVACY_META_RESET')).toBe(neutral);
    // 정보군(진행 이벤트)과 같은 색이면 두 축이 한 군으로 보인다 — 구 구현의 결함
    expect(dotClass('PRIVACY_META_UPDATE')).not.toBe(dotClass('SUBMIT'));
  });

  it('프레임_폐기_복원과_시작_버전_적용은_기록군이라_중립_톤이다', () => {
    // `UI-084`: "배정·검수가 앞으로 나아간 일은 정보 톤, 무엇이 언제 바뀌었는지를 남긴 기록은
    // 중립 톤" — 이 셋은 뒤쪽이라 개인정보 감사 2종과 **같은** 톤이다.
    const neutral = dotClass('PRIVACY_META_UPDATE');
    expect(dotClass('FRAME_DISCARD')).toBe(neutral);
    expect(dotClass('FRAME_RESTORE')).toBe(neutral);
    expect(dotClass('START_VERSION_APPLY')).toBe(neutral);
    // 진행 이벤트와 같은 색이면 두 축이 한 군으로 보인다.
    expect(dotClass('FRAME_DISCARD')).not.toBe(dotClass('SUBMIT'));
    // ★폴백 회색으로 떨어뜨리면 "분류가 없는 값"처럼 보인다 — 중립 톤과 폴백은 다른 것이다.
    expect(dotClass('FRAME_DISCARD')).not.toBe(FALLBACK_DOT);
  });

  it('원시_팔레트_클래스(green/red/orange/emerald/blue-500)_는_사용되지_않는다', () => {
    for (const c of ALL_EVENT_TYPES) {
      const cls = dotClass(c);
      expect(cls, `종류 ${c}`).not.toMatch(/bg-(green|red|orange|emerald|blue)-\d/);
    }
  });
});

describe('HistoryDrawer_이벤트_설명', () => {
  it('검수이력_CANCEL_SUBMIT_검수취소로_표시', () => {
    // given: CANCEL_SUBMIT 이벤트 row (BE 가 EVENT_CANCEL_SUBMIT 원문으로 기록)
    const row = makeRow({ eventTypeCd: 'CANCEL_SUBMIT', actorUserName: '홍길동' });
    // when/then: enum 원문이 아닌 "검수 취소" 한글로 표기
    expect(describeEvent(row)).toBe('홍길동 — 검수 취소');
  });

  it('개인정보_선언_감사이벤트는_원문코드가_아니라_한글로_표기된다', () => {
    // given: BE 가 감사 축(LS_TASK_EVNT_LOG)에 남기는 개인정보 선언 이벤트 2종
    // when/then: default 폴백(원문 코드 노출)이 아니라 사람이 읽는 문구여야 한다
    expect(
      describeEvent(makeRow({ eventTypeCd: 'PRIVACY_META_UPDATE', actorUserName: '검수자' })),
    ).toBe('검수자 — 개인정보 선언 저장');
    expect(
      describeEvent(makeRow({ eventTypeCd: 'PRIVACY_META_RESET', actorUserName: '홍길동' })),
    ).toBe('홍길동 — 비식별 신고로 개인정보 선언 초기화');
  });

  it('검수_시작은_코드값이_아니라_한글_문구로_표기된다', () => {
    // ★이 이벤트는 이번 라운드부터 **실제로 쌓인다**(점유를 세우는 기록). 문구가 없으면
    //   default 폴백이 코드값(`START_REVIEW`)을 사람에게 그대로 노출한다.
    const text = describeEvent(
      makeRow({ eventTypeCd: 'START_REVIEW', actorUserName: '김검수', actorRoleCd: null }),
    );
    expect(text).toBe('김검수 — 검수 시작');
    expect(text).not.toContain('START_REVIEW');
  });

  it('행위자_역할은_행위_시점_값으로_함께_표기된다', () => {
    // ★조회 시점에 그 사람의 지금 역할을 다시 읽은 값이 아니다 — 관리자가 승인한 건은
    //   나중에 그 사람이 검수자로 바뀌어도 관리자로 남아야 한다.
    expect(
      describeEvent(
        makeRow({ eventTypeCd: 'APPROVE', actorUserName: '박관리', actorRoleCd: 'ADMIN' }),
      ),
    ).toBe('박관리(관리자) — 검수 승인 완료');
    expect(
      describeEvent(
        makeRow({ eventTypeCd: 'APPROVE', actorUserName: '이검수', actorRoleCd: 'REVIEWER' }),
      ),
    ).toBe('이검수(검수자) — 검수 승인 완료');
    // 작업자도 제출 이벤트를 남긴다 — 이 값역은 승인자 표시 축보다 넓다.
    expect(
      describeEvent(
        makeRow({ eventTypeCd: 'SUBMIT', actorUserName: '홍길동', actorRoleCd: 'WORKER' }),
      ),
    ).toBe('홍길동(작업자) — 검수 제출');
  });

  it('같은_행위자라도_역할이_다르면_다르게_표기된다', () => {
    // 역할을 상수로 굳히거나 통째로 지우는 변이를 죽인다 — 결론(문구 뒷부분)만 보면 둘이 같다.
    const asAdmin = describeEvent(
      makeRow({ eventTypeCd: 'APPROVE', actorUserName: '같은사람', actorRoleCd: 'ADMIN' }),
    );
    const asReviewer = describeEvent(
      makeRow({ eventTypeCd: 'APPROVE', actorUserName: '같은사람', actorRoleCd: 'REVIEWER' }),
    );
    expect(asAdmin).not.toBe(asReviewer);
  });

  it('역할이_비어_있는_옛_이력은_빈_괄호를_남기지_않는다', () => {
    // 이 축이 생기기 전에 쌓인 행은 역할을 복원할 수 없어 백필하지 않았다 — 지어내지 않는다.
    const text = describeEvent(
      makeRow({ eventTypeCd: 'APPROVE', actorUserName: '이검수', actorRoleCd: null }),
    );
    expect(text).toBe('이검수 — 검수 승인 완료');
    expect(text).not.toContain('()');
  });

  it('모르는_역할_코드는_코드값_그대로_보인다', () => {
    // 빈칸으로 두면 값이 있는데 없는 것처럼 읽힌다.
    expect(
      describeEvent(
        makeRow({ eventTypeCd: 'APPROVE', actorUserName: '누군가', actorRoleCd: 'NEW_ROLE' }),
      ),
    ).toBe('누군가(NEW_ROLE) — 검수 승인 완료');
  });

  it('프레임_폐기_복원과_시작_버전_적용도_한글_문구로_표기된다', () => {
    // ★이론이 아니라 실제로 도달한다 — 서버가 이 셋을 실제로 발행하고, 이력 조회와 조립 경로
    //   어디에도 종류 필터가 없어 그대로 실려 온다. 문구가 없던 동안 화면에는
    //   "{행위자} — FRAME_DISCARD" 처럼 코드값이 그대로 보였다. 문구는 `UI-084` 원문 그대로다.
    expect(
      describeEvent(makeRow({ eventTypeCd: 'FRAME_DISCARD', actorUserName: '김작업' })),
    ).toBe('김작업 — 프레임 폐기');
    expect(
      describeEvent(makeRow({ eventTypeCd: 'FRAME_RESTORE', actorUserName: '김작업' })),
    ).toBe('김작업 — 프레임 복원');
    expect(
      describeEvent(makeRow({ eventTypeCd: 'START_VERSION_APPLY', actorUserName: '이검수' })),
    ).toBe('이검수 — 시작 버전 적용');
  });

  /**
   * ★값역이 넓어질 때마다 같은 일이 반복된다 — 이번이 바로 그 사례다.
   *
   * 한 종류씩 케이스를 더하는 방식은 **새로 생긴 종류를 구조적으로 못 본다.** 그래서 전 종류를
   * 표로 돌며 「코드값이 그대로 보이지 않는다」를 단언하고, 표 자체는 `Record<TaskEventType, true>`
   * 로 값역과 묶어 둔다(종류가 늘면 컴파일이 먼저 깨진다).
   */
  it('★알려진_전_종류가_코드값을_그대로_내보이지_않는다_폴백만_내보인다', () => {
    for (const code of ALL_EVENT_TYPES) {
      const text = describeEvent(makeRow({ eventTypeCd: code, actorUserName: '홍길동' }));
      expect(text, `종류 ${code} 의 문구에 코드값이 그대로 보인다`).not.toContain(code);
      expect(text, `종류 ${code} 의 문구가 비어 있다`).not.toBe('홍길동 — ');
      expect(dotClass(code), `종류 ${code} 가 폴백 색으로 떨어졌다`).not.toBe(FALLBACK_DOT);
    }

    // ★폴백은 **남아 있고**, 코드값을 그대로 내보인다 — 위 표가 왜 필요한지의 근거다.
    //   (폴백을 지우면 값역이 넓어지는 동안 화면이 터진다. 지우는 것이 답이 아니다.)
    const unknown = 'BRAND_NEW_EVENT' as TaskEventType;
    expect(describeEvent(makeRow({ eventTypeCd: unknown, actorUserName: '홍길동' }))).toBe(
      '홍길동 — BRAND_NEW_EVENT',
    );
    expect(dotClass(unknown)).toBe(FALLBACK_DOT);
  });

  it('검수이력_기존이벤트(제출/승인/반려)_회귀', () => {
    expect(describeEvent(makeRow({ eventTypeCd: 'SUBMIT', actorUserName: '홍길동' }))).toBe(
      '홍길동 — 검수 제출',
    );
    expect(describeEvent(makeRow({ eventTypeCd: 'APPROVE', actorUserName: '검수자' }))).toBe(
      '검수자 — 검수 승인 완료',
    );
    expect(describeEvent(makeRow({ eventTypeCd: 'REJECT', actorUserName: '검수자' }))).toBe(
      '검수자 — 검수 반려',
    );
  });
});

/**
 * 같은 사람의 **연속 재진입 접기**(`UI-084`).
 *
 * 서버는 재진입마다 검수 시작을 새로 쌓는다 — 그것이 점유 시각을 뒤로 미루는 유일한 수단이다.
 * 그래서 **화면이 접지 않으면** 타임라인이 그 사람의 재진입으로 덮인다. 사양이 예견한 상태다.
 */
describe('HistoryDrawer_연속_검수시작_접기', () => {
  /** 검수 시작 한 건 — 접기 판정에 실제로 쓰이는 축(행위자·종류·시각)만 바꾼다. */
  function start(
    eventSeq: number,
    occurredAt: string,
    actor: { no: number | null; name: string | null },
  ): AssignmentHistory {
    return makeRow({
      eventSeq,
      eventTypeCd: 'START_REVIEW',
      occurredAt,
      actorUserNo: actor.no,
      actorUserName: actor.name,
    });
  }

  const KIM = { no: 7, name: '김검수' };
  const LEE = { no: 9, name: '이검수' };

  it('★같은_사람의_연속_검수시작_3건이_한_줄로_접히고_최초와_마지막_시각을_모두_보존한다', () => {
    const folded = foldConsecutiveStartReviews([
      start(1, '2026-09-15T01:00:00Z', KIM),
      start(2, '2026-09-15T02:00:00Z', KIM),
      start(3, '2026-09-15T03:00:00Z', KIM),
    ]);

    expect(folded).toHaveLength(1);
    expect(folded[0].count).toBe(3);
    // ★최초 시각을 마지막 값으로 덮어쓰면 언제 처음 열었는지가 이력에서 사라진다.
    expect(folded[0].firstOccurredAt).toBe('2026-09-15T01:00:00Z');
    expect(folded[0].lastOccurredAt).toBe('2026-09-15T03:00:00Z');
    // 두 시각이 **서로 달라야** 한다 — 한쪽을 다른 쪽으로 덮는 변이를 죽인다.
    expect(folded[0].firstOccurredAt).not.toBe(folded[0].lastOccurredAt);
  });

  it('★사이에_다른_사람의_이벤트가_끼면_접지_않는다', () => {
    // 떨어져 있는 두 구간을 합치면 그 사이에 무슨 일이 있었는지가 지워진다.
    const folded = foldConsecutiveStartReviews([
      start(1, '2026-09-15T01:00:00Z', KIM),
      start(2, '2026-09-15T02:00:00Z', LEE),
      start(3, '2026-09-15T03:00:00Z', KIM),
    ]);

    expect(folded).toHaveLength(3);
    expect(folded.map((e) => e.count)).toEqual([1, 1, 1]);
    expect(folded.map((e) => e.row.eventSeq)).toEqual([1, 2, 3]);
  });

  it('★사이에_다른_종류의_이벤트가_끼면_접지_않는다', () => {
    const folded = foldConsecutiveStartReviews([
      start(1, '2026-09-15T01:00:00Z', KIM),
      makeRow({ eventSeq: 2, eventTypeCd: 'APPROVE', actorUserNo: KIM.no, actorUserName: KIM.name }),
      start(3, '2026-09-15T03:00:00Z', KIM),
    ]);

    expect(folded).toHaveLength(3);
  });

  it('★접기_대상은_검수_시작뿐이다_연속_승인은_접지_않는다', () => {
    // 재검수 뒤 같은 사람이 다시 승인하면 연속 두 건이 된다. 그 둘은 **서로 다른 실제 행위**라
    // 접으면 재승인이 이력에서 사라진다 — 접기를 종류 전반으로 넓히면 정확히 그 일이 일어난다.
    const folded = foldConsecutiveStartReviews([
      makeRow({ eventSeq: 1, eventTypeCd: 'APPROVE', actorUserNo: KIM.no, actorUserName: KIM.name }),
      makeRow({ eventSeq: 2, eventTypeCd: 'APPROVE', actorUserNo: KIM.no, actorUserName: KIM.name }),
    ]);

    expect(folded).toHaveLength(2);
  });

  it('접을_것이_없으면_원본_순서와_건수를_그대로_돌려준다', () => {
    const rows = [
      makeRow({ eventSeq: 1, eventTypeCd: 'ASSIGN' }),
      makeRow({ eventSeq: 2, eventTypeCd: 'SUBMIT' }),
      makeRow({ eventSeq: 3, eventTypeCd: 'APPROVE' }),
    ];
    const folded = foldConsecutiveStartReviews(rows);

    expect(folded.map((e) => e.row.eventSeq)).toEqual([1, 2, 3]);
    expect(folded.every((e) => e.count === 1)).toBe(true);
    // 접히지 않은 줄은 두 시각이 자기 발생시각과 같다(접기 전용 표기로 새지 않는다).
    expect(folded[0].firstOccurredAt).toBe(folded[0].lastOccurredAt);
  });

  it('★목록이_최신순으로_와도_최초_시각이_마지막_재진입_값으로_덮이지_않는다', () => {
    // 최초·마지막을 **위치**가 아니라 값으로 고르므로 정렬 방향을 전제하지 않는다.
    const folded = foldConsecutiveStartReviews([
      start(3, '2026-09-15T03:00:00Z', KIM),
      start(2, '2026-09-15T02:00:00Z', KIM),
      start(1, '2026-09-15T01:00:00Z', KIM),
    ]);

    expect(folded).toHaveLength(1);
    expect(folded[0].firstOccurredAt).toBe('2026-09-15T01:00:00Z');
    expect(folded[0].lastOccurredAt).toBe('2026-09-15T03:00:00Z');
  });
});

/**
 * 접기의 **화면 축** — 순수 함수가 옳아도 그 결과를 그리는 자리가 비어 있으면 절반만 고친 것이다.
 * (「쓰는 자리를 가드해도 읽는 자리는 통째로 비어 있을 수 있다」)
 */
describe('HistoryDrawer_접힌_줄_렌더', () => {
  let mock: MockAdapter;

  afterEach(() => {
    mock?.restore();
  });

  function renderDrawer(rows: AssignmentHistory[]) {
    mock = new MockAdapter(apiClient);
    mock.onGet('/assignments/5/history').reply(200, {
      success: true,
      data: rows,
      message: null,
      errorCode: null,
    });
    return renderWithProviders(
      <HistoryDrawer open onClose={() => {}} assignmentId={5} videoName="CCTV-A" />,
    );
  }

  it('★연속_재진입은_한_줄로_그려지고_그_줄에_두_시각이_모두_보인다', async () => {
    // 같은 **날** 여러 번 다시 들어오는 것이 이 줄이 생기는 조건이라, 날짜만 보이면 두 시각이
    // 같은 글자가 되어 「언제 처음 열었는지」가 사라진다 — 분 단위까지 보여야 한다.
    renderDrawer([
      makeRow({
        eventSeq: 11,
        eventTypeCd: 'START_REVIEW',
        actorUserNo: 7,
        actorUserName: '김검수',
        occurredAt: '2026-09-15T01:05:00',
      }),
      makeRow({
        eventSeq: 12,
        eventTypeCd: 'START_REVIEW',
        actorUserNo: 7,
        actorUserName: '김검수',
        occurredAt: '2026-09-15T02:30:00',
      }),
      makeRow({
        eventSeq: 13,
        eventTypeCd: 'START_REVIEW',
        actorUserNo: 7,
        actorUserName: '김검수',
        occurredAt: '2026-09-15T04:45:00',
      }),
    ]);

    const list = await screen.findByRole('list');
    // 세 건이 한 줄로 접힌다 — 접지 않으면 같은 문구가 세 줄 이어진다.
    expect(within(list).getAllByRole('listitem')).toHaveLength(1);
    expect(within(list).getAllByText('김검수 — 검수 시작')).toHaveLength(1);

    const when = screen.getByTestId('history-when-11');
    expect(when).toHaveTextContent('2026-09-15 01:05');
    expect(when).toHaveTextContent('2026-09-15 04:45');
    // 가운데 재진입은 대표로 쓰지 않는다(최초·마지막만 보인다).
    expect(when).not.toHaveTextContent('02:30');
  });

  it('★사이에_다른_사람이_끼면_화면에도_따로_그려진다', async () => {
    renderDrawer([
      makeRow({
        eventSeq: 21,
        eventTypeCd: 'START_REVIEW',
        actorUserNo: 7,
        actorUserName: '김검수',
        occurredAt: '2026-09-15T01:00:00',
      }),
      makeRow({
        eventSeq: 22,
        eventTypeCd: 'START_REVIEW',
        actorUserNo: 9,
        actorUserName: '이검수',
        occurredAt: '2026-09-15T02:00:00',
      }),
      makeRow({
        eventSeq: 23,
        eventTypeCd: 'START_REVIEW',
        actorUserNo: 7,
        actorUserName: '김검수',
        occurredAt: '2026-09-15T03:00:00',
      }),
    ]);

    const list = await screen.findByRole('list');
    expect(within(list).getAllByRole('listitem')).toHaveLength(3);
    expect(within(list).getAllByText('김검수 — 검수 시작')).toHaveLength(2);
    // 접히지 않은 줄은 종전대로 날짜만 보인다 — 접기 전용 표기가 보통 줄로 새지 않는다.
    expect(screen.getByTestId('history-when-21')).toHaveTextContent('2026-09-15');
    expect(screen.getByTestId('history-when-21').textContent).not.toContain(':');
  });
});
