interface TabItem {
  value: string;
  label: string;
  badge?: string | number;
}

interface TabsProps {
  tabs: TabItem[];
  value: string;
  onChange: (value: string) => void;
  className?: string;
}

export function Tabs({ tabs, value, onChange, className = '' }: TabsProps) {
  return (
    <div
      className={[
        'flex border-b border-gray-200 gap-0',
        className,
      ].join(' ')}
      role="tablist"
    >
      {tabs.map((tab) => {
        const isActive = tab.value === value;
        return (
          <button
            key={tab.value}
            role="tab"
            aria-selected={isActive}
            onClick={() => onChange(tab.value)}
            className={[
              'flex items-center gap-1.5 px-4 py-2.5 text-sm font-medium border-b-2 transition-colors -mb-px',
              isActive
                ? 'border-primary-500 text-primary-600'
                : 'border-transparent text-gray-500 hover:text-gray-700 hover:border-gray-300',
            ].join(' ')}
          >
            {tab.label}
            {tab.badge !== undefined && (
              <span
                className={[
                  'inline-flex items-center justify-center rounded-full text-xs font-semibold min-w-[1.25rem] h-5 px-1',
                  isActive
                    ? 'bg-primary-100 text-primary-700'
                    : 'bg-gray-100 text-gray-600',
                ].join(' ')}
              >
                {tab.badge}
              </span>
            )}
          </button>
        );
      })}
    </div>
  );
}

export default Tabs;
