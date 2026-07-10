import { forwardRef, useId, type InputHTMLAttributes, type ReactNode } from 'react';

import { cn } from '@/lib/cn';
import { KRDS_FOCUS } from '@/lib/focusRing';

export interface RadioProps extends Omit<InputHTMLAttributes<HTMLInputElement>, 'type'> {
  label?: ReactNode;
}

export const Radio = forwardRef<HTMLInputElement, RadioProps>(function Radio(
  { label, id, className, ...rest },
  ref,
) {
  const autoId = useId();
  const fieldId = id ?? autoId;
  return (
    // KRDS 터치 타깃 44px: input 을 label 로 감싸 44px 행 전체를 클릭 영역으로 확장.
    // 시각 크기(h-4 w-4)는 유지하고 min-h-11(44px) 로 hit area 만 확장.
    // label 미지정 시 폭도 min-w-11(44px) 로 가드(정사각 히트영역).
    <label
      className={cn(
        'inline-flex min-h-11 cursor-pointer select-none items-center gap-2',
        !label && 'min-w-11 justify-center',
      )}
    >
      <input
        ref={ref}
        id={fieldId}
        type="radio"
        className={cn(
          'h-4 w-4 border-gray-300 text-primary-600',
          KRDS_FOCUS,
          className,
        )}
        {...rest}
      />
      {label && <span className="text-body text-gray-700">{label}</span>}
    </label>
  );
});
