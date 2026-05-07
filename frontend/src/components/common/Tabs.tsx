import { useId, type KeyboardEvent, type ReactNode } from 'react';

import { cn } from '@/lib/cn';

export interface TabItem {
  value: string;
  label: ReactNode;
  disabled?: boolean;
}

export interface TabsProps {
  items: TabItem[];
  value: string;
  onChange: (value: string) => void;
  ariaLabel?: string;
  children?: ReactNode;
  className?: string;
}

export function Tabs({
  items,
  value,
  onChange,
  ariaLabel = '탭',
  children,
  className,
}: TabsProps) {
  const baseId = useId();

  const handleKey = (e: KeyboardEvent<HTMLButtonElement>, index: number) => {
    if (e.key !== 'ArrowRight' && e.key !== 'ArrowLeft') return;
    e.preventDefault();
    const dir = e.key === 'ArrowRight' ? 1 : -1;
    let next = index;
    for (let i = 0; i < items.length; i++) {
      next = (next + dir + items.length) % items.length;
      if (!items[next]?.disabled) break;
    }
    const nextItem = items[next];
    if (nextItem) onChange(nextItem.value);
  };

  return (
    <div className={cn('flex flex-col', className)}>
      <div role="tablist" aria-label={ariaLabel} className="flex border-b border-border">
        {items.map((item, idx) => {
          const selected = item.value === value;
          const id = `${baseId}-tab-${item.value}`;
          const panelId = `${baseId}-panel-${item.value}`;
          return (
            <button
              key={item.value}
              id={id}
              type="button"
              role="tab"
              aria-selected={selected}
              aria-controls={panelId}
              tabIndex={selected ? 0 : -1}
              disabled={item.disabled}
              onClick={() => onChange(item.value)}
              onKeyDown={(e) => handleKey(e, idx)}
              className={cn(
                '-mb-px border-b-2 px-4 py-2 text-body transition-colors duration-100 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent disabled:opacity-40',
                selected
                  ? 'border-primary text-primary font-medium'
                  : 'border-transparent text-neutral hover:text-secondary',
              )}
            >
              {item.label}
            </button>
          );
        })}
      </div>
      {children && (
        <div
          role="tabpanel"
          id={`${baseId}-panel-${value}`}
          aria-labelledby={`${baseId}-tab-${value}`}
          className="pt-4"
        >
          {children}
        </div>
      )}
    </div>
  );
}
