import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { DataTable, type DataTableColumn } from '../DataTable';

interface Row {
  id: number;
  name: string;
}

const columns: DataTableColumn<Row>[] = [
  { key: 'id', header: 'ID', sortable: true },
  { key: 'name', header: '이름', sortable: true },
];

const rows: Row[] = [
  { id: 1, name: 'A' },
  { id: 2, name: 'B' },
  { id: 3, name: 'C' },
];

describe('DataTable', () => {
  it('DataTable_loading_시_Skeleton_렌더', () => {
    render(
      <DataTable<Row>
        columns={columns}
        rows={[]}
        totalElements={0}
        page={0}
        size={10}
        onPageChange={() => {}}
        loading
      />,
    );
    // 빈 EmptyState 메시지 미노출 확인
    expect(screen.queryByText('데이터가 없습니다')).not.toBeInTheDocument();
  });

  it('DataTable_빈_rows_EmptyState_렌더', () => {
    render(
      <DataTable<Row>
        columns={columns}
        rows={[]}
        totalElements={0}
        page={0}
        size={10}
        onPageChange={() => {}}
      />,
    );
    expect(screen.getByText('데이터가 없습니다')).toBeInTheDocument();
  });

  it('DataTable_체크박스_전체_선택_토글', async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    render(
      <DataTable<Row>
        columns={columns}
        rows={rows}
        totalElements={3}
        page={0}
        size={10}
        onPageChange={() => {}}
        selection={{ selected: [], onChange, getId: (r) => (r as Row).id }}
      />,
    );
    await user.click(screen.getByLabelText('전체 선택'));
    expect(onChange).toHaveBeenCalledWith([1, 2, 3]);
  });

  it('DataTable_정렬_클릭_시_onSortChange_호출', async () => {
    const user = userEvent.setup();
    const onSortChange = vi.fn();
    render(
      <DataTable<Row>
        columns={columns}
        rows={rows}
        totalElements={3}
        page={0}
        size={10}
        onPageChange={() => {}}
        onSortChange={onSortChange}
      />,
    );
    await user.click(screen.getByRole('button', { name: /이름/ }));
    expect(onSortChange).toHaveBeenCalledWith('name,asc');
  });

  it('DataTable_정렬_재클릭_시_방향_토글', async () => {
    const user = userEvent.setup();
    const onSortChange = vi.fn();
    render(
      <DataTable<Row>
        columns={columns}
        rows={rows}
        totalElements={3}
        page={0}
        size={10}
        onPageChange={() => {}}
        sort="name,asc"
        onSortChange={onSortChange}
      />,
    );
    await user.click(screen.getByRole('button', { name: /이름/ }));
    expect(onSortChange).toHaveBeenCalledWith('name,desc');
  });
});
