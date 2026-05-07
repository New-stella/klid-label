import { type ReactNode, useMemo } from 'react';
import { ChevronDown, ChevronUp, ArrowUpDown } from 'lucide-react';

import { cn } from '@/lib/cn';

import { EmptyState } from './EmptyState';
import { Pagination } from './Pagination';
import { Skeleton } from './Skeleton';

export interface DataTableColumn<T> {
  key: string;
  header: ReactNode;
  render?: (row: T) => ReactNode;
  sortable?: boolean;
  width?: string;
  align?: 'left' | 'center' | 'right';
}

export interface DataTableSelection {
  selected: Array<number | string>;
  onChange: (ids: Array<number | string>) => void;
  getId: (row: unknown) => number | string;
}

export interface DataTableProps<T> {
  columns: DataTableColumn<T>[];
  rows: T[];
  totalElements: number;
  page: number;
  size: number;
  onPageChange: (page: number) => void;
  onSortChange?: (sort: string) => void;
  sort?: string;
  selection?: DataTableSelection;
  loading?: boolean;
  emptyMessage?: string;
  rowKey?: (row: T) => string | number;
  onRowClick?: (row: T) => void;
  className?: string;
}

function parseSort(sort?: string): { key: string; dir: 'asc' | 'desc' } | null {
  if (!sort) return null;
  const [key, dir] = sort.split(',');
  if (!key) return null;
  return { key, dir: (dir as 'asc' | 'desc') ?? 'asc' };
}

export function DataTable<T>({
  columns,
  rows,
  totalElements,
  page,
  size,
  onPageChange,
  onSortChange,
  sort,
  selection,
  loading,
  emptyMessage = '데이터가 없습니다',
  rowKey,
  onRowClick,
  className,
}: DataTableProps<T>) {
  const parsedSort = useMemo(() => parseSort(sort), [sort]);

  const handleSort = (key: string) => {
    if (!onSortChange) return;
    if (parsedSort?.key === key) {
      const next = parsedSort.dir === 'asc' ? 'desc' : 'asc';
      onSortChange(`${key},${next}`);
    } else {
      onSortChange(`${key},asc`);
    }
  };

  const allSelected =
    !!selection && rows.length > 0 && rows.every((r) => selection.selected.includes(selection.getId(r)));
  const someSelected =
    !!selection && rows.some((r) => selection.selected.includes(selection.getId(r)));

  const handleSelectAll = () => {
    if (!selection) return;
    if (allSelected) {
      const removed = rows.map((r) => selection.getId(r));
      selection.onChange(selection.selected.filter((id) => !removed.includes(id)));
    } else {
      const ids = rows.map((r) => selection.getId(r));
      const merged = Array.from(new Set([...selection.selected, ...ids]));
      selection.onChange(merged);
    }
  };

  const handleSelectRow = (row: T) => {
    if (!selection) return;
    const id = selection.getId(row);
    if (selection.selected.includes(id)) {
      selection.onChange(selection.selected.filter((s) => s !== id));
    } else {
      selection.onChange([...selection.selected, id]);
    }
  };

  return (
    <div className={cn('flex flex-col gap-3', className)}>
      <div className="overflow-x-auto rounded border border-border">
        <table className="min-w-full text-body" role="table">
          <thead className="bg-bgLight">
            <tr>
              {selection && (
                <th scope="col" className="w-10 px-3 py-2">
                  <input
                    type="checkbox"
                    aria-label="전체 선택"
                    checked={allSelected}
                    ref={(el) => {
                      if (el) el.indeterminate = !allSelected && someSelected;
                    }}
                    onChange={handleSelectAll}
                    className="h-4 w-4 rounded border-border text-primary focus-visible:ring-2 focus-visible:ring-accent"
                  />
                </th>
              )}
              {columns.map((col) => {
                const isSorted = parsedSort?.key === col.key;
                const ariaSort = isSorted
                  ? parsedSort?.dir === 'asc'
                    ? 'ascending'
                    : 'descending'
                  : undefined;
                return (
                  <th
                    key={col.key}
                    scope="col"
                    aria-sort={col.sortable ? (ariaSort ?? 'none') : undefined}
                    style={col.width ? { width: col.width } : undefined}
                    className={cn(
                      'px-3 py-2 text-table-header text-primary',
                      col.align === 'center' && 'text-center',
                      col.align === 'right' && 'text-right',
                      col.align !== 'center' && col.align !== 'right' && 'text-left',
                    )}
                  >
                    {col.sortable ? (
                      <button
                        type="button"
                        onClick={() => handleSort(col.key)}
                        className="inline-flex items-center gap-1 hover:text-secondary focus-visible:ring-2 focus-visible:ring-accent"
                      >
                        <span>{col.header}</span>
                        {isSorted ? (
                          parsedSort?.dir === 'asc' ? (
                            <ChevronUp className="h-3 w-3" aria-hidden />
                          ) : (
                            <ChevronDown className="h-3 w-3" aria-hidden />
                          )
                        ) : (
                          <ArrowUpDown className="h-3 w-3 opacity-40" aria-hidden />
                        )}
                      </button>
                    ) : (
                      col.header
                    )}
                  </th>
                );
              })}
            </tr>
          </thead>
          <tbody>
            {loading
              ? Array.from({ length: Math.max(3, size) }).map((_, i) => (
                  <tr key={`skeleton-${i}`} className="border-t border-border">
                    {selection && (
                      <td className="px-3 py-2">
                        <Skeleton width={16} height={16} />
                      </td>
                    )}
                    {columns.map((col) => (
                      <td key={col.key} className="px-3 py-2">
                        <Skeleton height={16} className="w-full" />
                      </td>
                    ))}
                  </tr>
                ))
              : rows.length === 0
                ? (
                    <tr>
                      <td
                        colSpan={columns.length + (selection ? 1 : 0)}
                        className="px-3 py-12"
                      >
                        <EmptyState message={emptyMessage} />
                      </td>
                    </tr>
                  )
                : rows.map((row, idx) => {
                    const id = rowKey ? rowKey(row) : selection?.getId(row) ?? idx;
                    const checked =
                      !!selection && selection.selected.includes(selection.getId(row));
                    return (
                      <tr
                        key={String(id)}
                        className={cn(
                          'border-t border-border hover:bg-bgLight',
                          onRowClick && 'cursor-pointer',
                          checked && 'bg-accent/5',
                        )}
                        onClick={onRowClick ? () => onRowClick(row) : undefined}
                      >
                        {selection && (
                          <td
                            className="px-3 py-2"
                            onClick={(e) => e.stopPropagation()}
                          >
                            <input
                              type="checkbox"
                              aria-label="행 선택"
                              checked={checked}
                              onChange={() => handleSelectRow(row)}
                              className="h-4 w-4 rounded border-border text-primary focus-visible:ring-2 focus-visible:ring-accent"
                            />
                          </td>
                        )}
                        {columns.map((col) => (
                          <td
                            key={col.key}
                            className={cn(
                              'px-3 py-2',
                              col.align === 'center' && 'text-center',
                              col.align === 'right' && 'text-right',
                            )}
                          >
                            {col.render
                              ? col.render(row)
                              : (row as unknown as Record<string, ReactNode>)[col.key]}
                          </td>
                        ))}
                      </tr>
                    );
                  })}
          </tbody>
        </table>
      </div>
      <Pagination
        page={page}
        size={size}
        totalElements={totalElements}
        onPageChange={onPageChange}
      />
    </div>
  );
}
