import { describe, expect, it } from 'vitest';
import { fireEvent, render, screen, within } from '@testing-library/react';

import { WorkerStatsTable, type WorkerRow } from '../components/WorkerStatsTable';

/**
 * 확정 사양 6컬럼: 작업자 / 라벨(labeled) / 진행(inProgress) / 검수(reviewed) /
 * 오토라벨(autoLabelRate) / 반려율(100 - approvalRate).
 *
 * 픽셀 원칙: 각 행의 수치가 <b>전부 서로 달라야</b> "어느 필드를 렌더하는지" 단언으로 구분된다.
 * 같은 값을 쓰면 두 컬럼이 같은 필드를 그리는 버그(실제로 진행·라벨 컬럼이 둘 다 reviewed 를
 * 그리고 있었다)가 그대로 통과한다.
 */
const rows: WorkerRow[] = [
  { userId: 1, name: '작업자A', labeled: 10, reviewed: 30, approvalRate: 90, inProgress: 7, autoLabelRate: 12 },
  { userId: 2, name: '작업자B', labeled: 50, reviewed: 20, approvalRate: 80, inProgress: 3, autoLabelRate: 64 },
  { userId: 3, name: '작업자C', labeled: 30, reviewed: 10, approvalRate: 70, inProgress: 9, autoLabelRate: 38 },
];

function bodyRows() {
  const table = screen.getByTestId('worker-stats-table');
  return within(table).getAllByRole('row').slice(1); // 헤더 행 제외
}

function cellsOf(name: string) {
  const row = bodyRows().find((r) => within(r).queryByText(name));
  expect(row).toBeDefined();
  return within(row as HTMLElement).getAllByRole('cell');
}

function orderedNames() {
  return bodyRows().map((r) => within(r).getAllByRole('cell')[0].textContent);
}

describe('WorkerStatsTable', () => {
  it('표는_사양의_6컬럼을_순서대로_렌더한다', () => {
    render(<WorkerStatsTable rows={rows} />);

    const headers = within(screen.getByTestId('worker-stats-table'))
      .getAllByRole('columnheader')
      .map((h) => h.textContent?.replace(/[↓↑\s]/g, ''));

    expect(headers).toEqual(['작업자', '라벨', '진행', '검수', '오토라벨', '반려율']);
  });

  it('각_컬럼은_사양이_정한_필드값을_렌더한다', () => {
    render(<WorkerStatsTable rows={rows} />);

    const cells = cellsOf('작업자A');
    expect(cells[1]).toHaveTextContent('10'); // 라벨 = labeled
    expect(cells[2]).toHaveTextContent('7'); // 진행 = inProgress
    expect(cells[3]).toHaveTextContent('30'); // 검수 = reviewed
    expect(cells[4]).toHaveTextContent('12.0%'); // 오토라벨 = autoLabelRate
    expect(cells[5]).toHaveTextContent('10.0%'); // 반려율 = 100 - approvalRate(90)
  });

  it('진행_컬럼과_검수_컬럼은_서로_다른_값을_렌더한다', () => {
    // 구 버그: 두 컬럼이 모두 reviewed 를 그려 같은 숫자가 나란히 찍혔다.
    render(<WorkerStatsTable rows={rows} />);

    const cells = cellsOf('작업자B');
    expect(cells[2]).toHaveTextContent('3'); // inProgress
    expect(cells[3]).toHaveTextContent('20'); // reviewed
    expect(cells[2].textContent).not.toEqual(cells[3].textContent);
  });

  it('라벨_헤더_클릭시_labeled_기준으로_재정렬된다', () => {
    render(<WorkerStatsTable rows={rows} />);

    // 기본 정렬이 이미 labeled 내림차순이므로, 클릭하면 오름차순으로 뒤집혀야 한다.
    fireEvent.click(screen.getByRole('button', { name: /^라벨/ }));

    // labeled 오름차순 — A(10) < C(30) < B(50)
    expect(orderedNames()).toEqual(['작업자A', '작업자C', '작업자B']);
  });

  it('진행_헤더_클릭시_inProgress_기준으로_재정렬된다', () => {
    render(<WorkerStatsTable rows={rows} />);

    fireEvent.click(screen.getByRole('button', { name: /^진행/ }));

    // inProgress 내림차순 — C(9) > A(7) > B(3)
    expect(orderedNames()).toEqual(['작업자C', '작업자A', '작업자B']);
  });

  it('검수_헤더_클릭시_reviewed_기준으로_재정렬된다', () => {
    render(<WorkerStatsTable rows={rows} />);

    fireEvent.click(screen.getByRole('button', { name: /^검수/ }));

    // reviewed 내림차순 — A(30) > B(20) > C(10)
    expect(orderedNames()).toEqual(['작업자A', '작업자B', '작업자C']);
  });

  it('오토라벨_헤더_클릭시_autoLabelRate_기준으로_재정렬된다', () => {
    // 구 버그: 오토라벨 헤더에 정렬 버튼은 있었는데 비교값이 항상 0 이라 클릭해도 순서가 그대로였다.
    render(<WorkerStatsTable rows={rows} />);

    fireEvent.click(screen.getByRole('button', { name: /^오토라벨/ }));

    // autoLabelRate 내림차순 — B(64) > C(38) > A(12)
    expect(orderedNames()).toEqual(['작업자B', '작업자C', '작업자A']);
  });

  it('정렬_헤더는_자기가_표시하는_값을_기준으로_정렬한다', () => {
    // 헤더는 A 로 정렬하는데 셀은 B 를 그리던 것이 이 표 결함의 뿌리였다.
    // 정렬 후 "그 컬럼에 보이는 값들"이 실제로 단조 정렬돼 있는지로 축 일치를 확인한다.
    // (헤더 클릭은 같은 축이면 방향만 뒤집으므로 방향은 화살표 표기에서 읽는다.)
    render(<WorkerStatsTable rows={rows} />);

    const columns: { header: RegExp; cellIndex: number }[] = [
      { header: /^라벨/, cellIndex: 1 },
      { header: /^진행/, cellIndex: 2 },
      { header: /^검수/, cellIndex: 3 },
      { header: /^오토라벨/, cellIndex: 4 },
    ];

    for (const { header, cellIndex } of columns) {
      const button = screen.getByRole('button', { name: header });
      fireEvent.click(button);

      const desc = (button.textContent ?? '').includes('↓');
      const values = bodyRows().map((r) =>
        Number((within(r).getAllByRole('cell')[cellIndex].textContent ?? '').replace(/[^\d.]/g, '')),
      );
      const expected = [...values].sort((a, b) => (desc ? b - a : a - b));

      // 셀이 다른 필드를 그리고 있으면 이 단조성이 깨진다.
      expect(values).toEqual(expected);
      // 정렬축이 실제로 이 컬럼으로 옮겨왔는지 (화살표가 이 헤더에만 있는지)도 함께 고정한다.
      expect(button.textContent).toMatch(/[↓↑]/);
    }
  });

  it('오토라벨_값이_없으면_자리표시를_렌더한다', () => {
    // 값이 실제로 없을 때만 '—'. 하드코딩 자리표시로 되돌아가면 위 렌더/정렬 테스트가 먼저 깨진다.
    const missing = [
      { ...rows[0], autoLabelRate: undefined as unknown as number },
    ] satisfies WorkerRow[];
    render(<WorkerStatsTable rows={missing} />);

    expect(cellsOf('작업자A')[4]).toHaveTextContent('—');
  });

  it('행이_없으면_안내_문구를_렌더한다', () => {
    render(<WorkerStatsTable rows={[]} />);

    expect(screen.getByText('작업자 통계가 없습니다')).toBeInTheDocument();
  });
});
