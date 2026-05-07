import { type ReactElement, cloneElement } from 'react';
import {
  Controller,
  type Control,
  type FieldPath,
  type FieldValues,
} from 'react-hook-form';

export interface FormFieldProps<T extends FieldValues> {
  name: FieldPath<T>;
  control: Control<T>;
  defaultValue?: unknown;
  render?: (props: {
    value: unknown;
    onChange: (...args: unknown[]) => void;
    onBlur: () => void;
    error?: string;
    name: string;
  }) => ReactElement;
  /**
   * 단일 children 사용 시 자동 바인딩 — value/onChange/onBlur/name/error props가 주입됨.
   */
  children?: ReactElement;
}

/**
 * react-hook-form Controller 래퍼.
 * - render prop 또는 children prop 둘 중 하나 사용.
 * - children 사용 시 컴포넌트는 value/onChange/onBlur/name/error props를 받도록 설계해야 함.
 */
export function FormField<T extends FieldValues>({
  name,
  control,
  defaultValue,
  render,
  children,
}: FormFieldProps<T>) {
  return (
    <Controller
      name={name}
      control={control}
      defaultValue={defaultValue as never}
      render={({ field, fieldState }) => {
        if (render) {
          return render({
            value: field.value,
            onChange: field.onChange,
            onBlur: field.onBlur,
            error: fieldState.error?.message,
            name: field.name,
          });
        }
        if (children) {
          return cloneElement(children, {
            ...field,
            error: fieldState.error?.message,
          } as Record<string, unknown>);
        }
        return <></>;
      }}
    />
  );
}
