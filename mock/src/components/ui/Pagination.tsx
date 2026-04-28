import { ChevronLeft, ChevronRight } from 'lucide-react';

interface PaginationProps {
  page: number;
  totalPages: number;
  onChange: (p: number) => void;
  className?: string;
}

export function Pagination({ page, totalPages, onChange, className = '' }: PaginationProps) {
  if (totalPages <= 1) return null;

  // Build window of 5 pages
  const windowSize = 5;
  let start = Math.max(0, page - Math.floor(windowSize / 2));
  let end = start + windowSize;
  if (end > totalPages) {
    end = totalPages;
    start = Math.max(0, end - windowSize);
  }
  const pages = Array.from({ length: end - start }, (_, i) => start + i);

  return (
    <div className={['flex items-center justify-center gap-1 mt-4', className].join(' ')}>
      <button
        onClick={() => onChange(page - 1)}
        disabled={page === 0}
        className="inline-flex items-center justify-center w-8 h-8 rounded-md text-gray-500 hover:bg-gray-100 disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
        aria-label="이전 페이지"
      >
        <ChevronLeft size={16} />
      </button>

      {start > 0 && (
        <>
          <button
            onClick={() => onChange(0)}
            className="inline-flex items-center justify-center w-8 h-8 rounded-md text-sm text-gray-600 hover:bg-gray-100 transition-colors"
          >
            1
          </button>
          {start > 1 && (
            <span className="w-8 h-8 inline-flex items-center justify-center text-gray-400 text-sm">
              …
            </span>
          )}
        </>
      )}

      {pages.map((p) => (
        <button
          key={p}
          onClick={() => onChange(p)}
          className={[
            'inline-flex items-center justify-center w-8 h-8 rounded-md text-sm font-medium transition-colors',
            p === page
              ? 'bg-primary-600 text-white'
              : 'text-gray-600 hover:bg-gray-100',
          ].join(' ')}
          aria-current={p === page ? 'page' : undefined}
        >
          {p + 1}
        </button>
      ))}

      {end < totalPages && (
        <>
          {end < totalPages - 1 && (
            <span className="w-8 h-8 inline-flex items-center justify-center text-gray-400 text-sm">
              …
            </span>
          )}
          <button
            onClick={() => onChange(totalPages - 1)}
            className="inline-flex items-center justify-center w-8 h-8 rounded-md text-sm text-gray-600 hover:bg-gray-100 transition-colors"
          >
            {totalPages}
          </button>
        </>
      )}

      <button
        onClick={() => onChange(page + 1)}
        disabled={page >= totalPages - 1}
        className="inline-flex items-center justify-center w-8 h-8 rounded-md text-gray-500 hover:bg-gray-100 disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
        aria-label="다음 페이지"
      >
        <ChevronRight size={16} />
      </button>
    </div>
  );
}

export default Pagination;
