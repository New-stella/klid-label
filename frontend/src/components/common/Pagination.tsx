import { ChevronLeft, ChevronRight } from 'lucide-react';

import { cn } from '@/lib/cn';
import { KRDS_FOCUS } from '@/lib/focusRing';

export interface PaginationProps {
  /** 현재 페이지(0부터). 화면 표시는 1부터의 번호로 한다. */
  page: number;
  /**
   * 전체 페이지 수. **서버 응답의 값을 그대로 받는다** — 총 건수와 페이지 크기로
   * 되계산하지 않는다(페이지 크기 규칙을 이 컨트롤이 또 알 필요가 없도록).
   *
   * 서버가 없는 클라이언트 측 페이징이면 호출부가 {@link pageCountOf} 로 구해 넘긴다.
   */
  totalPages: number;
  /** 페이지 이동 요청(0부터). 범위 밖 요청은 가두고, 현재 페이지와 같으면 부르지 않는다. */
  onChange: (page: number) => void;
  className?: string;
}

/**
 * 전체를 받아 화면에서 잘라 보여주는(클라이언트 측 페이징) 목록의 전체 페이지 수.
 *
 * 서버 페이징 화면은 이 함수를 쓰지 않는다 — 응답의 `totalPages` 를 그대로 넘긴다.
 * 호출부마다 `Math.ceil` 을 다시 쓰면 0건·나머지 처리가 갈리므로 규칙을 여기 한 곳에 둔다.
 *
 * 0건은 **1페이지**로 센다 — 아래 {@link Pagination} 의 빈 목록 규칙과 같은 값이어야
 * "0페이지 중 1페이지" 같은 어긋난 표시가 생기지 않는다.
 */
export function pageCountOf(totalElements: number, pageSize: number): number {
  if (!Number.isFinite(totalElements) || !Number.isFinite(pageSize) || pageSize <= 0) return 1;
  return Math.max(1, Math.ceil(totalElements / pageSize));
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

export function Pagination({ page, totalPages, onChange, className }: PaginationProps) {
  /*
   * 빈 목록(서버가 totalPages=0 을 내려보내는 경우)은 **1페이지**로 센다.
   * 0 으로 두면 노출할 번호가 하나도 없어 컨트롤이 통째로 사라지고, 그러면 사양이 요구한
   * "자리는 유지해 버튼 위치가 흔들리지 않게 한다"가 깨진다. 규칙은 여기 한 곳에서만 정하고
   * 호출부는 응답값을 그대로 넘긴다.
   */
  const pageCount = Number.isFinite(totalPages) ? Math.max(1, Math.floor(totalPages)) : 1;
  const safePage = Math.min(Math.max(page, 0), pageCount - 1);

  const slots = buildPageSlots(safePage, pageCount);

  const isFirst = safePage === 0;
  const isLast = safePage >= pageCount - 1;

  // 이동 요청은 범위로 가두고, 현재 페이지와 같으면 콜백을 부르지 않는다
  // (같은 페이지를 다시 조회하지 않게 하기 위해서다).
  const go = (next: number) => {
    const clamped = Math.min(Math.max(next, 0), pageCount - 1);
    if (clamped === safePage) return;
    onChange(clamped);
  };

  // KRDS 터치 타깃 44x44px: 페이지 버튼 h-11 min-w-11 로 확장.
  const baseBtn = cn(
    'inline-flex h-11 min-w-11 items-center justify-center rounded-md px-2 text-sub text-gray-600 transition-colors hover:bg-gray-100 disabled:cursor-not-allowed disabled:opacity-40',
    KRDS_FOCUS,
  );

  return (
    // 구성은 이전 · 페이지 번호 목록 · 다음 셋뿐이다(UI-008). 총 건수 요약은 이 컨트롤이
    // 갖지 않는다 — 건수를 알려면 총 건수와 페이지 크기를 또 받아야 하는데, 그러면 페이지 수를
    // 직접 받기로 한 계약이 무너진다. 건수 표기가 필요한 화면은 화면 쪽에서 소유한다.
    <nav aria-label="페이지네이션" className={cn('flex items-center justify-center', className)}>
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
