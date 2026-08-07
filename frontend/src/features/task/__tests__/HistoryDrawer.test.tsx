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

  it('원시_팔레트_클래스(green/red/orange/emerald/blue-500)_는_사용되지_않는다', () => {
    const codes: TaskEventType[] = [
      'ASSIGN',
      'REASSIGN',
      'SUBMIT',
      'APPROVE',
      'REJECT',
      'CANCEL_SUBMIT',
      'PRIVACY_META_UPDATE',
      'PRIVACY_META_RESET',
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

  it('개인정보_선언_감사이벤트는_원문코드가_아니라_한글로_표기된다', () => {
    // given: BE 가 감사 축(LS_TASK_EVENT_LOG)에 남기는 개인정보 선언 이벤트 2종
    // when/then: default 폴백(원문 코드 노출)이 아니라 사람이 읽는 문구여야 한다
    expect(
      describeEvent(makeRow({ eventTypeCd: 'PRIVACY_META_UPDATE', actorUserName: '검수자' })),
    ).toBe('검수자 — 개인정보 선언 저장');
    expect(
      describeEvent(makeRow({ eventTypeCd: 'PRIVACY_META_RESET', actorUserName: '홍길동' })),
    ).toBe('홍길동 — 비식별 신고로 개인정보 선언 초기화');
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
