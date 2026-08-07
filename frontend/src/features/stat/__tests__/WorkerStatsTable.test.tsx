import { describe, expect, it } from 'vitest';
import { fireEvent, render, screen, within } from '@testing-library/react';

import { WorkerStatsTable, type WorkerRow } from '../components/WorkerStatsTable';

// 픽셀 원칙: labeled 와 reviewed 값이 모두 서로 달라야 "어느 필드를 렌더하는지" 단언으로
// 구분된다. 같은 값이면 렌더 버그가 통과해 버린다.
const rows: WorkerRow[] = [
  { userId: 1, name: '작업자A', labeled: 10, reviewed: 30, approvalRate: 90 },
  { userId: 2, name: '작업자B', labeled: 50, reviewed: 20, approvalRate: 80 },
  { userId: 3, name: '작업자C', labeled: 30, reviewed: 10, approvalRate: 70 },
];

function bodyRows() {
  const table = screen.getByTestId('worker-stats-table');
  return within(table).getAllByRole('row').slice(1); // 헤더 행 제외
}

describe('WorkerStatsTable', () => {
  it('라벨_컬럼_셀은_reviewed_값을_렌더한다', () => {
    // given: 구 버그 — "라벨" 헤더는 handleSort('reviewed') 로 정렬하는데 셀은 r.labeled 를
    // 그대로 렌더해, 헤더를 클릭해도 이 컬럼에 보이는 값이 움직이지 않았다.
    render(<WorkerStatsTable rows={rows} />);

    const row = bodyRows().find((r) => within(r).queryByText('작업자A'));
    expect(row).toBeDefined();
    const cells = within(row as HTMLElement).getAllByRole('cell');
    // 컬럼 순서: 작업자 / 완료(labeled) / 진행(reviewed) / 라벨(reviewed) / 오토라벨 / 반려율
    expect(cells[3]).toHaveTextContent('30'); // reviewed=30, labeled=10 이 아니다
  });

  it('라벨_헤더_클릭시_reviewed_값_기준으로_재정렬된다', () => {
    render(<WorkerStatsTable rows={rows} />);

    // when: '라벨' 헤더 클릭 (기본 정렬은 'labeled' 이므로 이 클릭으로 정렬축이 바뀌어야 한다)
    fireEvent.click(screen.getByRole('button', { name: /^라벨/ }));

    // then: reviewed 내림차순 — 작업자A(30) > 작업자B(20) > 작업자C(10)
    const names = bodyRows().map((r) => within(r).getAllByRole('cell')[0].textContent);
    expect(names).toEqual(['작업자A', '작업자B', '작업자C']);
  });
});
