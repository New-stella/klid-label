import { forwardRef, type ComponentPropsWithoutRef, type ElementRef } from 'react';
import * as CheckboxPrimitive from '@radix-ui/react-checkbox';
import { Check, Minus } from 'lucide-react';

import { cn } from '@/lib/cn';
import { KRDS_FOCUS } from '@/lib/focusRing';

import { useFieldContext, useFieldControl } from './fieldContext';

/**
 * 공통 체크박스 프리미티브 (UI-024).
 *
 * `checked` 는 `boolean | 'indeterminate'` 3상태이며 변경은 `onCheckedChange` 로 받는다.
 * `'indeterminate'` 는 `aria-checked="mixed"` 로 노출되어 스크린리더가 부분선택을 인지한다
 * (별도 `indeterminate` prop 을 두지 않는다).
 *
 * 라벨 텍스트를 자체적으로 갖지 않는 순수 컨트롤이다 — 라벨은 호출부가
 * `Field`(orientation='horizontal') + `FieldLabel` 로 옆에 배치한다.
 *
 * **접근 가능한 이름은 `aria-labelledby` 로 잇는다.** 이 컨트롤의 실체는 `role="checkbox"` 인
 * `button` 인데, `button` 의 이름 계산은 `aria-labelledby` → `aria-label` → **자기 서브트리** →
 * `title` 순이고 `<label for>` 연결은 그 계산에 들어가지 않는다(HTML-AAM). 서브트리는
 * `aria-hidden` 인 시각 사각형뿐이라, 옆에 라벨을 두어도 낭독기에는 이름 없는 체크박스로
 * 들린다. `FieldLabel` 이 있으면 그 요소 id(`labelId`)를 가리켜 이름을 만든다.
 * 호출부가 `aria-label`/`aria-labelledby` 를 직접 주면 그 값이 항상 우선한다.
 *
 * **KRDS 44px 터치 타깃**은 두 경우로 갈린다(회귀 지점):
 * - `FieldLabel` 이 붙는 경우 → 라벨이 44px 행(min-h-11)을 만들고 `htmlFor` 로 토글까지 받으므로
 *   컨트롤이 폭을 따로 예약하지 않는다.
 * - 라벨이 없는 경우(표 전체선택·이력 행 선택 등) → 넓혀 줄 라벨이 없으므로 컨트롤 자신이
 *   44×44 히트영역을 예약한다.
 *
 * 히트영역은 **컨트롤 자신(button)의 크기**로 확보하고 시각 사각형은 내부 span 이 그린다.
 * `::before` 오버레이는 Firefox 가 폼 컨트롤의 가상요소를 렌더하지 않아 성립하지 않고,
 * 절대배치 오버레이는 옆 버튼을 덮는다.
 */
export type CheckboxProps = Omit<
  ComponentPropsWithoutRef<typeof CheckboxPrimitive.Root>,
  'asChild'
>;

export const Checkbox = forwardRef<ElementRef<typeof CheckboxPrimitive.Root>, CheckboxProps>(
  function Checkbox(
    {
      id,
      className,
      'aria-describedby': ariaDescribedBy,
      'aria-invalid': ariaInvalid,
      'aria-label': ariaLabel,
      'aria-labelledby': ariaLabelledBy,
      ...rest
    },
    ref,
  ) {
    const field = useFieldContext();
    const {
      id: fieldId,
      describedBy,
      invalid,
    } = useFieldControl({
      id,
      'aria-describedby': ariaDescribedBy,
      'aria-invalid': ariaInvalid,
    });

    // 옆에 붙는 FieldLabel 이 44px 행을 만들어 주면 컨트롤이 폭을 예약하지 않는다.
    const hitAreaSelfManaged = !field?.hasLabel;

    const labelledBy =
      ariaLabelledBy ?? (ariaLabel === undefined && field?.hasLabel ? field.labelId : undefined);

    return (
      <CheckboxPrimitive.Root
        ref={ref}
        id={fieldId}
        aria-invalid={invalid}
        aria-describedby={describedBy}
        aria-label={ariaLabel}
        aria-labelledby={labelledBy}
        className={cn(
          'group/checkbox inline-flex min-h-11 shrink-0 cursor-pointer select-none items-center justify-center rounded-md disabled:cursor-not-allowed disabled:opacity-60',
          hitAreaSelfManaged && 'min-w-11',
          KRDS_FOCUS,
          className,
        )}
        {...rest}
      >
        <span
          aria-hidden="true"
          className={cn(
            'flex h-5 w-5 items-center justify-center rounded border bg-white text-white transition-colors duration-100',
            invalid ? 'border-danger' : 'border-gray-300',
            'group-data-[state=checked]/checkbox:border-primary-600 group-data-[state=checked]/checkbox:bg-primary-600',
            'group-data-[state=indeterminate]/checkbox:border-primary-600 group-data-[state=indeterminate]/checkbox:bg-primary-600',
          )}
        >
          {/*
            아이콘 분기는 루트의 data-state 로 한다 — 비제어 모드도 따라가고,
            Radix `Indicator`(Presence)의 마운트 애니메이션 상태머신을 타지 않아
            테스트에서 act() 경고를 만들지 않는다.
          */}
          <Check
            className="hidden h-3.5 w-3.5 group-data-[state=checked]/checkbox:block"
            strokeWidth={3}
          />
          <Minus
            className="hidden h-3.5 w-3.5 group-data-[state=indeterminate]/checkbox:block"
            strokeWidth={3}
          />
        </span>
      </CheckboxPrimitive.Root>
    );
  },
);
