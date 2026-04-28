interface CardProps {
  title?: string;
  description?: string;
  actions?: React.ReactNode;
  children?: React.ReactNode;
  className?: string;
}

export function Card({ title, description, actions, children, className = '' }: CardProps) {
  const hasHeader = title || description || actions;

  return (
    <div
      className={[
        'bg-white border border-gray-200 rounded-lg shadow-sm',
        className,
      ].join(' ')}
    >
      {hasHeader && (
        <div className="flex items-start justify-between px-6 py-4 border-b border-gray-100">
          <div className="flex-1 min-w-0 mr-4">
            {title && (
              <h3 className="text-base font-semibold text-gray-900 truncate">{title}</h3>
            )}
            {description && (
              <p className="text-sm text-gray-500 mt-0.5">{description}</p>
            )}
          </div>
          {actions && <div className="flex items-center gap-2 shrink-0">{actions}</div>}
        </div>
      )}
      <div className="px-6 py-4">{children}</div>
    </div>
  );
}

export default Card;
