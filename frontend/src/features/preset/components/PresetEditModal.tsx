import { zodResolver } from '@hookform/resolvers/zod';
import { AlertCircle, Plus } from 'lucide-react';
import { useEffect, useMemo, useState } from 'react';
import { useForm } from 'react-hook-form';

import { Button } from '@/components/common/Button';
import { Modal } from '@/components/common/Modal';
import { KRDS_FOCUS } from '@/lib/focusRing';
import { useEventTypes } from '@/features/eventType/hooks';

import {
  presetSchema,
  type PresetFormInput,
  type PresetFormOutput,
} from '../schemas';
import {
  PRESET_LABEL_SUGGESTIONS,
  type LabelCodeOption,
  type Preset,
  type PresetForm,
} from '../types';

import { PresetCodeChip } from './PresetCodeChip';

export interface PresetEditModalProps {
  open: boolean;
  onClose: () => void;
  initial?: Preset;
  onSubmit: (form: PresetForm) => void;
  submitting?: boolean;
}

const EMPTY_FORM: PresetFormInput = {
  name: '',
  description: '',
  labelCodes: [],
  labelCodeOptions: [],
  eventTypeCd: '',
};

/**
 * mock의 PresetFormModal 패턴을 그대로 포팅한 모달.
 * - 이름 / 설명 / 라벨 코드 chips + 빠른 추가 chips
 * - 각 chip 옆에 BBOX/POLYGON 체크박스 (Phase 3)
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
  // 매핑 이벤트 옵션 — 관제 마스터 기반 9 카테고리 (value=categoryKey, 표시=label).
  const { data: eventTypes } = useEventTypes();

  const {
    register,
    handleSubmit,
    reset,
    setValue,
    watch,
    formState: { errors },
  } = useForm<PresetFormInput, unknown, PresetFormOutput>({
    resolver: zodResolver(presetSchema),
    defaultValues: EMPTY_FORM,
  });

  const labelCodeOptions = watch('labelCodeOptions') ?? [];
  const [newCode, setNewCode] = useState('');

  useEffect(() => {
    if (!open) return;
    if (initial) {
      // initial 의 labelCodeOptions 가 있으면 우선, 없으면 labelCodes 로부터 모두 (true,true) 로 변환
      const opts: LabelCodeOption[] =
        initial.labelCodeOptions && initial.labelCodeOptions.length > 0
          ? initial.labelCodeOptions.map((o) => ({ ...o }))
          : initial.labelCodes.map((code) => ({
              code,
              bboxEnabled: true,
              polygonEnabled: true,
            }));
      reset({
        name: initial.name,
        description: initial.description ?? '',
        labelCodes: opts.map((o) => o.code),
        labelCodeOptions: opts,
        eventTypeCd: initial.eventTypeCd ?? '',
      });
    } else {
      reset(EMPTY_FORM);
    }
    setNewCode('');
  }, [open, initial, reset]);

  // 이벤트 옵션은 비동기 로드되므로, 옵션 준비 후 initial 의 매핑값(categoryKey)을 select 에 재반영한다.
  // (옵션이 아직 없을 때 reset 하면 native select 가 빈 값으로 남는 문제 보정 — 운영/테스트 공통)
  useEffect(() => {
    if (open && initial && eventTypes) {
      setValue('eventTypeCd', initial.eventTypeCd ?? '');
    }
  }, [open, initial, eventTypes, setValue]);

  const setOptions = (opts: LabelCodeOption[]) => {
    setValue('labelCodeOptions', opts, { shouldValidate: true, shouldDirty: true });
    setValue(
      'labelCodes',
      opts.map((o) => o.code),
      { shouldValidate: false, shouldDirty: true },
    );
  };

  const addLabel = (code: string) => {
    const upper = code.trim().toUpperCase();
    if (!upper) return;
    if (labelCodeOptions.some((o) => o.code === upper)) return;
    setOptions([
      ...labelCodeOptions,
      { code: upper, bboxEnabled: true, polygonEnabled: true },
    ]);
    setNewCode('');
  };

  const removeLabel = (code: string) => {
    setOptions(labelCodeOptions.filter((o) => o.code !== code));
  };

  const toggleBbox = (code: string) => {
    setOptions(
      labelCodeOptions.map((o) =>
        o.code === code ? { ...o, bboxEnabled: !o.bboxEnabled } : o,
      ),
    );
  };

  const togglePolygon = (code: string) => {
    setOptions(
      labelCodeOptions.map((o) =>
        o.code === code ? { ...o, polygonEnabled: !o.polygonEnabled } : o,
      ),
    );
  };

  // chip 중 하나라도 둘 다 off 면 저장 disabled
  const hasInvalidOption = useMemo(
    () =>
      labelCodeOptions.some((o) => !o.bboxEnabled && !o.polygonEnabled),
    [labelCodeOptions],
  );

  const submit = handleSubmit((form) => {
    onSubmit({
      name: form.name,
      description: form.description ?? '',
      labelCodes: form.labelCodeOptions.map((o) => o.code),
      labelCodeOptions: form.labelCodeOptions,
      eventTypeCd: form.eventTypeCd ?? '',
    });
  });

  const saveDisabled = !!submitting || hasInvalidOption;

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
            disabled={saveDisabled}
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
            프리셋 이름 <span className="text-danger">*</span>
          </label>
          <input
            id="preset-name"
            type="text"
            placeholder="예: 교통사고 표준 프리셋"
            className={[
              `w-full text-sm border rounded-lg px-3 py-2 ${KRDS_FOCUS}`,
              errors.name ? 'border-danger' : 'border-gray-300',
            ].join(' ')}
            {...register('name')}
          />
          {errors.name?.message && (
            <p className="flex items-center gap-1 text-xs text-danger" role="alert">
              <AlertCircle className="h-3.5 w-3.5 shrink-0" aria-hidden="true" />
              {errors.name.message}
            </p>
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
            className={`w-full text-sm border border-gray-300 rounded-lg px-3 py-2 resize-none ${KRDS_FOCUS}`}
            {...register('description')}
          />
          {errors.description?.message && (
            <p className="flex items-center gap-1 text-xs text-danger" role="alert">
              <AlertCircle className="h-3.5 w-3.5 shrink-0" aria-hidden="true" />
              {errors.description.message}
            </p>
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
              `w-full text-sm border rounded-lg px-3 py-2 bg-white ${KRDS_FOCUS}`,
              errors.eventTypeCd ? 'border-danger' : 'border-gray-300',
            ].join(' ')}
            {...register('eventTypeCd')}
          >
            <option value="">선택 안 함 (-)</option>
            {(eventTypes ?? []).map((o) => (
              <option key={o.categoryKey} value={o.categoryKey}>
                {o.label}
              </option>
            ))}
          </select>
          {errors.eventTypeCd?.message && (
            <p className="flex items-center gap-1 text-xs text-danger" role="alert">
              <AlertCircle className="h-3.5 w-3.5 shrink-0" aria-hidden="true" />
              {errors.eventTypeCd.message}
            </p>
          )}
          <p className="text-xs text-gray-400">
            동일 이벤트는 1개 프리셋에만 매핑됩니다. 이미 다른 프리셋이 매핑된 경우 저장 시 안내됩니다.
          </p>
        </div>

        {/* Label codes */}
        <div className="space-y-3">
          <label className="block text-sm font-medium text-gray-700">
            라벨 항목 <span className="text-danger">*</span>
            <span
              className="ml-1 font-normal text-gray-400"
              data-testid="preset-labels-count"
            >
              ({labelCodeOptions.length}개)
            </span>
          </label>

          {/* Existing labels */}
          {labelCodeOptions.length > 0 && (
            <div className="flex flex-wrap gap-2" data-testid="preset-labels-list">
              {labelCodeOptions.map((option) => (
                <PresetCodeChip
                  key={option.code}
                  option={option}
                  onToggleBbox={() => toggleBbox(option.code)}
                  onTogglePolygon={() => togglePolygon(option.code)}
                  onRemove={() => removeLabel(option.code)}
                />
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
              className={`flex-1 text-sm border border-gray-300 rounded-lg px-3 py-2 ${KRDS_FOCUS}`}
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

          {errors.labelCodeOptions?.message && (
            <p className="flex items-center gap-1 text-xs text-danger" role="alert">
              <AlertCircle className="h-3.5 w-3.5 shrink-0" aria-hidden="true" />
              {errors.labelCodeOptions.message}
            </p>
          )}
          {hasInvalidOption && (
            <p className="flex items-center gap-1 text-xs text-danger" role="alert">
              <AlertCircle className="h-3.5 w-3.5 shrink-0" aria-hidden="true" />
              BBOX 또는 POLYGON 중 최소 하나는 활성화해야 합니다.
            </p>
          )}

          {/* Suggestions */}
          <div className="space-y-1">
            <p className="text-xs text-gray-400">빠른 추가:</p>
            <div className="flex flex-wrap gap-1.5" data-testid="preset-suggestions">
              {PRESET_LABEL_SUGGESTIONS.filter(
                (s) => !labelCodeOptions.some((o) => o.code === s.code),
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
