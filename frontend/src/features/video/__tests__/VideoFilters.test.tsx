import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';

import { VideoFilters } from '../components/VideoFilters';

// 이벤트 유형 옵션은 서버 조회(useEventTypes) 결과라 여기서는 관심 밖 — 빈 목록으로 고정한다.
vi.mock('@/features/eventType/hooks', () => ({
  useEventTypes: () => ({ data: [], isLoading: false }),
}));

describe('VideoFilters 상태 옵션', () => {
  it('상태_드롭다운은_BE_배치단계_상태_5종을_모두_노출한다', () => {
    // given / when
    render(<VideoFilters initial={{ page: 0, size: 20 }} onApply={() => {}} />);
    const select = screen.getByLabelText('상태') as HTMLSelectElement;

    // then: BE LsDataRaw.DATA_STTS_* 와 1:1 (MARKING_READY 누락 시 마킹 대기 영상을 좁힐 수 없다)
    const values = Array.from(select.options).map((o) => o.value);
    expect(values).toEqual([
      '',
      'COMPLETED',
      'PROCESSING',
      'MARKING_READY',
      'PENDING',
      'FAILED',
    ]);
    expect(screen.getByRole('option', { name: '마킹 대기' })).toBeInTheDocument();
  });
});
