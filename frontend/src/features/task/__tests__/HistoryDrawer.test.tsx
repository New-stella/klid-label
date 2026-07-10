import { describe, expect, it } from 'vitest';

import { dotClass } from '../components/HistoryDrawer';
import type { TaskEventType } from '../types';

describe('HistoryDrawer_상태색_토큰', () => {
  it('각_이벤트_dot_은_KRDS_의미상태색_토큰으로_매핑된다', () => {
    // given/when/then: 액션의 상태 의미 → KRDS 토큰
    expect(dotClass('APPROVE')).toBe('bg-success');
    expect(dotClass('REJECT')).toBe('bg-danger');
    expect(dotClass('REASSIGN')).toBe('bg-warning');
    expect(dotClass('ASSIGN')).toBe('bg-primary-600');
    expect(dotClass('SUBMIT')).toBe('bg-info');
  });

  it('원시_팔레트_클래스(green/red/orange/emerald/blue-500)_는_사용되지_않는다', () => {
    const codes: TaskEventType[] = [
      'ASSIGN',
      'REASSIGN',
      'SUBMIT',
      'APPROVE',
      'REJECT',
    ];
    for (const c of codes) {
      const cls = dotClass(c);
      expect(cls).not.toMatch(/bg-(green|red|orange|emerald|blue)-\d/);
    }
  });
});
