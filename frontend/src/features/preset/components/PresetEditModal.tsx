import { zodResolver } from '@hookform/resolvers/zod';
import { Plus, Trash2 } from 'lucide-react';
import { useEffect, useState } from 'react';
import { useForm } from 'react-hook-form';

import { Button } from '@/components/common/Button';
import { Modal } from '@/components/common/Modal';

import { presetSchema, type PresetFormValues } from '../schemas';
import {
  EVENT_TYPE_OPTIONS,
  PRESET_LABEL_SUGGESTIONS,
  type Preset,
  type PresetForm,
} from '../types';

export interface PresetEditModalProps {
  open: boolean;
  onClose: () => void;
  initial?: Preset;
  onSubmit: (form: PresetForm) => void;
  submitting?: boolean;
}

const EMPTY_FORM: PresetFormValues = {
  name: '',
  description: '',
  labelCodes: [],
  eventTypeCd: '',
};

/**
 * mock의 PresetFormModal 패턴을 그대로 포팅한 모달.
 * - 이름 / 설명 / 라벨 코드 chips + 빠른 추가 chips
 * - 매핑 이벤트 타입 select (V15 — 1:1 매핑, 빈 값 = 미매핑)
 */
export function PresetEditModal({
  open,
  onClose,
  initial,
  onSubmit,
  submitting,
}: PresetEditModalProps) {
  const isEdit = !!initial;

  const {
    register,
    handleSubmit,
    reset,
    setValue,
    watch,
    formState: { errors },
  } = useForm<PresetFormValues>({
    resolver: zodResolver(presetSchema),
    defaultValues: EMPTY_FORM,
  });

  const labelCodes = watch('labelCodes') ?? [];
  const [newCode, setNewCode] = useState('');

  useEffect(() => {
    if (open) {
      reset(
        initial
          ? {
              name: initial.name,
              description: initial.description ?? '',
              labelCodes: [...initial.labelCodes],
              eventTypeCd: initial.eventTypeCd ?? '',
            }
          : EMPTY_FORM,
      );
      setNewCode('');
    }
  }, [open, initial, reset]);

  const addLabel = (code: string) => {
    const upper = code.trim().toUpperCase();
    if (!upper || labelCodes.includes(upper)) return;
    setValue('labelCodes', [...labelCodes, upper], {
      shouldValidate: true,
      shouldDirty: true,
    });
    setNewCode('');
  };

  const removeLabel = (code: string) => {
    setValue(
      'labelCodes',
      labelCodes.filter((c) => c !== code),
      { shouldValidate: true, shouldDirty: true },
    );
  };

  const submit = handleSubmit((form) => {
    onSubmit({
      name: form.name,
      description: form.description ?? '',
      labelCodes: form.labelCodes,
      eventTypeCd: form.eventTypeCd ?? '',
    });
  });

  return (
    <Modal
      open={open}
      onClose={onClose}
      size="lg"
      title={isEdit ? '프리셋 편집' : '새 프리셋 만들기'}
      footer={
        <>
          <Button
            type="button"
            variant="secondary"
            onClick={onClose}
            disabled={submitting}
          >
            취소
          </Button>
          <Button
            type="button"
            variant="primary"
            onClick={submit}
            loading={submitting}
          >
            {isEdit ? '저장' : '만들기'}
          </Button>
        </>
      }
    >
      <form
        className="space-y-5"
        onSubmit={(e) => {
          e.preventDefault();
          submit();
        }}
      >
        {/* Name */}
        <div className="space-y-1.5">
          <label
            className="block text-sm font-medium text-gray-700"
            htmlFor="preset-name"
          >
            프리셋 이름 <span className="text-red-500">*</span>
          </label>
          <input
            id="preset-name"
            type="text"
            placeholder="예: 교통사고 표준 프리셋"
            className={[
              'w-full text-sm border rounded-lg px-3 py-2 focus:outline-none focus:ring-2 focus:ring-primary-500',
              errors.name ? 'border-red-400' : 'border-gray-300',
            ].join(' ')}
            {...register('name')}
          />
          {errors.name?.message && (
            <p className="text-xs text-red-500">{errors.name.message}</p>
          )}
        </div>

        {/* Description */}
        <div className="space-y-1.5">
          <label
            className="block text-sm font-medium text-gray-700"
            htmlFor="preset-desc"
          >
            설명
          </label>
          <textarea
            id="preset-desc"
            placeholder="프리셋에 대한 설명을 입력하세요."
            rows={2}
            className="w-full text-sm border border-gray-300 rounded-lg px-3 py-2 resize-none focus:outline-none focus:ring-2 focus:ring-primary-500"
            {...register('description')}
          />
          {errors.description?.message && (
            <p className="text-xs text-red-500">{errors.description.message}</p>
          )}
        </div>

        {/* Event type mapping (V15 — 1:1) */}
        <div className="space-y-1.5">
          <label
            className="block text-sm font-medium text-gray-700"
            htmlFor="preset-event"
          >
            매핑 이벤트 타입
            <span className="ml-1 font-normal text-gray-400">
              (오토라벨 시 이 이벤트의 영상에 본 프리셋 적용)
            </span>
          </label>
          <select
            id="preset-event"
            className={[
              'w-full text-sm border rounded-lg px-3 py-2 bg-white focus:outline-none focus:ring-2 focus:ring-primary-500',
              errors.eventTypeCd ? 'border-red-400' : 'border-gray-300',
            ].join(' ')}
            {...register('eventTypeCd')}
          >
            <option value="">선택 안 함 (-)</option>
            {EVENT_TYPE_OPTIONS.map((o) => (
              <option key={o.code} value={o.code}>
                {o.code} — {o.name}
              </option>
            ))}
          </select>
          {errors.eventTypeCd?.message && (
            <p className="text-xs text-red-500">{errors.eventTypeCd.message}</p>
          )}
          <p className="text-xs text-gray-400">
            동일 이벤트는 1개 프리셋에만 매핑됩니다. 이미 다른 프리셋이 매핑된 경우 저장 시 안내됩니다.
          </p>
        </div>

        {/* Label codes */}
        <div className="space-y-3">
          <label className="block text-sm font-medium text-gray-700">
            라벨 항목 <span className="text-red-500">*</span>
            <span
              className="ml-1 font-normal text-gray-400"
              data-testid="preset-labels-count"
            >
              ({labelCodes.length}개)
            </span>
          </label>

          {/* Existing labels */}
          {labelCodes.length > 0 && (
            <div className="flex flex-wrap gap-2" data-testid="preset-labels-list">
              {labelCodes.map((code) => (
                <span
                  key={code}
                  className="inline-flex items-center gap-1 px-2.5 py-1 rounded-full bg-primary-100 text-primary-700 text-xs font-medium"
                >
                  {code}
                  <button
                    type="button"
                    onClick={() => removeLabel(code)}
                    className="ml-0.5 hover:text-red-500 transition-colors"
                    aria-label={`${code} 삭제`}
                  >
                    <Trash2 className="h-3 w-3" aria-hidden />
                  </button>
                </span>
              ))}
            </div>
          )}

          {/* Add new label */}
          <div className="flex items-center gap-2">
            <input
              type="text"
              value={newCode}
              onChange={(e) => setNewCode(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter') {
                  e.preventDefault();
                  addLabel(newCode);
                }
              }}
              placeholder="라벨 코드 입력 (Enter로 추가)"
              className="flex-1 text-sm border border-gray-300 rounded-lg px-3 py-2 focus:outline-none focus:ring-2 focus:ring-primary-500"
              aria-label="라벨 코드 입력"
            />
            <Button
              type="button"
              variant="secondary"
              size="sm"
              leftIcon={Plus}
              onClick={() => addLabel(newCode)}
              disabled={!newCode.trim()}
            >
              추가
            </Button>
          </div>

          {errors.labelCodes?.message && (
            <p className="text-xs text-red-500" role="alert">
              {errors.labelCodes.message}
            </p>
          )}

          {/* Suggestions */}
          <div className="space-y-1">
            <p className="text-xs text-gray-400">빠른 추가:</p>
            <div className="flex flex-wrap gap-1.5" data-testid="preset-suggestions">
              {PRESET_LABEL_SUGGESTIONS.filter(
                (s) => !labelCodes.includes(s.code),
              ).map((s) => (
                <button
                  key={s.code}
                  type="button"
                  onClick={() => addLabel(s.code)}
                  className="text-xs px-2 py-0.5 rounded border border-gray-200 bg-gray-50 text-gray-600 hover:border-primary-300 hover:bg-primary-50 hover:text-primary-700 transition-colors"
                >
                  + {s.code}{' '}
                  <span className="text-gray-400">({s.name})</span>
                </button>
              ))}
            </div>
          </div>
        </div>
      </form>
    </Modal>
  );
}
