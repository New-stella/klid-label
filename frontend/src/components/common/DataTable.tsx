import { useState, type MouseEvent as ReactMouseEvent } from 'react';
import {
  flexRender,
  getCoreRowModel,
  getSortedRowModel,
  useReactTable,
  type ColumnDef,
  type OnChangeFn,
  type Row,
  type RowData,
  type RowSelectionState,
  type SortingState,
} from '@tanstack/react-table';
import { ArrowUpDown, ChevronDown, ChevronUp } from 'lucide-react';

import { cn } from '@/lib/cn';
import { KRDS_FOCUS } from '@/lib/focusRing';

import { EmptyState } from './EmptyState';
import { Skeleton } from './Skeleton';

/**
 * 컬럼 정의에 얹는 렌더 힌트(UI-007).
 *
 * 표준 `ColumnDef` 에는 정렬 정합·정렬 방향 같은 화면 세부까지는 없어서 `meta` 로 보강한다.
 * - `ariaSort`: 이 컴포넌트가 모르는 정렬축(서버 정렬 등, `sortable=false`)을 컬럼 정의 쪽에서
 *   직접 구성할 때, 그 컬럼이 스스로 `aria-sort` 를 알리고 싶으면 명시한다. 지정하지 않으면
 *   `aria-sort` 자체가 부여되지 않는다(accessibility_notes 참고).
 */
declare module '@tanstack/react-table' {
  // eslint-disable-next-line @typescript-eslint/no-unused-vars -- 원본 시그니처와 동일한 제네릭 매개변수 필요(모듈 augmentation)
  interface ColumnMeta<TData extends RowData, TValue> {
    align?: 'left' | 'center' | 'right';
    headerClassName?: string;
    cellClassName?: string;
    width?: string;
    ariaSort?: 'ascending' | 'descending' | 'none';
  }
}

export interface DataTableProps<T> {
  /** accessorKey/header/cell/size/enableSorting 등 표준 컬럼 정의. 헤더·셀은 flexRender 로 위임. */
  columns: ColumnDef<T, unknown>[];
  /** 표시할 행 데이터 — 서버가 이미 페이징한 현재 페이지 분량. */
  data: T[];
  /** 행 0건일 때 표시할 안내 문구. */
  emptyMessage?: string;
  /** 행 선택 가능 여부(함수면 행 단위 조건부 선택). true 여도 선택 컬럼은 자동 추가되지 않는다. */
  enableRowSelection?: boolean | ((row: Row<T>) => boolean);
  /** 제어형 선택 상태 맵(행 id → 선택 여부). 값의 소유·영속은 호출부 책임. */
  rowSelection?: RowSelectionState;
  onRowSelectionChange?: OnChangeFn<RowSelectionState>;
  /** React key 이자 선택 상태 키 산출 함수. 미지정 시 배열 인덱스로 대체된다. */
  getRowId?: (row: T, index: number) => string;
  /** 지정 시 행 전체가 클릭 가능해진다. 체크박스·버튼·링크·입력 클릭은 행 이동에서 제외. */
  onRowClick?: (row: T) => void;
  /** 표 영역 최소 높이 — 로딩 자리표시(DataTableSkeleton)에서 전환될 때 레이아웃 흔들림 방지. */
  minHeight?: number | string;
  /**
   * 컬럼 헤더 클릭으로 켜는 클라이언트(로컬) 정렬. 기본 꺼짐.
   * 서버가 정렬을 처리하는 목록에는 켜지 않는다 — 헤더 UI·정렬 상태를 컬럼 정의 쪽에서 직접
   * 구성해 서버 정렬 콜백에 연결하며, 로컬 정렬과 동시에 켜면 두 축이 충돌한다.
   */
  sortable?: boolean;
  /** sortable=true 일 때만 유효한 최초 정렬 상태. */
  initialSorting?: SortingState;
  className?: string;
}

// 헤더 글자색은 `gray-600` 이 하한이다 — 헤더 배경이 secondary-50(#EEF2F7)이라
// gray-500 은 그 위에서 4.01:1 로 AA(4.5:1) 미달이다(gray-600 은 5.60:1).
const HEADER_CLASS = 'px-4 py-3 text-table-header uppercase tracking-wide text-gray-600';
const CELL_CLASS = 'px-4 py-3 text-gray-700';

function alignClass(align?: 'left' | 'center' | 'right'): string | undefined {
  if (align === 'center') return 'text-center';
  if (align === 'right') return 'text-right';
  return undefined;
}

/**
 * 제네릭 데이터 테이블(UI-007) — 컬럼 정의 위의 얇은 렌더 계층이다.
 *
 * 페이지네이션·로딩 표시·선택 체크박스 컬럼·서버 정렬 헤더는 내장하지 않는다 — 호출부가
 * `columns` 와 반환 영역 아래에 조합한다. 자세한 조합 방법은 `usage_example`(UI-007) 참고.
 */
export function DataTable<T>({
  columns,
  data,
  emptyMessage = '데이터가 없습니다.',
  enableRowSelection,
  rowSelection,
  onRowSelectionChange,
  getRowId,
  onRowClick,
  minHeight,
  sortable = false,
  initialSorting,
  className,
}: DataTableProps<T>) {
  // 로컬(클라이언트) 정렬 상태 — sortable=true 일 때만 테이블에 연결한다.
  // 이 컴포넌트는 정렬 state 를 제어형으로 받지 않는다(initialSorting 만 최초값).
  const [internalSorting, setInternalSorting] = useState<SortingState>(initialSorting ?? []);

  const table = useReactTable({
    data,
    columns,
    getRowId,
    enableRowSelection,
    enableSorting: sortable,
    getCoreRowModel: getCoreRowModel(),
    ...(sortable ? { getSortedRowModel: getSortedRowModel() } : {}),
    state: {
      ...(sortable ? { sorting: internalSorting } : {}),
      ...(rowSelection !== undefined ? { rowSelection } : {}),
    },
    onSortingChange: sortable ? setInternalSorting : undefined,
    onRowSelectionChange,
  });

  const rows = table.getRowModel().rows;
  const leafColumnCount = table.getVisibleLeafColumns().length;

  const handleRowClick = (row: Row<T>) => (event: ReactMouseEvent<HTMLTableRowElement>) => {
    if (!onRowClick) return;
    const target = event.target as HTMLElement;
    // 체크박스(Radix Checkbox 는 role=checkbox 인 button)·버튼·링크·입력·label 클릭은 행 이동에서 제외.
    if (target.closest('button, a, input, label, [role="checkbox"]')) return;
    onRowClick(row.original);
  };

  return (
    <div
      className={cn(
        'overflow-auto rounded-lg border border-gray-200 bg-white shadow-sm',
        'max-h-[60vh]',
        className,
      )}
      style={minHeight !== undefined ? { minHeight } : undefined}
    >
      <table className="min-w-full text-body" role="table">
        {/* 헤더 배경은 secondary 스케일 최옅단(DS-001 do_rules) — 페이지 배경과 같은
            회색을 쓰면 열 구조가 먼저 읽히지 않는다. */}
        <thead className="sticky top-0 z-10 bg-secondary-50">
          {table.getHeaderGroups().map((headerGroup) => (
            <tr key={headerGroup.id} className="border-b border-gray-200">
              {headerGroup.headers.map((header) => {
                const meta = header.column.columnDef.meta;
                const canSort = sortable && header.column.getCanSort();
                const sortDirection = canSort ? header.column.getIsSorted() : false;
                const ariaSort = canSort
                  ? sortDirection === 'asc'
                    ? 'ascending'
                    : sortDirection === 'desc'
                      ? 'descending'
                      : 'none'
                  : meta?.ariaSort;
                return (
                  <th
                    key={header.id}
                    scope="col"
                    aria-sort={ariaSort}
                    style={meta?.width ? { width: meta.width } : undefined}
                    className={cn(HEADER_CLASS, alignClass(meta?.align), meta?.headerClassName)}
                  >
                    {header.isPlaceholder ? null : canSort ? (
                      <button
                        type="button"
                        onClick={header.column.getToggleSortingHandler()}
                        className={cn(
                          'inline-flex items-center gap-1 hover:text-primary-600',
                          KRDS_FOCUS,
                        )}
                      >
                        <span>{flexRender(header.column.columnDef.header, header.getContext())}</span>
                        {sortDirection === 'asc' ? (
                          <ChevronUp className="h-3 w-3" aria-hidden />
                        ) : sortDirection === 'desc' ? (
                          <ChevronDown className="h-3 w-3" aria-hidden />
                        ) : (
                          <ArrowUpDown className="h-3 w-3 opacity-40" aria-hidden />
                        )}
                      </button>
                    ) : (
                      flexRender(header.column.columnDef.header, header.getContext())
                    )}
                  </th>
                );
              })}
            </tr>
          ))}
        </thead>
        <tbody>
          {rows.length === 0 ? (
            <tr>
              <td colSpan={leafColumnCount} className="px-3 py-12">
                <EmptyState message={emptyMessage} />
              </td>
            </tr>
          ) : (
            rows.map((row) => (
              <tr
                key={row.id}
                className={cn(
                  'border-t border-gray-100 transition-colors',
                  // hover 표면은 클릭 가능 여부와 무관하게 rowHover 토큰 하나로 통일한다
                  // (DS-001 do_rules). 커서 모양만 클릭 가능 여부를 따른다.
                  // ⚠ 선택 상태(bg-primary-50)는 hover 와 다른 축이라 그대로 둔다.
                  'hover:bg-rowHover',
                  onRowClick && 'cursor-pointer',
                  row.getIsSelected() && 'bg-primary-50',
                )}
                onClick={handleRowClick(row)}
              >
                {row.getVisibleCells().map((cell) => {
                  const meta = cell.column.columnDef.meta;
                  return (
                    <td
                      key={cell.id}
                      className={cn(CELL_CLASS, alignClass(meta?.align), meta?.cellClassName)}
                    >
                      {flexRender(cell.column.columnDef.cell, cell.getContext())}
                    </td>
                  );
                })}
              </tr>
            ))
          )}
        </tbody>
      </table>
    </div>
  );
}

export interface DataTableSkeletonProps {
  /** 표시할 컬럼 수(선택 컬럼 포함) — 실제 columns 구성과 맞춰야 폭이 흔들리지 않는다. */
  columnCount: number;
  rowCount?: number;
  minHeight?: number | string;
  className?: string;
}

/**
 * 로딩 중 DataTable 자리를 대신하는 스켈레톤(UI-007 usage_example).
 *
 * DataTable 자신은 로딩을 내장하지 않는다 — 호출부가 데이터 도착 전까지 이 컴포넌트로 표
 * 영역 전체를 대체하고, 데이터가 오면 DataTable 로 교체한다.
 */
export function DataTableSkeleton({
  columnCount,
  rowCount = 5,
  minHeight,
  className,
}: DataTableSkeletonProps) {
  return (
    <div
      className={cn('overflow-hidden rounded-lg border border-gray-200 bg-white shadow-sm', className)}
      style={minHeight !== undefined ? { minHeight } : undefined}
    >
      <table className="min-w-full text-body">
        <tbody>
          {Array.from({ length: rowCount }).map((_, rowIdx) => (
            <tr key={rowIdx} className={cn(rowIdx > 0 && 'border-t border-gray-100')}>
              {Array.from({ length: columnCount }).map((__, colIdx) => (
                <td key={colIdx} className="px-4 py-3">
                  <Skeleton height={16} className="w-full" />
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

export type { ColumnDef, Row, RowSelectionState, SortingState } from '@tanstack/react-table';
