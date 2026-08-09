import {
  useCallback,
  useEffect,
  useId,
  useMemo,
  useState,
  type HTMLAttributes,
  type LabelHTMLAttributes,
  type ReactNode,
} from 'react';
import { AlertCircle } from 'lucide-react';

import { cn } from '@/lib/cn';

import {
  FieldContext,
  useFieldContext,
  type FieldContextValue,
  type FieldPart,
} from './fieldContext';

/**
 * 입력 프리미티브와 라벨·설명·오류 문구를 **바깥에서 조립하는** 표시 구조 래퍼 (UI-099).
 *
 * `Input`/`Select`/`Textarea`/`Checkbox`/`RadioGroup`/`DatePicker`/`FileInput` 은 더 이상
 * `label`/`hint`/`error` prop 을 갖지 않는다 — 문구의 위치·존재는 이 조립부가 전담한다.
 *
 * 접근성 배선(`htmlFor`↔`id`, `aria-describedby`, `aria-invalid`, 그룹 컨트롤의
 * `aria-labelledby`)은 `fieldContext` 를 통해 자동으로 이어진다. 호출부가 같은 값을 명시하면
 * 명시값이 우선한다.
 */
export interface FieldProps extends Omit<HTMLAttributes<HTMLDivElement>, 'title'> {
  /**
   * vertical=라벨 위·컨트롤 아래(기본) / horizontal=라벨과 컨트롤을 한 줄에(체크박스·라디오류) /
   * responsive=좁은 화면은 세로, **md(768px) 이상**에서 가로.
   *
   * ⚠ `sm:` 이 아니다 — 이 저장소의 Tailwind `theme.screens` 는 기본 브레이크포인트를 **대체**해
   * `md`·`xl` 두 개만 정의한다. 따라서 `sm:`·`lg:`·`2xl:` 은 한 번도 적용되지 않는 죽은
   * 접두사이며, 반응형 분기를 새로 쓸 때도 `md:`/`xl:` 만 쓴다.
   */
  orientation?: 'vertical' | 'horizontal' | 'responsive';
  /** 지정 시 그룹 전체 텍스트가 destructive 색으로 전환된다. */
  'data-invalid'?: boolean;
}

export function Field({
  orientation = 'vertical',
  className,
  children,
  'data-invalid': dataInvalid,
  ...rest
}: FieldProps) {
  const base = useId();
  const [present, setPresent] = useState({
    label: false,
    description: false,
    error: false,
  });

  const [controlIdOverride, setControlIdOverride] = useState<string | undefined>(undefined);

  const register = useCallback((part: FieldPart, isPresent: boolean) => {
    setPresent((prev) => (prev[part] === isPresent ? prev : { ...prev, [part]: isPresent }));
  }, []);

  const registerControlId = useCallback((id: string | undefined) => {
    setControlIdOverride((prev) => (prev === id ? prev : id));
  }, []);

  const value = useMemo<FieldContextValue>(
    () => ({
      controlId: controlIdOverride ?? `${base}-control`,
      labelId: `${base}-label`,
      descriptionId: `${base}-description`,
      errorId: `${base}-error`,
      hasLabel: present.label,
      hasDescription: present.description,
      hasError: present.error,
      register,
      registerControlId,
    }),
    [
      base,
      controlIdOverride,
      present.label,
      present.description,
      present.error,
      register,
      registerControlId,
    ],
  );

  return (
    <FieldContext.Provider value={value}>
      <div
        data-slot="field"
        data-orientation={orientation}
        data-invalid={dataInvalid || present.error ? true : undefined}
        className={cn(
          'group/field flex',
          orientation === 'vertical' && 'flex-col gap-1',
          // 체크박스·라디오처럼 컨트롤이 라벨 앞에 오는 배치. 44px 터치 타깃은 FieldLabel 이 보장한다.
          orientation === 'horizontal' && 'flex-row items-center gap-2',
          orientation === 'responsive' && 'flex-col gap-1 md:flex-row md:items-center md:gap-2',
          // ⚠ 2026-08-08: 공용 컴포넌트라 실제 렌더 배경(카드 흰색/페이지 gray-50 등)을 호출부마다
          // 보장할 수 없다 — DEFAULT 는 gray-50 배경에서 AA(4.5:1)에 미달(4.36:1)하므로 -700 사용.
          'data-[invalid=true]:text-danger-700',
          className,
        )}
        {...rest}
      >
        {children}
      </div>
    </FieldContext.Provider>
  );
}

/** 여러 Field 를 세로로 묶는 상위 컨테이너. */
export function FieldGroup({ className, ...rest }: HTMLAttributes<HTMLDivElement>) {
  return <div data-slot="field-group" className={cn('flex flex-col gap-4', className)} {...rest} />;
}

/**
 * 가로 배치에서 라벨+설명을 세로로 묶어 컨트롤 옆에 두는 하위 컨테이너.
 */
export function FieldContent({ className, ...rest }: HTMLAttributes<HTMLDivElement>) {
  return (
    <div data-slot="field-content" className={cn('flex flex-col gap-0.5', className)} {...rest} />
  );
}

export interface FieldLabelProps extends LabelHTMLAttributes<HTMLLabelElement> {
  /** 필수 입력 표시(*)를 라벨 뒤에 붙인다. 스크린리더에는 '필수'로 읽힌다. */
  required?: boolean;
}

/** 대상 입력과 `htmlFor` 로 연결되는 라벨. `htmlFor` 미지정 시 Field 컨텍스트의 controlId 로 잇는다. */
export function FieldLabel({
  className,
  htmlFor,
  id,
  required,
  children,
  ...rest
}: FieldLabelProps) {
  const field = useFieldContext();
  const { register } = field ?? {};

  useEffect(() => {
    register?.('label', true);
    return () => register?.('label', false);
  }, [register]);

  return (
    <label
      data-slot="field-label"
      id={id ?? field?.labelId}
      htmlFor={htmlFor ?? field?.controlId}
      className={cn(
        'text-body font-medium text-gray-700 group-data-[orientation=horizontal]/field:flex group-data-[orientation=horizontal]/field:min-h-11 group-data-[orientation=horizontal]/field:cursor-pointer group-data-[orientation=horizontal]/field:select-none group-data-[orientation=horizontal]/field:items-center',
        className,
      )}
      {...rest}
    >
      {children}
      {required && (
        <>
          <span aria-hidden="true" className="ml-0.5 text-danger-700">
            *
          </span>
          <span className="sr-only"> (필수)</span>
        </>
      )}
    </label>
  );
}

/**
 * 특정 입력 하나와 연결되지 않는 정적 섹션 라벨. `FieldLabel` 과 달리 `htmlFor` 대상이 없다.
 */
export function FieldTitle({ className, ...rest }: HTMLAttributes<HTMLDivElement>) {
  return (
    <div
      data-slot="field-title"
      className={cn('text-body font-medium text-gray-700', className)}
      {...rest}
    />
  );
}

/** 라벨 아래·오류 위에 오는 보조 설명 문단. */
export function FieldDescription({ className, id, ...rest }: HTMLAttributes<HTMLParagraphElement>) {
  const field = useFieldContext();
  const { register } = field ?? {};

  useEffect(() => {
    register?.('description', true);
    return () => register?.('description', false);
  }, [register]);

  return (
    <span
      data-slot="field-description"
      id={id ?? field?.descriptionId}
      className={cn('text-sub text-gray-500', className)}
      {...rest}
    />
  );
}

export interface FieldErrorProps extends Omit<HTMLAttributes<HTMLElement>, 'children'> {
  /** 메시지 객체 배열 — react-hook-form 의 오류 객체를 그대로 넘길 수 있다. */
  errors?: Array<{ message?: string } | undefined | null>;
  children?: ReactNode;
}

/**
 * 오류 문구. `role="alert"` 로 즉시 안내되며, 내용이 없으면 **아무것도 렌더하지 않는다**
 * (빈 alert 로 DOM 에 남지 않음). 서로 다른 메시지가 2건 이상이면 불릿 목록으로 렌더한다.
 */
export function FieldError({ className, id, errors, children, ...rest }: FieldErrorProps) {
  const field = useFieldContext();
  const { register } = field ?? {};

  const messages = useMemo(() => {
    if (children != null && children !== false && children !== '') return null;
    const list = (errors ?? [])
      .map((e) => e?.message)
      .filter((m): m is string => typeof m === 'string' && m.length > 0);
    return Array.from(new Set(list));
  }, [children, errors]);

  const hasContent =
    (children != null && children !== false && children !== '') ||
    (messages != null && messages.length > 0);

  useEffect(() => {
    register?.('error', hasContent);
    return () => register?.('error', false);
  }, [register, hasContent]);

  if (!hasContent) return null;

  const body =
    messages && messages.length > 1 ? (
      <ul className="ml-4 list-disc">
        {messages.map((m) => (
          <li key={m}>{m}</li>
        ))}
      </ul>
    ) : messages && messages.length === 1 ? (
      messages[0]
    ) : (
      children
    );

  return (
    <span
      data-slot="field-error"
      id={id ?? field?.errorId}
      role="alert"
      className={cn('flex items-center gap-1 text-sub text-danger-700', className)}
      {...rest}
    >
      <AlertCircle className="h-3.5 w-3.5 shrink-0" aria-hidden="true" />
      {body}
    </span>
  );
}

/** 네이티브 fieldset 래퍼 — 여러 Field 를 의미상 한 묶음으로 나타낼 때 FieldLegend 와 함께 쓴다. */
export function FieldSet({ className, ...rest }: HTMLAttributes<HTMLFieldSetElement>) {
  return <fieldset data-slot="field-set" className={cn('flex flex-col gap-3', className)} {...rest} />;
}

export interface FieldLegendProps extends HTMLAttributes<HTMLLegendElement> {
  variant?: 'legend' | 'label';
}

/** FieldSet 의 제목. */
export function FieldLegend({ className, variant = 'legend', ...rest }: FieldLegendProps) {
  return (
    <legend
      data-slot="field-legend"
      data-variant={variant}
      className={cn(
        'font-medium text-gray-800',
        variant === 'legend' ? 'text-section-title' : 'text-body',
        className,
      )}
      {...rest}
    />
  );
}

/** Field 사이 구분선. children 지정 시 구분선 중앙에 텍스트를 병기한다. */
export function FieldSeparator({ className, children, ...rest }: HTMLAttributes<HTMLDivElement>) {
  return (
    <div
      data-slot="field-separator"
      role="separator"
      className={cn('flex items-center gap-2 text-sub text-gray-400', className)}
      {...rest}
    >
      <span aria-hidden="true" className="h-px flex-1 bg-gray-200" />
      {children != null && children !== '' && <span>{children}</span>}
      <span aria-hidden="true" className="h-px flex-1 bg-gray-200" />
    </div>
  );
}
