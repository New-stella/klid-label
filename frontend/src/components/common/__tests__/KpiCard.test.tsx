import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import type { ColumnDef } from '@tanstack/react-table';

import { Checkbox } from '../Checkbox';
import { DataTable } from '../DataTable';
import { KpiCard } from '../KpiCard';

describe('KpiCard', () => {
  it('KpiCard_DataTable_포커스링_KRDS_3px', () => {
    // KpiCard(클릭형) 포커스링 검증
    const { container: kpiC } = render(
      <KpiCard label="누적" value={10} onClick={() => {}} />,
    );
    const kpiBtn = kpiC.querySelector('button') as HTMLButtonElement;
    expect(kpiBtn.className).toMatch(/focus-visible:ring-\[3px\]/);
    expect(kpiBtn.className).toMatch(/focus-visible:ring-offset-2/);

    // DataTable 정렬 버튼/체크박스 포커스링 검증 — 선택 컬럼은 호출부가 columns 에 조합한다(UI-007).
    const columns: ColumnDef<{ id: number }, unknown>[] = [
      {
        id: 'select',
        header: ({ table }) => (
          <Checkbox
            aria-label="모두 선택"
            checked={table.getIsAllRowsSelected()}
            onCheckedChange={(v) => table.toggleAllRowsSelected(v === true)}
          />
        ),
        cell: ({ row }) => (
          <Checkbox
            aria-label="행 선택"
            checked={row.getIsSelected()}
            onCheckedChange={(v) => row.toggleSelected(v === true)}
          />
        ),
      },
      { id: 'id', accessorKey: 'id', header: 'ID', enableSorting: true },
    ];
    const { container: dtC } = render(
      <DataTable<{ id: number }>
        columns={columns}
        data={[{ id: 1 }]}
        enableRowSelection
        sortable
      />,
    );
    const sortBtn = dtC.querySelector('thead button[type="button"]:not([role="checkbox"])') as HTMLButtonElement;
    expect(sortBtn.className).toMatch(/focus-visible:ring-\[3px\]/);
    const checkbox = dtC.querySelector('[role="checkbox"]') as HTMLElement;
    expect(checkbox.className).toMatch(/focus-visible:ring-\[3px\]/);
  });

  it('KpiCard_숫자_3자리_콤마_포맷', () => {
    render(<KpiCard label="누적 라벨" value={1234567} unit="건" />);
    expect(screen.getByText('1,234,567')).toBeInTheDocument();
  });

  it('KpiCard_trend_양수_success_색상', () => {
    render(<KpiCard label="이번주" value={100} trend={{ delta: 12, label: '대비' }} />);
    const trendSpan = screen.getByText(/12 대비/);
    // 양수 트렌드는 green 계열 (mock tone) — 부모 div 에 색상 클래스가 적용됨
    const trendContainer = trendSpan.parentElement;
    expect(trendContainer?.className).toMatch(/text-green-600|text-success/);
  });
});
