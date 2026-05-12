import { forwardRef, type ButtonHTMLAttributes, type ComponentType, type ReactNode } from 'react';

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
  leftIcon?: ComponentType<{ className?: string }>;
  rightIcon?: ComponentType<{ className?: string }>;
  children?: ReactNode;
}

const variantClass: Record<ButtonVariant, string> = {
  primary:
    'bg-primary-600 !text-white hover:bg-primary-700 active:bg-primary-800 disabled:bg-primary-300',
  secondary:
    'bg-white text-gray-700 border border-gray-300 hover:bg-gray-50 active:bg-gray-100 disabled:text-gray-400 disabled:border-gray-200',
  outline:
    'bg-white text-primary-600 border border-primary-600 hover:bg-primary-50 active:bg-primary-100 disabled:text-primary-300 disabled:border-primary-200',
  danger:
    'bg-danger text-white hover:bg-red-600 active:bg-red-700 disabled:bg-red-300',
  ghost:
    'bg-transparent text-gray-600 hover:bg-gray-100 active:bg-gray-200 disabled:text-gray-400',
};

const sizeClass: Record<ButtonSize, string> = {
  sm: 'text-xs px-3 py-1.5 gap-1.5',
  md: 'h-10 px-4 text-btn-label gap-2',
  lg: 'px-5 py-2.5 text-btn-label gap-2',
};

const iconSize: Record<ButtonSize, string> = {
  sm: 'h-3.5 w-3.5',
  md: 'h-4 w-4',
  lg: 'h-5 w-5',
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
    leftIcon: LeftIcon,
    rightIcon: RightIcon,
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
        'inline-flex items-center justify-center rounded-lg font-medium transition-colors duration-100 focus:outline-none focus-visible:ring-2 focus-visible:ring-primary-500 focus-visible:ring-offset-1 disabled:cursor-not-allowed',
        variantClass[variant],
        sizeClass[size],
        fullWidth && 'w-full',
        className,
      )}
      {...rest}
    >
      {loading && <Spinner size="sm" label="처리 중" />}
      {!loading && LeftIcon && <LeftIcon className={iconSize[size]} />}
      {children}
      {!loading && RightIcon && <RightIcon className={iconSize[size]} />}
    </button>
  );
});
