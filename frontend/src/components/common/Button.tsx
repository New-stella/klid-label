import { forwardRef, type ButtonHTMLAttributes, type ReactNode } from 'react';

import { cn } from '@/lib/cn';

import { Spinner } from './Spinner';

export type ButtonVariant = 'primary' | 'secondary' | 'outline' | 'danger' | 'ghost';
export type ButtonSize = 'sm' | 'md' | 'lg';

export interface ButtonProps extends Omit<ButtonHTMLAttributes<HTMLButtonElement>, 'type'> {
  variant?: ButtonVariant;
  size?: ButtonSize;
  loading?: boolean;
  fullWidth?: boolean;
  type?: 'button' | 'submit' | 'reset';
  children?: ReactNode;
}

const variantClass: Record<ButtonVariant, string> = {
  primary:
    'bg-primary text-white border border-primary hover:opacity-90 disabled:opacity-50',
  secondary:
    'bg-secondary text-white border border-secondary hover:opacity-90 disabled:opacity-50',
  outline:
    'bg-white text-primary border border-primary hover:bg-bgLight disabled:opacity-50',
  danger:
    'bg-danger text-white border border-danger hover:opacity-90 disabled:opacity-50',
  ghost:
    'bg-transparent text-primary border border-transparent hover:bg-bgLight disabled:opacity-50',
};

const sizeClass: Record<ButtonSize, string> = {
  sm: 'h-8 px-3 text-sub',
  md: 'h-10 px-4 text-btn-label',
  lg: 'h-12 px-6 text-btn-label',
};

export const Button = forwardRef<HTMLButtonElement, ButtonProps>(function Button(
  {
    variant = 'primary',
    size = 'md',
    loading = false,
    fullWidth = false,
    disabled,
    type = 'button',
    className,
    children,
    ...rest
  },
  ref,
) {
  const isDisabled = disabled || loading;
  return (
    <button
      ref={ref}
      type={type}
      disabled={isDisabled}
      aria-busy={loading || undefined}
      className={cn(
        'inline-flex items-center justify-center gap-2 rounded font-medium transition-opacity duration-100 focus:outline-none focus-visible:ring-2 focus-visible:ring-accent disabled:cursor-not-allowed',
        variantClass[variant],
        sizeClass[size],
        fullWidth && 'w-full',
        className,
      )}
      {...rest}
    >
      {loading && <Spinner size="sm" label="처리 중" />}
      {children}
    </button>
  );
});
