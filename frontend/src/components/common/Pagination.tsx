import { ChevronLeft, ChevronRight, ChevronsLeft, ChevronsRight } from 'lucide-react';

import { cn } from '@/lib/cn';

export interface PaginationProps {
  page: number; // 0-based
  size: number;
  totalElements: number;
  onPageChange: (page: number) => void;
  visiblePages?: number;
  className?: string;
}

export function Pagination({
  page,
  size,
  totalElements,
  onPageChange,
  visiblePages = 5,
  className,
}: PaginationProps) {
  const totalPages = Math.max(1, Math.ceil(totalElements / size));
  const safePage = Math.min(Math.max(page, 0), totalPages - 1);

  const start = totalElements === 0 ? 0 : safePage * size + 1;
  const end = Math.min(totalElements, (safePage + 1) * size);

  // 페이지 번호 윈도우 계산
  const half = Math.floor(visiblePages / 2);
  let from = Math.max(0, safePage - half);
  const to = Math.min(totalPages - 1, from + visiblePages - 1);
  from = Math.max(0, Math.min(from, to - visiblePages + 1));
  const numbers: number[] = [];
  for (let i = from; i <= to; i++) numbers.push(i);

  const isFirst = safePage === 0;
  const isLast = safePage >= totalPages - 1;

  const baseBtn =
    'inline-flex h-8 min-w-8 items-center justify-center rounded border border-border px-2 text-sub hover:bg-bgLight disabled:cursor-not-allowed disabled:opacity-40 focus-visible:ring-2 focus-visible:ring-accent';

  return (
    <nav
      aria-label="페이지네이션"
      className={cn('flex items-center justify-between gap-4', className)}
    >
      <span className="text-sub text-neutral" aria-live="polite">
        {start}-{end} / 총 {totalElements.toLocaleString('ko-KR')}건
      </span>
      <div className="inline-flex items-center gap-1">
        <button
          type="button"
          aria-label="첫 페이지"
          disabled={isFirst}
          onClick={() => onPageChange(0)}
          className={baseBtn}
        >
          <ChevronsLeft className="h-4 w-4" aria-hidden />
        </button>
        <button
          type="button"
          aria-label="이전 페이지"
          disabled={isFirst}
          onClick={() => onPageChange(safePage - 1)}
          className={baseBtn}
        >
          <ChevronLeft className="h-4 w-4" aria-hidden />
        </button>
        {numbers.map((n) => (
          <button
            key={n}
            type="button"
            aria-label={`${n + 1}페이지`}
            aria-current={n === safePage ? 'page' : undefined}
            onClick={() => onPageChange(n)}
            className={cn(
              baseBtn,
              n === safePage && 'border-primary bg-primary text-white hover:opacity-90',
            )}
          >
            {n + 1}
          </button>
        ))}
        <button
          type="button"
          aria-label="다음 페이지"
          disabled={isLast}
          onClick={() => onPageChange(safePage + 1)}
          className={baseBtn}
        >
          <ChevronRight className="h-4 w-4" aria-hidden />
        </button>
        <button
          type="button"
          aria-label="마지막 페이지"
          disabled={isLast}
          onClick={() => onPageChange(totalPages - 1)}
          className={baseBtn}
        >
          <ChevronsRight className="h-4 w-4" aria-hidden />
        </button>
      </div>
    </nav>
  );
}
