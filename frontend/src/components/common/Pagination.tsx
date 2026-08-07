import { ChevronLeft, ChevronRight } from 'lucide-react';

import { cn } from '@/lib/cn';
import { KRDS_FOCUS } from '@/lib/focusRing';

export interface PaginationProps {
  page: number; // 0-based
  size: number;
  totalElements: number;
  onPageChange: (page: number) => void;
  className?: string;
}

/** 번호 목록 항목 — 페이지 번호(0-based) 또는 말줄임 자리. */
type PageSlot = number | 'ellipsis';

/**
 * 노출할 페이지 번호 목록을 만든다.
 *
 * 규칙(UI-008): **양끝(첫·마지막) + 현재 앞뒤 1칸**만 남기고 그 사이는 말줄임으로 접는다.
 * 고정 개수의 창을 옆으로 미끄러뜨리는 방식은 쓰지 않는다 — 번호가 많아질수록 끝 페이지로
 * 가는 경로가 사라지기 때문이다(그래서 처음·마지막 전용 버튼도 두지 않는다).
 *
 * 사이에 빠지는 페이지가 **정확히 1개**뿐이면 말줄임 대신 그 번호를 그대로 보여준다 —
 * 말줄임이 번호 하나를 대신 가리면 폭도 줄지 않고 도달 경로만 사라진다.
 */
export function buildPageSlots(current: number, totalPages: number): PageSlot[] {
  const last = totalPages - 1;
  const keep = new Set<number>([0, last, current - 1, current, current + 1]);
  const pages = [...keep].filter((n) => n >= 0 && n <= last).sort((a, b) => a - b);

  const slots: PageSlot[] = [];
  pages.forEach((n, i) => {
    if (i > 0) {
      const prev = pages[i - 1]!;
      if (n - prev === 2) slots.push(prev + 1);
      else if (n - prev > 2) slots.push('ellipsis');
    }
    slots.push(n);
  });
  return slots;
}

export function Pagination({
  page,
  size,
  totalElements,
  onPageChange,
  className,
}: PaginationProps) {
  const totalPages = Math.max(1, Math.ceil(totalElements / size));
  const safePage = Math.min(Math.max(page, 0), totalPages - 1);

  const start = totalElements === 0 ? 0 : safePage * size + 1;
  const end = Math.min(totalElements, (safePage + 1) * size);

  const slots = buildPageSlots(safePage, totalPages);

  const isFirst = safePage === 0;
  const isLast = safePage >= totalPages - 1;

  // 이동 요청은 범위로 가두고, 현재 페이지와 같으면 콜백을 부르지 않는다
  // (같은 페이지를 다시 조회하지 않게 하기 위해서다).
  const go = (next: number) => {
    const clamped = Math.min(Math.max(next, 0), totalPages - 1);
    if (clamped === safePage) return;
    onPageChange(clamped);
  };

  // KRDS 터치 타깃 44x44px: 페이지 버튼 h-11 min-w-11 로 확장.
  const baseBtn = cn(
    'inline-flex h-11 min-w-11 items-center justify-center rounded-md px-2 text-sub text-gray-600 transition-colors hover:bg-gray-100 disabled:cursor-not-allowed disabled:opacity-40',
    KRDS_FOCUS,
  );

  return (
    <nav
      aria-label="페이지네이션"
      className={cn('flex items-center justify-between gap-4', className)}
    >
      <span className="text-sub text-gray-500" aria-live="polite">
        {start}-{end} / 총 {totalElements.toLocaleString('ko-KR')}건
      </span>
      <div className="inline-flex items-center gap-1">
        <button
          type="button"
          aria-label="이전 페이지"
          disabled={isFirst}
          aria-disabled={isFirst || undefined}
          onClick={() => go(safePage - 1)}
          className={baseBtn}
        >
          <ChevronLeft className="h-4 w-4" aria-hidden />
        </button>
        {slots.map((slot, i) =>
          slot === 'ellipsis' ? (
            // 말줄임 자리는 보조기술이 읽을 필요가 없다 — 숫자 목록의 흐름만 유지한다.
            <span
              // 같은 목록에 말줄임이 최대 2개 나오므로 위치를 키에 포함한다.
              key={`ellipsis-${i}`}
              aria-hidden="true"
              className="inline-flex h-11 min-w-8 items-center justify-center text-sub text-gray-400"
            >
              …
            </span>
          ) : (
            <button
              key={slot}
              type="button"
              aria-label={`${slot + 1}페이지`}
              aria-current={slot === safePage ? 'page' : undefined}
              onClick={() => go(slot)}
              className={cn(
                baseBtn,
                slot === safePage &&
                  'bg-primary-600 text-white hover:bg-primary-700 font-medium',
              )}
            >
              {slot + 1}
            </button>
          ),
        )}
        <button
          type="button"
          aria-label="다음 페이지"
          disabled={isLast}
          aria-disabled={isLast || undefined}
          onClick={() => go(safePage + 1)}
          className={baseBtn}
        >
          <ChevronRight className="h-4 w-4" aria-hidden />
        </button>
      </div>
    </nav>
  );
}
