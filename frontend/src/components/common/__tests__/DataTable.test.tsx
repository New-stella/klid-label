import { useState } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ColumnDef, RowSelectionState } from '@tanstack/react-table';

import { Checkbox } from '../Checkbox';
import { DataTable, DataTableSkeleton } from '../DataTable';

interface Row {
  id: number;
  name: string;
}

const columns: ColumnDef<Row, unknown>[] = [
  { id: 'id', header: 'ID', cell: ({ row }) => row.original.id },
  { id: 'name', header: '이름', cell: ({ row }) => row.original.name },
];

const rows: Row[] = [
  { id: 1, name: 'A' },
  { id: 2, name: 'B' },
  { id: 3, name: 'C' },
];

/** 정렬 가능한(로컬) 컬럼 정의 — sortable=true 테스트 전용. */
const sortableColumns: ColumnDef<Row, unknown>[] = [
  { id: 'id', accessorKey: 'id', header: 'ID', enableSorting: true },
  { id: 'name', accessorKey: 'name', header: '이름', enableSorting: true },
];

/** 선택 컬럼을 조합한 테스트 하네스 — usage_example 이 요구하는 조합형 패턴을 그대로 재현. */
function SelectableTable({
  onSelectionChange,
}: {
  onSelectionChange?: (s: RowSelectionState) => void;
}) {
  const [selection, setSelection] = useState<RowSelectionState>({});
  const selectionColumns: ColumnDef<Row, unknown>[] = [
    {
      id: 'select',
      header: ({ table }) => (
        <Checkbox
          aria-label="모두 선택"
          checked={
            table.getIsAllRowsSelected() ? true : table.getIsSomeRowsSelected() ? 'indeterminate' : false
          }
          onCheckedChange={(v) => table.toggleAllRowsSelected(v === true)}
        />
      ),
      cell: ({ row }) => (
        <Checkbox
          aria-label={`${row.original.name} 행 선택`}
          checked={row.getIsSelected()}
          onCheckedChange={(v) => row.toggleSelected(v === true)}
        />
      ),
    },
    ...columns,
  ];
  return (
    <DataTable<Row>
      columns={selectionColumns}
      data={rows}
      enableRowSelection
      getRowId={(r) => String(r.id)}
      rowSelection={selection}
      onRowSelectionChange={(updater) => {
        setSelection((prev) => {
          const next = typeof updater === 'function' ? updater(prev) : updater;
          onSelectionChange?.(next);
          return next;
        });
      }}
    />
  );
}

describe('DataTable', () => {
  it('DataTable_빈_data_EmptyState_렌더', () => {
    render(<DataTable<Row> columns={columns} data={[]} />);
    expect(screen.getByText('데이터가 없습니다.')).toBeInTheDocument();
  });

  it('DataTable_emptyMessage_커스텀_문구_렌더', () => {
    render(<DataTable<Row> columns={columns} data={[]} emptyMessage="검색 결과가 없습니다" />);
    expect(screen.getByText('검색 결과가 없습니다')).toBeInTheDocument();
    expect(screen.queryByText('데이터가 없습니다.')).not.toBeInTheDocument();
  });

  it('DataTable_행_렌더', () => {
    render(<DataTable<Row> columns={columns} data={rows} />);
    expect(screen.getByText('A')).toBeInTheDocument();
    expect(screen.getByText('B')).toBeInTheDocument();
    expect(screen.getByText('C')).toBeInTheDocument();
  });

  it('DataTable_onRowClick_지정시_행_클릭으로_콜백_호출', async () => {
    const user = userEvent.setup();
    const onRowClick = vi.fn();
    render(<DataTable<Row> columns={columns} data={rows} onRowClick={onRowClick} />);
    await user.click(screen.getByText('A'));
    expect(onRowClick).toHaveBeenCalledWith(rows[0]);
  });

  it('DataTable_체크박스_클릭은_행_이동으로_전파되지_않는다', async () => {
    const user = userEvent.setup();
    const onSelectionChange = vi.fn();
    // onRowClick + 선택 컬럼을 함께 조합했을 때 체크박스 클릭이 행 이동을 트리거하지 않아야 한다.
    const onRowClick = vi.fn();
    function Harness() {
      const [selection, setSelection] = useState<RowSelectionState>({});
      const selectionColumns: ColumnDef<Row, unknown>[] = [
        {
          id: 'select',
          header: () => null,
          cell: ({ row }) => (
            <Checkbox
              aria-label={`${row.original.name} 행 선택`}
              checked={row.getIsSelected()}
              onCheckedChange={(v) => row.toggleSelected(v === true)}
            />
          ),
        },
        ...columns,
      ];
      return (
        <DataTable<Row>
          columns={selectionColumns}
          data={rows}
          enableRowSelection
          getRowId={(r) => String(r.id)}
          rowSelection={selection}
          onRowSelectionChange={(updater) => {
            setSelection((prev) => {
              const next = typeof updater === 'function' ? updater(prev) : updater;
              onSelectionChange(next);
              return next;
            });
          }}
          onRowClick={onRowClick}
        />
      );
    }
    render(<Harness />);
    await user.click(screen.getByLabelText('A 행 선택'));
    expect(onRowClick).not.toHaveBeenCalled();
    expect(onSelectionChange).toHaveBeenCalledWith({ '1': true });
  });

  it('DataTable_전체선택_체크박스는_부분선택시_indeterminate(mixed)이다', async () => {
    const user = userEvent.setup();
    const onSelectionChange = vi.fn();
    render(<SelectableTable onSelectionChange={onSelectionChange} />);

    // ★ 클릭마다 DOM 을 재조회한다 — Radix Checkbox 는 checked 값 종류(boolean↔'indeterminate')가
    // 바뀌면 내부적으로 노드를 다시 그릴 수 있어, 클릭 전에 캐시한 참조는 stale 해질 수 있다.
    expect(screen.getByLabelText('모두 선택')).toHaveAttribute('aria-checked', 'false');

    await user.click(screen.getByLabelText('A 행 선택'));
    expect(screen.getByLabelText('모두 선택')).toHaveAttribute('aria-checked', 'mixed');

    await user.click(screen.getByLabelText('B 행 선택'));
    await user.click(screen.getByLabelText('C 행 선택'));
    expect(screen.getByLabelText('모두 선택')).toHaveAttribute('aria-checked', 'true');
  });

  it('DataTable_전체선택_클릭시_전체_토글된다', async () => {
    const user = userEvent.setup();
    render(<SelectableTable />);
    await user.click(screen.getByLabelText('모두 선택'));
    expect(screen.getByLabelText('A 행 선택')).toHaveAttribute('aria-checked', 'true');
    expect(screen.getByLabelText('B 행 선택')).toHaveAttribute('aria-checked', 'true');
    expect(screen.getByLabelText('C 행 선택')).toHaveAttribute('aria-checked', 'true');

    await user.click(screen.getByLabelText('모두 선택'));
    expect(screen.getByLabelText('A 행 선택')).toHaveAttribute('aria-checked', 'false');
  });

  it('DataTable_선택_체크박스_44px_히트영역(KRDS)', () => {
    render(<SelectableTable />);
    const selectAll = screen.getByLabelText('모두 선택');
    expect(selectAll.className).toMatch(/min-h-11/);
    expect(selectAll.className).toMatch(/min-w-11/);
    const rowCheckbox = screen.getByLabelText('A 행 선택');
    expect(rowCheckbox.className).toMatch(/min-h-11/);
    expect(rowCheckbox.className).toMatch(/min-w-11/);
  });

  it('DataTable_sortable_로컬_정렬_클릭시_방향_토글_및_aria_sort', async () => {
    const user = userEvent.setup();
    render(<DataTable<Row> columns={sortableColumns} data={rows} sortable />);

    const nameHeader = screen.getByRole('columnheader', { name: /이름/ });
    expect(nameHeader).toHaveAttribute('aria-sort', 'none');

    await user.click(screen.getByRole('button', { name: /이름/ }));
    expect(nameHeader).toHaveAttribute('aria-sort', 'ascending');

    await user.click(screen.getByRole('button', { name: /이름/ }));
    expect(nameHeader).toHaveAttribute('aria-sort', 'descending');
  });

  it('DataTable_sortable_false(기본값)면_헤더가_버튼이_아니다', () => {
    render(<DataTable<Row> columns={sortableColumns} data={rows} />);
    expect(screen.queryByRole('button', { name: /이름/ })).not.toBeInTheDocument();
    const nameHeader = screen.getByRole('columnheader', { name: /이름/ });
    expect(nameHeader).not.toHaveAttribute('aria-sort');
  });

  it('DataTable_컬럼_meta_ariaSort로_서버_정렬_축을_직접_노출할_수_있다', () => {
    const serverSortColumns: ColumnDef<Row, unknown>[] = [
      { id: 'id', header: 'ID', cell: ({ row }) => row.original.id, meta: { ariaSort: 'ascending' } },
      { id: 'name', header: '이름', cell: ({ row }) => row.original.name },
    ];
    render(<DataTable<Row> columns={serverSortColumns} data={rows} />);
    expect(screen.getByRole('columnheader', { name: 'ID' })).toHaveAttribute('aria-sort', 'ascending');
    expect(screen.getByRole('columnheader', { name: '이름' })).not.toHaveAttribute('aria-sort');
  });

  it('DataTable_minHeight_적용', () => {
    const { container } = render(<DataTable<Row> columns={columns} data={rows} minHeight={240} />);
    const wrapper = container.firstElementChild as HTMLElement;
    expect(wrapper.style.minHeight).toBe('240px');
  });
});

describe('DataTableSkeleton', () => {
  it('DataTableSkeleton_행_열_수만큼_렌더', () => {
    const { container } = render(<DataTableSkeleton columnCount={3} rowCount={4} />);
    expect(container.querySelectorAll('tbody tr')).toHaveLength(4);
    expect(container.querySelectorAll('tbody tr:first-child td')).toHaveLength(3);
  });

  it('DataTableSkeleton_기본_rowCount는_5', () => {
    const { container } = render(<DataTableSkeleton columnCount={2} />);
    expect(container.querySelectorAll('tbody tr')).toHaveLength(5);
  });
});
