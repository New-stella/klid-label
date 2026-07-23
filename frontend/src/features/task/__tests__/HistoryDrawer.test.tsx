import { describe, expect, it } from 'vitest';

import { describeEvent, dotClass } from '../components/HistoryDrawer';
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

describe('HistoryDrawer_상태색_토큰', () => {
  it('각_이벤트_dot_은_KRDS_의미상태색_토큰으로_매핑된다', () => {
    // given/when/then: 액션의 상태 의미 → KRDS 토큰
    expect(dotClass('APPROVE')).toBe('bg-success');
    expect(dotClass('REJECT')).toBe('bg-danger');
    expect(dotClass('REASSIGN')).toBe('bg-warning');
    expect(dotClass('ASSIGN')).toBe('bg-primary-600');
    expect(dotClass('SUBMIT')).toBe('bg-info');
  });

  it('CANCEL_SUBMIT_은_주의(warning)_토큰으로_매핑된다', () => {
    expect(dotClass('CANCEL_SUBMIT')).toBe('bg-warning');
  });

  it('원시_팔레트_클래스(green/red/orange/emerald/blue-500)_는_사용되지_않는다', () => {
    const codes: TaskEventType[] = [
      'ASSIGN',
      'REASSIGN',
      'SUBMIT',
      'APPROVE',
      'REJECT',
      'CANCEL_SUBMIT',
    ];
    for (const c of codes) {
      const cls = dotClass(c);
      expect(cls).not.toMatch(/bg-(green|red|orange|emerald|blue)-\d/);
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
