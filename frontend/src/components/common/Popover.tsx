import { useEffect, useRef, useState, type ReactNode } from 'react';

import { cn } from '@/lib/cn';
import { KRDS_FOCUS } from '@/lib/focusRing';

export interface PopoverProps {
  trigger: ReactNode;
  children: ReactNode;
  placement?: 'bottom-start' | 'bottom-end' | 'top-start' | 'top-end';
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
  className,
  contentClassName,
}: PopoverProps) {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);

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
  }, [open]);

  return (
    <div ref={ref} className={cn('relative inline-block', className)}>
      <button
        type="button"
        aria-haspopup="dialog"
        aria-expanded={open}
        onClick={() => setOpen((v) => !v)}
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
