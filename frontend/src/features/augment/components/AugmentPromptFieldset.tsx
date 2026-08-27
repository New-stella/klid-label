import { AlertTriangle } from 'lucide-react';

import { FieldCounter } from '@/components/common/FieldCounter';
import { KRDS_FOCUS } from '@/lib/focusRing';

import {
  invalidMtdtFieldLabels,
  type AugmentConditionDraft,
  type AugmentConditionErrors,
} from '../promptValidation';
import {
  AUGMENT_EVENT_TYPES,
  AUGMENT_EVENT_TYPE_LABEL,
  AUGMENT_FLOOD_SUBTYPES,
  AUGMENT_FLOOD_SUBTYPE_LABEL,
  AUGMENT_MTDT_FIELD_KEYS,
  AUGMENT_MTDT_FIELD_META,
  AUGMENT_PROMPT_MAX_LENGTH,
  type AugmentMtdtFieldKey,
} from '../types';

/** 사용자가 건드린 축 — 건드리기 전에는 오류를 띄우지 않는다. */
export interface AugmentConditionTouched {
  evntType?: boolean;
  mtdt: Partial<Record<AugmentMtdtFieldKey, boolean>>;
  prompt?: boolean;
}

export interface AugmentPromptFieldsetProps {
  /** 입력 초안 (이벤트 유형 · 침수 세부 유형 · 생성 조건 5항목 · 자유 지시문) */
  value: AugmentConditionDraft;
  /** 축별 오류 (validateAugmentConditions 결과) */
  errors: AugmentConditionErrors;
  touched: AugmentConditionTouched;
  onEventTypeChange: (value: string) => void;
  onFloodSubtypeChange: (value: string) => void;
  onMtdtChange: (key: AugmentMtdtFieldKey, value: string) => void;
  onPromptChange: (value: string) => void;
  disabled?: boolean;
}

const SELECT_CLASS = `rounded-md border bg-white px-2.5 py-1.5 text-body-md ${KRDS_FOCUS}`;

/**
 * 증강 요청의 **이벤트 유형 · 생성 조건 · 자유 지시문** 입력 블록 —
 * SCR-AUG-001 의 외부 위탁 증강(WINTER/NIGHT/RAIN) 전용. 해상도 변경은 이 블록을 받지 않는다.
 *
 * 계약(「생성형 AI API 연동명세서 v1.3」 §4.1):
 * - **이벤트 유형**은 필수이며 침수·산불 두 코드뿐이다. 영상의 관제 이벤트 코드에서 자동 변환하지
 *   않고 검수자가 직접 고른다 — 관제 코드와 외부 유형은 분류 축이 달라 자동 변환은 추정이 된다.
 * - **침수 세부 유형**은 침수를 고른 경우에만 노출하는 선택값이다. 계약에 산불 세부 코드가
 *   정의돼 있지 않아 산불과 함께 보내면 400 이므로, 산불이면 노출도 전송도 하지 않는다.
 * - **생성 조건 5항목**(시간대/계절/날씨/지형/심각도)은 **전부 필수**이며 값은 계약이 정한
 *   **허용 코드**다. 그래서 드롭다운으로만 고르게 한다 — 허용 코드 밖 값을 보낼 수단이 화면에 없다.
 * - **자유 지시문**은 1,000자 이내 선택 입력이다. 초과분을 잘라 보내지 않고 요청이 거부되므로
 *   글자수 카운터를 병기한다.
 *
 * ⚠ **구 동작 폐기(2026-08-27)**: 구 버전은 5항목을 **자유 텍스트 입력**으로 받았다. 근거였던
 * *"명세가 허용값 enum 을 정의하지 않으므로 select 로 사용자를 가두지 않는다"* 는 v1.3 에서
 * 사실이 아니게 됐다 — 다섯 축 전부의 코드가 닫혀 있고 코드 밖 값은 벤더에서 400 이다.
 *
 * ★**세 축은 서로를 유추하지 않는다**: 증강 종류(카드 선택) · 이벤트 유형 · 생성 조건은 값도
 * 조달처도 다르다. 종류가 생성 조건의 **기본값**을 채우는 것은 프리필이며(호출부가 소유),
 * 반대 방향(생성 조건·이벤트 유형 → 종류)은 만들지 않는다.
 *
 * 표시(사양 SCREEN-022):
 * - 자유 지시문에 글자수 카운터를 병기한다(공용 `FieldCounter`(UI-115) 재사용).
 *   상한은 `AUGMENT_PROMPT_MAX_LENGTH` 하나가 정한다 — 화면에 숫자를 박지 않는다.
 * - 코드값을 그대로 노출하지 않고 한국어 라벨을 보인다. 라벨↔코드 매핑의 단일 정의 지점은
 *   `AUGMENT_MTDT_FIELD_META` 이며 이 컴포넌트는 그것을 순회할 뿐 목록을 복제하지 않는다.
 *   전송값은 언제나 코드다.
 *
 * 접근성(WCAG 2.1 AA):
 * - 모든 입력에 `label htmlFor` 연결, 오류 시 `aria-invalid` + `aria-describedby` 로 사유 연결.
 * - 요약 안내만 `role="alert"` 로 알린다(필드마다 alert 를 두면 낭독이 중복된다).
 * - 카운터는 `aria-describedby` 에 **넣지 않는다** — FieldCounter 는 `aria-hidden` 이고,
 *   연결하면 타이핑마다 숫자가 낭독되며 오류 사유가 뒤로 밀린다.
 *
 * 개인정보: 선택·입력값은 **외부 생성형 AI 로 그대로 전송**되므로 경고를 상시 노출한다.
 *
 * [@design SCREEN-022] [@design API-060] [@design INT-008]
 */
export function AugmentPromptFieldset({
  value,
  errors,
  touched,
  onEventTypeChange,
  onFloodSubtypeChange,
  onMtdtChange,
  onPromptChange,
  disabled,
}: AugmentPromptFieldsetProps) {
  const isFlood = value.evntType === 'FLOOD';
  const eventTypeError = touched.evntType ? errors.evntType : undefined;
  const promptError = touched.prompt ? errors.prompt : undefined;
  const visibleErrorLabels = invalidMtdtFieldLabels(
    Object.fromEntries(
      AUGMENT_MTDT_FIELD_KEYS.filter((k) => touched.mtdt[k] && errors.mtdt[k]).map(
        (k) => [k, errors.mtdt[k] as string],
      ),
    ),
  );

  return (
    <div className="space-y-3" data-testid="augment-prompt-block">
      {/* 개인정보 안내 — 고른 값과 적은 내용이 외부로 나간다는 사실을 입력 지점에서 알린다. */}
      <p className="flex items-start gap-2 rounded-md border border-warning/30 bg-warning/10 px-3 py-2 text-caption text-warning-700">
        {/* 경고 박스의 아이콘은 앱 전체에서 AlertTriangle 하나로 통일한다
            (위험 구역·프리셋 경고와 동일). AlertCircle 은 '입력 오류' 축이라 섞지 않는다. */}
        <AlertTriangle size={14} className="mt-0.5 shrink-0" aria-hidden />
        <span>
          개인식별정보(이름·차량번호·연락처 등)를 입력하지 마세요 — 선택·입력한 내용은
          외부 생성형 AI 서비스로 그대로 전송됩니다.
        </span>
      </p>

      {/* [1] 이벤트 유형 축 — 생성 조건과 다른 축이라 별도 fieldset 으로 나눈다. */}
      <fieldset
        className="space-y-3 rounded-lg border border-gray-200 bg-white p-4 shadow-sm"
        data-testid="augment-event-type-block"
        disabled={disabled}
      >
        <legend className="px-1 text-label font-medium text-gray-500">
          이벤트 유형 (필수)
        </legend>

        <div className="grid grid-cols-1 gap-3 md:grid-cols-2">
          <div className="flex flex-col gap-1" data-testid="aug-evnt-type-field">
            <label
              htmlFor="aug-evnt-type"
              className="text-label font-medium text-gray-600"
            >
              이벤트 유형
              <span className="ml-0.5 text-danger" aria-hidden>
                *
              </span>
              <span className="sr-only">(필수)</span>
            </label>
            <select
              id="aug-evnt-type"
              value={value.evntType}
              onChange={(e) => onEventTypeChange(e.target.value)}
              required
              aria-required="true"
              aria-invalid={eventTypeError ? true : undefined}
              aria-describedby={
                eventTypeError ? 'aug-evnt-type-error' : 'aug-evnt-type-hint'
              }
              className={`${SELECT_CLASS} ${
                eventTypeError ? 'border-danger' : 'border-gray-300'
              }`}
            >
              <option value="">선택하세요</option>
              {AUGMENT_EVENT_TYPES.map((code) => (
                <option key={code} value={code}>
                  {AUGMENT_EVENT_TYPE_LABEL[code]}
                </option>
              ))}
            </select>
            {eventTypeError ? (
              <p id="aug-evnt-type-error" className="text-caption text-danger">
                {eventTypeError}
              </p>
            ) : (
              <p id="aug-evnt-type-hint" className="text-caption text-gray-400">
                영상의 관제 이벤트 코드와 별개로 직접 고릅니다.
              </p>
            )}
          </div>

          {/* [2] 침수 세부 유형 — 침수일 때만 노출한다. 산불에는 계약상 세부 코드가 없다. */}
          {isFlood && (
            <div className="flex flex-col gap-1" data-testid="aug-evnt-subtype-field">
              <label
                htmlFor="aug-evnt-subtype"
                className="text-label font-medium text-gray-600"
              >
                침수 세부 유형
                <span className="ml-1 text-caption font-normal text-gray-400">
                  (선택)
                </span>
              </label>
              <select
                id="aug-evnt-subtype"
                value={value.evntSubtype}
                onChange={(e) => onFloodSubtypeChange(e.target.value)}
                aria-describedby="aug-evnt-subtype-hint"
                className={`${SELECT_CLASS} border-gray-300`}
              >
                <option value="">선택 안 함</option>
                {AUGMENT_FLOOD_SUBTYPES.map((code) => (
                  <option key={code} value={code}>
                    {AUGMENT_FLOOD_SUBTYPE_LABEL[code]}
                  </option>
                ))}
              </select>
              <p id="aug-evnt-subtype-hint" className="text-caption text-gray-400">
                침수를 고른 경우에만 함께 보낼 수 있습니다.
              </p>
            </div>
          )}
        </div>
      </fieldset>

      {/* [3] 생성 조건 축 — 다섯 항목 전부 필수, 값은 허용 코드다. */}
      <fieldset
        className="space-y-3 rounded-lg border border-gray-200 bg-white p-4 shadow-sm"
        data-testid="augment-mtdt-block"
        disabled={disabled}
      >
        <legend className="px-1 text-label font-medium text-gray-500">
          생성 조건 ({AUGMENT_MTDT_FIELD_KEYS.length}개 항목 모두 필수)
        </legend>

        <div className="grid grid-cols-1 gap-3 md:grid-cols-2 xl:grid-cols-3">
          {AUGMENT_MTDT_FIELD_KEYS.map((key) => {
            const meta = AUGMENT_MTDT_FIELD_META[key];
            const error = touched.mtdt[key] ? errors.mtdt[key] : undefined;
            const selectId = `aug-mtdt-${key}`;
            return (
              <div
                key={key}
                data-testid={`aug-mtdt-${key}-field`}
                className="flex flex-col gap-1"
              >
                <label
                  htmlFor={selectId}
                  className="text-label font-medium text-gray-600"
                >
                  {meta.label}
                  <span className="ml-0.5 text-danger" aria-hidden>
                    *
                  </span>
                  <span className="sr-only">(필수)</span>
                </label>
                <select
                  id={selectId}
                  value={value.mtdt[key]}
                  onChange={(e) => onMtdtChange(key, e.target.value)}
                  required
                  aria-required="true"
                  aria-invalid={error ? true : undefined}
                  aria-describedby={error ? `${selectId}-error` : undefined}
                  className={`${SELECT_CLASS} ${
                    error ? 'border-danger' : 'border-gray-300'
                  }`}
                >
                  <option value="">선택하세요</option>
                  {(meta.codes as readonly string[]).map((code) => (
                    <option key={code} value={code}>
                      {(meta.codeLabel as Record<string, string>)[code]}
                    </option>
                  ))}
                </select>
                {error && (
                  <p id={`${selectId}-error`} className="text-caption text-danger">
                    {error}
                  </p>
                )}
              </div>
            );
          })}
        </div>

        {visibleErrorLabels.length > 0 && (
          <p role="alert" className="text-caption text-danger">
            선택을 확인하세요: {visibleErrorLabels.join(', ')} —{' '}
            {AUGMENT_MTDT_FIELD_KEYS.length}개 항목을 모두 골라야 요청할 수 있습니다.
          </p>
        )}

        <p className="text-caption text-gray-400">
          자유 입력이 아니라 연동 계약이 정한 보기 중에서 고릅니다. 증강
          종류(겨울/야간/우천)는 위에서 고른 값이 그대로 사용되며 생성 조건에서 파생하지
          않습니다.
        </p>
      </fieldset>

      {/* [4] 자유 지시문 — 선택. 생성 조건과 충돌하면 외부에서 생성 조건이 우선한다. */}
      <fieldset
        className="space-y-2 rounded-lg border border-gray-200 bg-white p-4 shadow-sm"
        data-testid="augment-free-prompt-block"
        disabled={disabled}
      >
        <legend className="px-1 text-label font-medium text-gray-500">
          자유 지시문 (선택)
        </legend>
        <div className="flex items-baseline justify-between gap-2">
          <label
            htmlFor="aug-free-prompt"
            className="text-label font-medium text-gray-600"
          >
            자유 지시문
          </label>
          <FieldCounter
            current={value.prompt.length}
            max={AUGMENT_PROMPT_MAX_LENGTH}
          />
        </div>
        <textarea
          id="aug-free-prompt"
          rows={3}
          value={value.prompt}
          onChange={(e) => onPromptChange(e.target.value)}
          maxLength={AUGMENT_PROMPT_MAX_LENGTH}
          placeholder="예: 원본 카메라 시점과 도로 구조를 유지해줘."
          aria-invalid={promptError ? true : undefined}
          aria-describedby={
            promptError ? 'aug-free-prompt-error' : 'aug-free-prompt-hint'
          }
          className={`w-full rounded-md border bg-white px-2.5 py-1.5 text-body-md ${KRDS_FOCUS} ${
            promptError ? 'border-danger' : 'border-gray-300'
          }`}
        />
        {promptError ? (
          <p id="aug-free-prompt-error" role="alert" className="text-caption text-danger">
            {promptError}
          </p>
        ) : (
          <p id="aug-free-prompt-hint" className="text-caption text-gray-400">
            생성 조건과 충돌하는 표현은 생성 조건이 우선하며, 무시된 표현은 결과 화면에
            경고로 표시됩니다.
          </p>
        )}
      </fieldset>
    </div>
  );
}
