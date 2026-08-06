import { Plus, X } from 'lucide-react';

import { Button } from '@/components/common/Button';
import { Input } from '@/components/common/Input';
import { Modal } from '@/components/common/Modal';
import { Select, type SelectOption } from '@/components/common/Select';
import {
  LABEL_ATTR_INPUT_TYPES,
  type LabelAttrDef,
  type LabelAttrInputType,
  type LabelAttrUpsert,
} from '@/features/label/api/labelAttr';
import { KRDS_FOCUS } from '@/lib/focusRing';

/**
 * 라벨 속성 정의 생성/수정 폼 모달 — LabelAttrDefPanel 에서 추출(component.md 400줄 분리).
 *
 * 상태(폼 값/에러/제출)는 부모(LabelAttrDefPanel)가 소유하고, 이 컴포넌트는 표현 + 입력 위임만 담당.
 * 폼/표기 헬퍼(파싱·검증·변환)는 목록 표기와 공유하므로 여기서 export 한다.
 *
 * 보안: 선택 항목 값은 React 기본 escape 로 렌더(XSS 방어), valuesJson 파싱은 try/catch 안전 폴백.
 */

/** 선택 항목(valuesJson)을 갖는 입력 유형. */
const CHOICE_TYPES: readonly LabelAttrInputType[] = ['SELECT', 'CHECKBOX', 'RADIO'];

/** 작업 중 값 수정 가능 여부 옵션 — 값·순서 고정. */
const MUTABLE_OPTIONS: SelectOption[] = [
  { value: 'Y', label: '가능' },
  { value: 'N', label: '고정' },
];

export function hasChoices(type: LabelAttrInputType): boolean {
  return CHOICE_TYPES.includes(type);
}

/** 입력 형식 코드 → 사용자 표기. 목록/폼에서 공유. */
export const INPUT_TYPE_LABEL: Record<LabelAttrInputType, string> = {
  SELECT: '선택(드롭다운)',
  CHECKBOX: '체크박스(다중)',
  RADIO: '라디오(단일)',
  NUMBER: '숫자',
  TEXT: '텍스트',
};

/** 입력 형식 드롭다운 옵션 — 순서는 LABEL_ATTR_INPUT_TYPES 정의 순서를 따른다. */
const INPUT_TYPE_OPTIONS: SelectOption[] = LABEL_ATTR_INPUT_TYPES.map((t) => ({
  value: t,
  label: INPUT_TYPE_LABEL[t],
}));

/** valuesJson 문자열을 문자열 배열로 안전 파싱(파싱 실패/비배열 → 빈 배열). */
export function parseValues(json: string | null): string[] {
  if (!json) return [];
  try {
    const parsed: unknown = JSON.parse(json);
    if (Array.isArray(parsed)) return parsed.map((v) => String(v));
    return [];
  } catch {
    return [];
  }
}

export interface AttrForm {
  name: string;
  inputType: LabelAttrInputType;
  values: string[];
  defaultVal: string;
  mutable: 'Y' | 'N';
  sortNo: number;
}

export interface AttrFormErrors {
  name?: string;
  values?: string;
  sortNo?: string;
}

export function emptyForm(sortNo: number): AttrForm {
  return { name: '', inputType: 'TEXT', values: [], defaultVal: '', mutable: 'Y', sortNo };
}

export function toForm(a: LabelAttrDef): AttrForm {
  return {
    name: a.name,
    inputType: a.inputType,
    values: parseValues(a.valuesJson),
    defaultVal: a.defaultVal ?? '',
    mutable: a.mutable,
    sortNo: a.sortNo,
  };
}

/** 클라이언트 사전검증 — name 필수/길이, 선택 유형이면 항목 1개 이상, sortNo 정수. */
export function validate(form: AttrForm): AttrFormErrors {
  const errors: AttrFormErrors = {};
  if (!form.name.trim()) errors.name = '속성명을 입력하세요.';
  else if (form.name.length > 64) errors.name = '속성명은 64자 이하로 입력하세요.';
  if (hasChoices(form.inputType) && form.values.filter((v) => v.trim() !== '').length === 0) {
    errors.values = '선택 항목을 1개 이상 입력하세요.';
  }
  if (!Number.isInteger(form.sortNo) || form.sortNo < 0) {
    errors.sortNo = '정렬 순서는 0 이상의 정수여야 합니다.';
  }
  return errors;
}

/** AttrForm → 전송용 LabelAttrUpsert(허용 필드만). 비선택 유형은 valuesJson=null. */
export function toUpsert(form: AttrForm): LabelAttrUpsert {
  const values = form.values.map((v) => v.trim()).filter((v) => v !== '');
  return {
    name: form.name.trim(),
    inputType: form.inputType,
    valuesJson: hasChoices(form.inputType) ? JSON.stringify(values) : null,
    defaultVal: form.defaultVal.trim() || null,
    mutable: form.mutable,
    sortNo: form.sortNo,
  };
}

interface LabelAttrFormModalProps {
  open: boolean;
  isEditing: boolean;
  form: AttrForm;
  errors: AttrFormErrors;
  submitError: string | null;
  submitting: boolean;
  onClose: () => void;
  onSubmit: () => void;
  onPatch: (patch: Partial<AttrForm>) => void;
}

export function LabelAttrFormModal({
  open,
  isEditing,
  form,
  errors,
  submitError,
  submitting,
  onClose,
  onSubmit,
  onPatch,
}: LabelAttrFormModalProps) {
  const showChoices = hasChoices(form.inputType);

  return (
    <Modal
      open={open}
      onClose={onClose}
      title={isEditing ? '속성 수정' : '속성 추가'}
      size="sm"
      closeOnBackdrop={!submitting}
      closeOnEsc={!submitting}
      footer={
        <>
          <Button variant="secondary" onClick={onClose} disabled={submitting}>
            취소
          </Button>
          <Button variant="primary" onClick={onSubmit} loading={submitting}>
            저장
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-3">
        <Input
          label="속성명"
          value={form.name}
          onChange={(e) => onPatch({ name: e.target.value })}
          error={errors.name}
          maxLength={64}
          placeholder="예: 색상, 방향"
        />

        <Select
          id="label-attr-input-type"
          label="입력 형식"
          aria-label="입력 형식"
          value={form.inputType}
          onChange={(e) => onPatch({ inputType: e.target.value as LabelAttrInputType })}
          options={INPUT_TYPE_OPTIONS}
          className="rounded-lg"
        />

        {showChoices && (
          <div className="flex flex-col gap-2">
            <div className="flex items-center justify-between">
              <span className="text-body font-medium text-gray-700">선택 항목</span>
              <Button
                variant="ghost"
                size="sm"
                onClick={() => onPatch({ values: [...form.values, ''] })}
              >
                <Plus className="mr-1 h-3.5 w-3.5" aria-hidden />
                항목 추가
              </Button>
            </div>
            {form.values.length === 0 ? (
              <p className="text-caption text-gray-400">선택 항목을 1개 이상 추가하세요.</p>
            ) : (
              form.values.map((v, idx) => (
                <div key={idx} className="flex items-center gap-2">
                  <input
                    type="text"
                    aria-label={`선택 항목 ${idx + 1}`}
                    value={v}
                    onChange={(e) => {
                      const next = [...form.values];
                      next[idx] = e.target.value;
                      onPatch({ values: next });
                    }}
                    className={`h-10 w-full rounded-lg border border-gray-300 px-3 text-body ${KRDS_FOCUS}`}
                  />
                  <Button
                    variant="ghost"
                    size="sm"
                    onClick={() => onPatch({ values: form.values.filter((_, i) => i !== idx) })}
                    aria-label={`선택 항목 ${idx + 1} 삭제`}
                  >
                    <X className="h-4 w-4" aria-hidden />
                  </Button>
                </div>
              ))
            )}
            {errors.values && (
              <span role="alert" className="text-caption text-danger">
                {errors.values}
              </span>
            )}
          </div>
        )}

        <Input
          label="기본값"
          value={form.defaultVal}
          onChange={(e) => onPatch({ defaultVal: e.target.value })}
          placeholder="선택 사항"
        />

        <Select
          id="label-attr-mutable"
          label="작업 중 값 수정"
          aria-label="작업 중 값 수정"
          value={form.mutable}
          onChange={(e) => onPatch({ mutable: e.target.value === 'N' ? 'N' : 'Y' })}
          options={MUTABLE_OPTIONS}
          className="rounded-lg"
        />

        <div className="flex flex-col gap-1">
          <label htmlFor="label-attr-sort" className="text-body font-medium text-gray-700">
            정렬 순서
          </label>
          <input
            id="label-attr-sort"
            type="number"
            min={0}
            value={form.sortNo}
            onChange={(e) => onPatch({ sortNo: Number(e.target.value) })}
            className={`h-11 w-full rounded-lg border px-3 text-body tabular-nums ${KRDS_FOCUS} ${
              errors.sortNo ? 'border-danger' : 'border-gray-300'
            }`}
          />
          {errors.sortNo && (
            <span role="alert" className="text-caption text-danger">
              {errors.sortNo}
            </span>
          )}
        </div>

        {submitError && (
          <p role="alert" className="rounded-md bg-danger/10 px-3 py-2 text-body-md text-danger">
            {submitError}
          </p>
        )}
      </div>
    </Modal>
  );
}
