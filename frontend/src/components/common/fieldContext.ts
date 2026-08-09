import { createContext, useContext, useEffect, useId, type AriaAttributes } from 'react';

/**
 * Field 조립부(UI-099)와 입력 프리미티브 사이의 접근성 배선 통로.
 *
 * 공통 입력 컴포넌트는 라벨·설명·오류 **문구를 렌더하지 않는다**(조합형 — 문구 배치는
 * `Field`/`FieldLabel`/`FieldDescription`/`FieldError` 가 전담). 다만 `htmlFor`↔`id`,
 * `aria-describedby`, `aria-invalid` 같은 **프로그램적 연결까지 호출부에 떠넘기면** 조합형
 * 전환이 곧 접근성 후퇴가 된다(호출부 90여 곳이 매번 id 를 손으로 맞춰야 하고, 한 곳만
 * 빠뜨려도 조용히 끊긴다). 그래서 문구 렌더 책임만 밖으로 내보내고 **연결은 이 컨텍스트가
 * 자동으로 잇는다**.
 *
 * 호출부가 `id`/`aria-describedby`/`aria-invalid` 를 명시하면 그 값이 항상 우선한다.
 */
export interface FieldContextValue {
  /** 입력 프리미티브의 `id` 이자 `FieldLabel` 의 `htmlFor` 대상. */
  controlId: string;
  /** `FieldLabel` 요소의 id — 그룹 컨트롤(radiogroup 등)이 `aria-labelledby` 로 참조한다. */
  labelId: string;
  /** `FieldDescription` 요소의 id. */
  descriptionId: string;
  /** `FieldError` 요소의 id. */
  errorId: string;
  hasLabel: boolean;
  hasDescription: boolean;
  hasError: boolean;
  /** 하위 조립 요소가 자신의 렌더 여부를 알린다(없는 id 를 참조하지 않기 위함). */
  register: (part: FieldPart, present: boolean) => void;
  /**
   * 입력 프리미티브가 **자기 `id` 를 호출부에서 명시로 받았을 때** 그 값을 알린다.
   *
   * 없으면 `FieldLabel` 의 기본 `htmlFor`(컨텍스트 id)와 컨트롤의 실제 `id` 가 어긋나 라벨
   * 연결이 조용히 끊긴다 — 조합형 전환에서 가장 흔한 접근성 회귀다.
   */
  registerControlId: (id: string | undefined) => void;
}

export type FieldPart = 'label' | 'description' | 'error';

export const FieldContext = createContext<FieldContextValue | null>(null);

export function useFieldContext(): FieldContextValue | null {
  return useContext(FieldContext);
}

export interface FieldControlAria {
  id?: string;
  'aria-describedby'?: AriaAttributes['aria-describedby'];
  'aria-invalid'?: AriaAttributes['aria-invalid'];
}

export interface ResolvedFieldControl {
  id: string;
  describedBy: string | undefined;
  invalid: AriaAttributes['aria-invalid'];
  /** 오류 상태 여부 — 보더·링을 destructive 로 전환할지 판단하는 시각 축. */
  hasError: boolean;
}

/**
 * 입력 프리미티브가 Field 조립부와 자동으로 이어지도록 접근성 속성을 해석한다.
 *
 * 우선순위는 **호출부 명시값 > Field 컨텍스트 > 자동 생성 id** 다.
 * 설명·오류가 실제로 렌더되지 않으면 `aria-describedby` 를 붙이지 않는다(존재하지 않는 id
 * 참조 방지). 오류와 설명이 동시에 있으면 오류를 가리킨다 — 조합형 전환 이전 프리미티브가
 * 내장으로 갖고 있던 `errorId ?? hintId` 규칙을 그대로 옮긴 것이다.
 */
export function resolveFieldControl(
  ctx: FieldContextValue | null,
  explicit: FieldControlAria,
  fallbackId: string,
): ResolvedFieldControl {
  const id = explicit.id ?? ctx?.controlId ?? fallbackId;

  const ctxDescribedBy = ctx?.hasError
    ? ctx.errorId
    : ctx?.hasDescription
      ? ctx.descriptionId
      : undefined;
  const describedBy = explicit['aria-describedby'] ?? ctxDescribedBy;

  const hasError = ctx?.hasError ?? false;
  const invalid = explicit['aria-invalid'] ?? (hasError ? true : undefined);

  return { id, describedBy, invalid, hasError: hasError || invalid === true || invalid === 'true' };
}

/** 입력 프리미티브 전용 훅 — {@link resolveFieldControl} 을 컨텍스트·자동 id 와 함께 묶는다. */
export function useFieldControl(explicit: FieldControlAria): ResolvedFieldControl {
  const autoId = useId();
  const ctx = useFieldContext();
  const explicitId = explicit.id;
  const registerControlId = ctx?.registerControlId;

  useEffect(() => {
    if (!registerControlId || !explicitId) return;
    registerControlId(explicitId);
    return () => registerControlId(undefined);
  }, [registerControlId, explicitId]);

  return resolveFieldControl(ctx, explicit, autoId);
}
