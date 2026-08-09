import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react';

import { cn } from '@/lib/cn';
import { KRDS_FOCUS } from '@/lib/focusRing';

export interface PopoverProps {
  trigger: ReactNode;
  children: ReactNode;
  placement?: 'bottom-start' | 'bottom-end' | 'top-start' | 'top-end';
  /**
   * 지정하면 controlled 모드로 전환된다 — 열림 상태를 외부에서 직접 소유한다
   * (값 선택 즉시 닫기 등). 미지정이면 내부 state 로 자동 관리한다.
   */
  open?: boolean;
  /** controlled 모드에서 열림 상태가 바뀔 때 호출. `open` 과 함께 쓴다. */
  onOpenChange?: (open: boolean) => void;
  className?: string;
  contentClassName?: string;
}

const placementClass: Record<NonNullable<PopoverProps['placement']>, string> = {
  'bottom-start': 'top-full left-0 mt-1',
  'bottom-end': 'top-full right-0 mt-1',
  'top-start': 'bottom-full left-0 mb-1',
  'top-end': 'bottom-full right-0 mb-1',
};

export function Popover({
  trigger,
  children,
  placement = 'bottom-start',
  open: openProp,
  onOpenChange,
  className,
  contentClassName,
}: PopoverProps) {
  const [uncontrolledOpen, setUncontrolledOpen] = useState(false);
  // `open` 이 넘어온 순간부터 controlled — 내부 state 는 쓰지 않는다.
  const isControlled = openProp !== undefined;
  const open = isControlled ? openProp : uncontrolledOpen;
  const ref = useRef<HTMLDivElement>(null);

  // 비제어면 내부 state 를 갱신하고, 어느 모드든 콜백은 항상 통지한다
  // (controlled 에서 상태를 바꾸는 주체는 호출부다).
  const setOpen = useCallback(
    (next: boolean) => {
      if (!isControlled) setUncontrolledOpen(next);
      onOpenChange?.(next);
    },
    [isControlled, onOpenChange],
  );

  useEffect(() => {
    if (!open) return;
    const handler = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) {
        setOpen(false);
      }
    };
    const escHandler = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setOpen(false);
    };
    document.addEventListener('mousedown', handler);
    document.addEventListener('keydown', escHandler);
    return () => {
      document.removeEventListener('mousedown', handler);
      document.removeEventListener('keydown', escHandler);
    };
  }, [open, setOpen]);

  return (
    <div ref={ref} className={cn('relative inline-block', className)}>
      <button
        type="button"
        aria-haspopup="dialog"
        aria-expanded={open}
        onClick={() => setOpen(!open)}
        className={cn(
          'inline-flex min-h-11 min-w-11 items-center justify-center rounded-md',
          KRDS_FOCUS,
        )}
      >
        {trigger}
      </button>
      {open && (
        <div
          role="dialog"
          className={cn(
            'absolute z-30 min-w-40 rounded-lg border border-gray-200 bg-white p-2 shadow-lg',
            placementClass[placement],
            contentClassName,
          )}
        >
          {children}
        </div>
      )}
    </div>
  );
}
