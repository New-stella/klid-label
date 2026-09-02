import { AlertTriangle } from 'lucide-react';

import { FieldCounter } from '@/components/common/FieldCounter';
import { cn } from '@/lib/cn';
import { KRDS_FOCUS } from '@/lib/focusRing';

import {
  invalidMtdtFieldLabels,
  type AugmentConditionDraft,
  type AugmentConditionErrors,
} from '../promptValidation';
import {
  AUGMENT_CONDITION_PRESET_IDS,
  AUGMENT_CONDITION_PRESET_LABEL,
  AUGMENT_CONDITION_PRESET_VALUES,
  AUGMENT_MTDT_FIELD_KEYS,
  AUGMENT_MTDT_FIELD_META,
  AUGMENT_PROMPT_MAX_LENGTH,
  type AugmentConditionPresetId,
  type AugmentMtdtFieldKey,
} from '../types';

/** 사용자가 건드린 축 — 건드리기 전에는 오류를 띄우지 않는다. */
export interface AugmentConditionTouched {
  mtdt: Partial<Record<AugmentMtdtFieldKey, boolean>>;
  prompt?: boolean;
}

export interface AugmentPromptFieldsetProps {
  /** 입력 초안 (생성 조건 5항목 · 자유 지시문) */
  value: AugmentConditionDraft;
  /** 축별 오류 (validateAugmentConditions 결과) */
  errors: AugmentConditionErrors;
  touched: AugmentConditionTouched;
  /** 현재 눌러 둔 생성 조건 프리셋. 없으면 `null`. */
  selectedPreset: AugmentConditionPresetId | null;
  /** 프리셋 버튼 클릭 — 같은 프리셋을 다시 누르면 해제(`null`)를 전달한다. */
  onSelectPreset: (preset: AugmentConditionPresetId | null) => void;
  /**
   * 프리셋이 채워 둔(=사용자가 아직 손대지 않은) 항목 — 그 자리에 안내 문구를 띄운다.
   * 어느 값이 시스템이 채운 것인지 보이지 않으면 검수자가 고쳐도 되는 값인지 알 수 없다.
   */
  presetFilledFields: Partial<Record<AugmentMtdtFieldKey, boolean>>;
  onMtdtChange: (key: AugmentMtdtFieldKey, value: string) => void;
  onPromptChange: (value: string) => void;
  disabled?: boolean;
}

const SELECT_CLASS = `rounded-md border bg-white px-2.5 py-1.5 text-body-md ${KRDS_FOCUS}`;

/**
 * 증강 요청의 **생성 조건 · 자유 지시문** 입력 블록 — SCR-AUG-001 의 외부 위탁 증강 전용.
 * 해상도 변경은 이 블록을 받지 않는다.
 *
 * 계약(「생성형 AI API 연동명세서 v1.3」 §4.1):
 * - **생성 조건 5항목**(시간대/계절/날씨/지형/심각도)은 **전부 필수**이며 값은 계약이 정한
 *   **허용 코드**다. 그래서 드롭다운으로만 고르게 한다 — 허용 코드 밖 값을 보낼 수단이 화면에 없다.
 * - **자유 지시문**은 1,000자 이내 선택 입력이다. 초과분을 잘라 보내지 않고 요청이 거부되므로
 *   글자수 카운터를 병기한다.
 *
 * ★**생성 조건 프리셋**(겨울·야간·우천): 자주 쓰는 조합에 이름을 붙인 것일 뿐 **증강 종류에
 * 관여하지 않는다**(ADR-059 — 종류는 프리셋과 무관하게 `AUGMENT` 하나다). 프리셋이 채운 값은
 * 고칠 수 있고, **이미 사용자가 손댄 항목은 프리셋을 바꿔도 덮어쓰지 않는다** — 그 판정
 * (무엇을 손댔는가)은 호출부가 소유하고 이 컴포넌트는 결과(`presetFilledFields`)만 표시한다.
 *
 * ⚠ **구 동작 폐기(2026-09-02 · ADR-059)**: 구 버전은 이 블록 맨 위에 **이벤트 유형**(침수/산불,
 * 필수)과 **침수 세부 유형** select 를 두었다. 그 값은 벤더가 배경 이미지에 무슨 장면을 만들어
 * 넣을지 정하는 축인데 우리 증강은 이미 이벤트가 담긴 프레임을 변환할 뿐이라 고를 자리가 없었다.
 * 요청 본문에서 사라졌고 서버가 중립값을 고정 송신한다 — **되살리지 말 것.**
 *
 * ⚠ **구 동작 폐기(2026-08-27)**: 구 버전은 5항목을 **자유 텍스트 입력**으로 받았다. 근거였던
 * *"명세가 허용값 enum 을 정의하지 않으므로 select 로 사용자를 가두지 않는다"* 는 v1.3 에서
 * 사실이 아니게 됐다 — 다섯 축 전부의 코드가 닫혀 있고 코드 밖 값은 벤더에서 400 이다.
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
 * - 프리셋은 `role="group"` + `aria-pressed` **토글 버튼**이다. `radiogroup` 을 쓰지 않는 이유는
 *   Step 1 의 처리 종류 카드가 이미 radiogroup 이라, 한 화면에 라디오그룹이 둘 겹치면 화살표
 *   탐색 축이 모호해지기 때문이다(사양 SCREEN-022).
 * - 카운터는 `aria-describedby` 에 **넣지 않는다** — FieldCounter 는 `aria-hidden` 이고,
 *   연결하면 타이핑마다 숫자가 낭독되며 오류 사유가 뒤로 밀린다.
 *
 * 개인정보: 선택·입력값은 **외부 생성형 AI 로 그대로 전송**되므로 경고를 상시 노출한다.
 *
 * [@design SCREEN-022] [@design API-060] [@design INT-008] [@design ADR-059]
 */
export function AugmentPromptFieldset({
  value,
  errors,
  touched,
  selectedPreset,
  onSelectPreset,
  presetFilledFields,
  onMtdtChange,
  onPromptChange,
  disabled,
}: AugmentPromptFieldsetProps) {
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

      {/* [1] 생성 조건 축 — 다섯 항목 전부 필수, 값은 허용 코드다. */}
      <fieldset
        className="space-y-3 rounded-lg border border-gray-200 bg-white p-4 shadow-sm"
        data-testid="augment-mtdt-block"
        disabled={disabled}
      >
        <legend className="px-1 text-label font-medium text-gray-500">
          생성 조건 ({AUGMENT_MTDT_FIELD_KEYS.length}개 항목 모두 필수)
        </legend>

        {/* 생성 조건 프리셋 — 증강 종류가 아니라 조건의 초기값일 뿐이다. */}
        <div className="flex flex-col gap-1" data-testid="augment-preset-field">
          <span
            id="augment-preset-label"
            className="text-label font-medium text-gray-600"
          >
            생성 조건 프리셋
          </span>
          <div
            role="group"
            aria-labelledby="augment-preset-label"
            className="flex flex-wrap gap-2"
            data-testid="augment-preset-group"
          >
            {AUGMENT_CONDITION_PRESET_IDS.map((preset) => {
              const pressed = selectedPreset === preset;
              return (
                <button
                  key={preset}
                  type="button"
                  aria-pressed={pressed}
                  data-testid={`augment-preset-${preset}`}
                  onClick={() => onSelectPreset(pressed ? null : preset)}
                  className={cn(
                    'rounded-md border px-3 py-1 text-label font-medium transition-colors',
                    KRDS_FOCUS,
                    'disabled:cursor-not-allowed disabled:opacity-50',
                    pressed
                      ? 'border-primary-500 bg-primary-600 text-white'
                      : 'border-gray-300 bg-white text-gray-700 hover:border-primary-300',
                  )}
                >
                  {AUGMENT_CONDITION_PRESET_LABEL[preset]}
                </button>
              );
            })}
          </div>
          <p className="text-caption text-gray-400">
            프리셋을 고르면 생성 조건 다섯 항목에 그에 맞는 값이 채워집니다. 겨울은 계절과
            날씨, 야간은 시간대, 우천은 날씨. 채워진 값은 고칠 수 있고, 이미 입력한 항목은
            프리셋을 바꿔도 덮어쓰지 않습니다.
          </p>
        </div>

        <div className="grid grid-cols-1 gap-3 md:grid-cols-2 xl:grid-cols-3">
          {AUGMENT_MTDT_FIELD_KEYS.map((key) => {
            const meta = AUGMENT_MTDT_FIELD_META[key];
            const error = touched.mtdt[key] ? errors.mtdt[key] : undefined;
            const selectId = `aug-mtdt-${key}`;
            // 프리셋이 실제로 이 항목을 채웠는가 — 채우지 않는 축에는 안내를 띄우지 않는다.
            const filledByPreset =
              selectedPreset !== null &&
              presetFilledFields[key] === true &&
              AUGMENT_CONDITION_PRESET_VALUES[selectedPreset][key] !== undefined;
            const describedBy = error
              ? `${selectId}-error`
              : filledByPreset
                ? `${selectId}-preset-hint`
                : undefined;
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
                  aria-describedby={describedBy}
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
                {error ? (
                  <p id={`${selectId}-error`} className="text-caption text-danger">
                    {error}
                  </p>
                ) : (
                  filledByPreset && (
                    <p
                      id={`${selectId}-preset-hint`}
                      data-testid={`aug-mtdt-${key}-preset-hint`}
                      className="text-caption text-gray-400"
                    >
                      {AUGMENT_CONDITION_PRESET_LABEL[selectedPreset]} 프리셋으로 채워
                      뒀습니다. 고칠 수 있습니다.
                    </p>
                  )
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
          자유 입력이 아니라 연동 계약이 정한 보기 중에서 고릅니다. 무엇으로 바꿀지는 이
          조건이 정하며, 증강 종류는 이 값에서 파생하지 않습니다.
        </p>
      </fieldset>

      {/* [2] 자유 지시문 — 선택. 생성 조건과 충돌하면 외부에서 생성 조건이 우선한다. */}
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
