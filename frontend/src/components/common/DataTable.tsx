import { type ReactNode, useMemo } from 'react';
import { ChevronDown, ChevronUp, ArrowUpDown } from 'lucide-react';

import { cn } from '@/lib/cn';
import { KRDS_FOCUS } from '@/lib/focusRing';

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
      <div className="overflow-x-auto rounded-lg border border-gray-200 bg-white shadow-sm">
        <table className="min-w-full text-body" role="table">
          <thead className="bg-gray-50">
            <tr className="border-b border-gray-200">
              {selection && (
                <th scope="col" className="w-12 px-2 py-1">
                  {/* KRDS 44px 클릭영역: 시각 크기(h-4 w-4)는 유지하고 label 래퍼로 히트영역 확장 */}
                  <label className="mx-auto flex h-11 w-11 cursor-pointer items-center justify-center">
                    <input
                      type="checkbox"
                      aria-label="전체 선택"
                      checked={allSelected}
                      ref={(el) => {
                        if (el) el.indeterminate = !allSelected && someSelected;
                      }}
                      onChange={handleSelectAll}
                      className={cn('h-4 w-4 rounded border-gray-300 text-primary-600', KRDS_FOCUS)}
                    />
                  </label>
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
                      'px-4 py-3 text-table-header uppercase tracking-wide text-gray-500',
                      col.align === 'center' && 'text-center',
                      col.align === 'right' && 'text-right',
                      col.align !== 'center' && col.align !== 'right' && 'text-left',
                    )}
                  >
                    {col.sortable ? (
                      <button
                        type="button"
                        onClick={() => handleSort(col.key)}
                        className={cn(
                          'inline-flex items-center gap-1 hover:text-primary-600',
                          KRDS_FOCUS,
                        )}
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
                  <tr key={`skeleton-${i}`} className="border-t border-gray-100">
                    {selection && (
                      <td className="px-4 py-3">
                        <Skeleton width={16} height={16} />
                      </td>
                    )}
                    {columns.map((col) => (
                      <td key={col.key} className="px-4 py-3">
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
                    // rowKey 가 undefined/null 을 반환할 경우(데이터 alias 누락 등) idx fallback 으로 React key 경고 회피
                    const candidate = rowKey ? rowKey(row) : selection?.getId(row);
                    const id = candidate === undefined || candidate === null ? idx : candidate;
                    const checked =
                      !!selection && selection.selected.includes(selection.getId(row));
                    return (
                      <tr
                        key={String(id)}
                        className={cn(
                          'border-t border-gray-100 transition-colors',
                          onRowClick ? 'cursor-pointer hover:bg-primary-50' : 'hover:bg-gray-50',
                          checked && 'bg-primary-50',
                        )}
                        onClick={onRowClick ? () => onRowClick(row) : undefined}
                      >
                        {selection && (
                          <td
                            className="px-2 py-1"
                            onClick={(e) => e.stopPropagation()}
                          >
                            {/* KRDS 44px 클릭영역: 시각 크기 유지, label 래퍼로 히트영역 확장 */}
                            <label className="mx-auto flex h-11 w-11 cursor-pointer items-center justify-center">
                              <input
                                type="checkbox"
                                aria-label="행 선택"
                                checked={checked}
                                onChange={() => handleSelectRow(row)}
                                className={cn('h-4 w-4 rounded border-gray-300 text-primary-600', KRDS_FOCUS)}
                              />
                            </label>
                          </td>
                        )}
                        {columns.map((col) => (
                          <td
                            key={col.key}
                            className={cn(
                              'px-4 py-3 text-gray-700',
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
