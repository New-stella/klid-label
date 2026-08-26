import {
  Children,
  forwardRef,
  isValidElement,
  useCallback,
  type ComponentPropsWithoutRef,
  type ElementRef,
  type ReactNode,
} from 'react';
import * as SelectPrimitive from '@radix-ui/react-select';
import { Check, ChevronDown, ChevronUp, ChevronsUpDown } from 'lucide-react';

import { cn } from '@/lib/cn';
import { KRDS_FOCUS } from '@/lib/focusRing';

import { useFieldControl } from './fieldContext';

/**
 * 공통 셀렉트 프리미티브 (UI-003) — Radix `Select` 위에 조립하는 조합형 컴포넌트.
 *
 * `label`/`hint`/`error` 를 자체적으로 두지 않는다 — 문구 배치는 `Field` 계열 조립부(UI-099)가
 * 전담한다. `SelectTrigger` 에 `aria-invalid` 전달 시 보더가 destructive 로 전환되며 오류 문구는
 * 렌더하지 않는다.
 *
 * 옵션 배열을 통째로 받는 단일 prop(`options`)은 두지 않는다 — 호출부가 옵션 데이터를
 * `SelectItem` 자식으로 직접 매핑해 그룹·구분선·비활성 옵션 등 임의 구성을 조립한다.
 *
 * 구성: `Select`(루트, 상태 보유) · `SelectTrigger`(트리거 버튼, `size='default'|'sm'`) ·
 * `SelectValue`(선택값 또는 placeholder 텍스트) · `SelectContent`(포털 드롭다운 패널,
 * `position='item-aligned'|'popper'`) · `SelectItem`(개별 옵션, 선택 시 체크 아이콘) ·
 * `SelectGroup`/`SelectLabel`/`SelectSeparator`(옵션 묶음·소제목·구분선) ·
 * `SelectScrollUpButton`/`SelectScrollDownButton`(패널 스크롤 시 자동 노출).
 *
 * ### 빈 문자열 값 처리 (Critical)
 * Radix 는 `SelectItem` 의 값으로 빈 문자열을 예약해 둔다 — 빈 문자열은 "미선택
 * (placeholder 노출)" 신호로 쓰여서, `SelectItem value=""` 를 그대로 넘기면 그 옵션이
 * 선택돼도 트리거가 placeholder 를 계속 보여준다("선택됨"과 "미선택"이 시각적으로
 * 구분되지 않는다). 그런데 이 저장소의 여러 호출부는 "전체"/"선택 안 함"처럼
 * **선택 가능한 빈 값 옵션**이 필요하고, 그 값은 URL 쿼리파라미터·컴포넌트 상태·서버
 * 요청까지 전부 `''` 를 전제한다.
 *
 * 그래서 이 경계 **한 곳**(`toInternalValue`/`toExternalValue`)에서만 내부 센티넬로
 * 왕복 변환한다. `Select`(값 in/out)와 `SelectItem`(값 in) 두 지점 모두 이 헬퍼를 거치므로
 * 호출부·상태·URL 은 항상 `''` 그대로 보고 센티넬은 컴포넌트 경계를 벗어나지 않는다.
 *
 * ⚠ 이 저장소에는 `''` 를 쓰는 **서로 다른 두 의도**가 공존한다 — ①"전체"/"선택 안 함"처럼
 * 실제로 선택 가능한 빈 값 옵션(예: `EnvironmentMetaPanel`) ②아직 아무것도 고르지 않은 진짜
 * 미선택 상태(예: `VersionPicker`, 옵션 목록에 빈 값이 아예 없다). ①은 센티넬 왕복이 필요하지만
 * ②에 똑같이 적용하면 센티넬이 어떤 `SelectItem` 과도 매치되지 않아 트리거가 통째로 빈 채로
 * 렌더된다(placeholder 도 못 보여준다 — Radix 의 placeholder 판정은 값이 정확히 `''` 일 때만
 * 발동한다). 그래서 `Select` 는 렌더 시점에 자신의 `children`(=SelectContent 하위 트리)을
 * 얕게 스캔해 `value=""` 인 `SelectItem` 이 **실제로 존재할 때만** 센티넬을 적용한다 — 없으면
 * Radix 네이티브 placeholder 동작(빈 문자열=미선택)을 그대로 살려 둔다.
 */
const EMPTY_VALUE_SENTINEL = '__select-empty-value__';

/** `node` 트리 안에 `value=""` 인 `SelectItem` 이 있는지 얕게(직접 자식 경로만) 스캔한다. */
function containsEmptySelectItem(node: ReactNode): boolean {
  let found = false;
  Children.forEach(node, (child) => {
    if (found || !isValidElement(child)) return;
    if (child.type === SelectItem) {
      if ((child.props as SelectItemProps).value === '') found = true;
      return;
    }
    const nested = (child.props as { children?: ReactNode } | null | undefined)?.children;
    if (nested !== undefined && containsEmptySelectItem(nested)) found = true;
  });
  return found;
}

function toInternalValue(value: string, allowNativeEmpty: boolean): string;
function toInternalValue(
  value: string | undefined,
  allowNativeEmpty: boolean,
): string | undefined;
function toInternalValue(
  value: string | undefined,
  allowNativeEmpty: boolean,
): string | undefined {
  if (value === undefined) return undefined;
  if (value !== '') return value;
  return allowNativeEmpty ? '' : EMPTY_VALUE_SENTINEL;
}

function toExternalValue(value: string): string {
  return value === EMPTY_VALUE_SENTINEL ? '' : value;
}

/**
 * 옵션 데이터 형태 — `Select` 자체의 prop 이 아니라, 호출부가 배열을 `SelectItem` 자식으로
 * 매핑할 때 쓰는 **데이터 셰이프**다(조립 편의 타입). `<Select options={...}>` 처럼 컴포넌트에
 * 통째로 넘기는 단일 prop 은 어디에도 없다.
 */
export interface SelectOption {
  value: string;
  label: string;
  disabled?: boolean;
}

export interface SelectProps
  extends Omit<
    ComponentPropsWithoutRef<typeof SelectPrimitive.Root>,
    'value' | 'defaultValue' | 'onValueChange'
  > {
  /** 제어 모드 선택값. 빈 문자열('')은 "선택 안 함" 옵션과 왕복 매핑된다. */
  value?: string;
  /** 비제어 모드 초기값. */
  defaultValue?: string;
  /** 값 변경 콜백. */
  onValueChange?: (value: string) => void;
}

/** 셀렉트 루트 — 상태(제어/비제어)를 보유하며 DOM 을 직접 렌더하지 않는다. */
export function Select({ value, defaultValue, onValueChange, children, ...rest }: SelectProps) {
  const handleValueChange = useCallback(
    (next: string) => onValueChange?.(toExternalValue(next)),
    [onValueChange],
  );
  // children 에 value="" SelectItem 이 실제로 있을 때만 센티넬 왕복을 적용한다(위 클래스 주석 참조).
  const allowNativeEmpty = !containsEmptySelectItem(children);

  return (
    <SelectPrimitive.Root
      value={toInternalValue(value, allowNativeEmpty)}
      defaultValue={toInternalValue(defaultValue, allowNativeEmpty)}
      onValueChange={handleValueChange}
      {...rest}
    >
      {children}
    </SelectPrimitive.Root>
  );
}

/** 옵션 묶음 — 소제목(`SelectLabel`)과 함께 그룹으로 표시할 때 쓴다. */
export const SelectGroup = SelectPrimitive.Group;

/**
 * 선택값 또는 placeholder 텍스트 표시.
 * placeholder muted 색 처리는 `SelectTrigger` 의 `data-[placeholder]` 셀렉터가 담당한다
 * (Radix 가 그 데이터 속성을 실제로 렌더하는 지점이 Value 가 아니라 Trigger 이기 때문).
 */
export const SelectValue = SelectPrimitive.Value;

const TRIGGER_SIZE_CLASS = {
  // KRDS 44px 최소 터치 타깃 — 입력·버튼과 같은 높이로 한 폼 줄에서 정렬이 맞는다.
  default: 'h-11 px-3 text-body',
  // 표 안 액션·툴바 등 밀집 배치 전용 예외. 단독 터치 타깃으로는 쓰지 않는다.
  sm: 'h-8 px-2 gap-1 text-caption',
} as const;

export interface SelectTriggerProps extends ComponentPropsWithoutRef<typeof SelectPrimitive.Trigger> {
  /** 트리거 버튼 높이. default=44px(KRDS 최소 터치 타깃) / sm=밀집 배치 전용, 44px 미만 허용. */
  size?: keyof typeof TRIGGER_SIZE_CLASS;
}

export const SelectTrigger = forwardRef<ElementRef<typeof SelectPrimitive.Trigger>, SelectTriggerProps>(
  function SelectTrigger(
    {
      size = 'default',
      id,
      className,
      children,
      'aria-describedby': ariaDescribedBy,
      'aria-invalid': ariaInvalid,
      ...rest
    },
    ref,
  ) {
    const {
      id: fieldId,
      describedBy,
      invalid,
      hasError,
    } = useFieldControl({
      id,
      'aria-describedby': ariaDescribedBy,
      'aria-invalid': ariaInvalid,
    });

    return (
      <SelectPrimitive.Trigger
        ref={ref}
        id={fieldId}
        data-slot="select-trigger"
        data-size={size}
        aria-invalid={invalid}
        aria-describedby={describedBy}
        className={cn(
          'flex w-full items-center justify-between gap-2 rounded-md border bg-white text-gray-900 outline-none transition-colors duration-100 disabled:cursor-not-allowed disabled:bg-gray-50 disabled:opacity-60 data-[placeholder]:text-gray-600 [&_svg]:pointer-events-none [&_svg]:shrink-0',
          TRIGGER_SIZE_CLASS[size],
          KRDS_FOCUS,
          hasError
            ? 'border-danger focus-visible:border-danger'
            : 'border-gray-300 focus-visible:border-primary-500',
          className,
        )}
        {...rest}
      >
        {children}
        <SelectPrimitive.Icon asChild>
          <ChevronsUpDown className="h-4 w-4 shrink-0 text-gray-400" aria-hidden="true" />
        </SelectPrimitive.Icon>
      </SelectPrimitive.Trigger>
    );
  },
);

export interface SelectContentProps extends ComponentPropsWithoutRef<typeof SelectPrimitive.Content> {}

/** 포털 드롭다운 패널. `position='item-aligned'`(기본) 이 아니면 popper 배치로 전환된다. */
export const SelectContent = forwardRef<ElementRef<typeof SelectPrimitive.Content>, SelectContentProps>(
  function SelectContent({ className, children, position = 'item-aligned', ...rest }, ref) {
    return (
      <SelectPrimitive.Portal>
        <SelectPrimitive.Content
          ref={ref}
          position={position}
          className={cn(
            'relative z-50 max-h-96 min-w-[8rem] overflow-hidden rounded-md border border-gray-200 bg-white text-gray-900 shadow-md',
            position === 'popper' &&
              'data-[side=bottom]:translate-y-1 data-[side=left]:-translate-x-1 data-[side=right]:translate-x-1 data-[side=top]:-translate-y-1',
            className,
          )}
          {...rest}
        >
          <SelectScrollUpButton />
          <SelectPrimitive.Viewport
            className={cn(
              'p-1',
              position === 'popper' &&
                'h-[var(--radix-select-trigger-height)] w-full min-w-[var(--radix-select-trigger-width)]',
            )}
          >
            {children}
          </SelectPrimitive.Viewport>
          <SelectScrollDownButton />
        </SelectPrimitive.Content>
      </SelectPrimitive.Portal>
    );
  },
);

/** 옵션 묶음의 소제목. */
export const SelectLabel = forwardRef<
  ElementRef<typeof SelectPrimitive.Label>,
  ComponentPropsWithoutRef<typeof SelectPrimitive.Label>
>(function SelectLabel({ className, ...rest }, ref) {
  return (
    <SelectPrimitive.Label
      ref={ref}
      className={cn('px-2 py-1.5 text-caption font-medium text-gray-500', className)}
      {...rest}
    />
  );
});

export interface SelectItemProps extends ComponentPropsWithoutRef<typeof SelectPrimitive.Item> {}

/** 개별 옵션. 선택 시 체크 아이콘(ItemIndicator)이 노출된다. `value` 는 필수. */
export const SelectItem = forwardRef<ElementRef<typeof SelectPrimitive.Item>, SelectItemProps>(
  function SelectItem({ className, children, value, ...rest }, ref) {
    return (
      <SelectPrimitive.Item
        ref={ref}
        value={toInternalValue(value, false)}
        className={cn(
          'relative flex w-full cursor-pointer select-none items-center gap-2 rounded py-2 pl-8 pr-2 text-body text-gray-900 outline-none data-[disabled]:pointer-events-none data-[disabled]:cursor-not-allowed data-[disabled]:opacity-50 data-[highlighted]:bg-primary-50 data-[highlighted]:text-primary-900',
          className,
        )}
        {...rest}
      >
        <span className="absolute left-2 flex h-3.5 w-3.5 items-center justify-center">
          <SelectPrimitive.ItemIndicator>
            <Check className="h-3.5 w-3.5" strokeWidth={3} aria-hidden="true" />
          </SelectPrimitive.ItemIndicator>
        </span>
        <SelectPrimitive.ItemText>{children}</SelectPrimitive.ItemText>
      </SelectPrimitive.Item>
    );
  },
);

/** 옵션 그룹 사이 구분선. */
export const SelectSeparator = forwardRef<
  ElementRef<typeof SelectPrimitive.Separator>,
  ComponentPropsWithoutRef<typeof SelectPrimitive.Separator>
>(function SelectSeparator({ className, ...rest }, ref) {
  return (
    <SelectPrimitive.Separator
      ref={ref}
      className={cn('-mx-1 my-1 h-px bg-gray-100', className)}
      {...rest}
    />
  );
});

/** 패널 스크롤 가능 시 상단에 자동 노출되는 버튼. */
export const SelectScrollUpButton = forwardRef<
  ElementRef<typeof SelectPrimitive.ScrollUpButton>,
  ComponentPropsWithoutRef<typeof SelectPrimitive.ScrollUpButton>
>(function SelectScrollUpButton({ className, ...rest }, ref) {
  return (
    <SelectPrimitive.ScrollUpButton
      ref={ref}
      className={cn('flex cursor-default items-center justify-center py-1', className)}
      {...rest}
    >
      <ChevronUp className="h-4 w-4" aria-hidden="true" />
    </SelectPrimitive.ScrollUpButton>
  );
});

/** 패널 스크롤 가능 시 하단에 자동 노출되는 버튼. */
export const SelectScrollDownButton = forwardRef<
  ElementRef<typeof SelectPrimitive.ScrollDownButton>,
  ComponentPropsWithoutRef<typeof SelectPrimitive.ScrollDownButton>
>(function SelectScrollDownButton({ className, ...rest }, ref) {
  return (
    <SelectPrimitive.ScrollDownButton
      ref={ref}
      className={cn('flex cursor-default items-center justify-center py-1', className)}
      {...rest}
    >
      <ChevronDown className="h-4 w-4" aria-hidden="true" />
    </SelectPrimitive.ScrollDownButton>
  );
});
