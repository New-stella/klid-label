import { zodResolver } from '@hookform/resolvers/zod';
import { AlertCircle, AlertTriangle } from 'lucide-react';
import { useEffect, useMemo } from 'react';
import { useForm } from 'react-hook-form';

import { Field, FieldError, FieldLabel } from '@/components/common/Field';
import { Button } from '@/components/common/Button';
import { Input } from '@/components/common/Input';
import { Modal } from '@/components/common/Modal';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/common/Select';
import { Textarea } from '@/components/common/Textarea';
import { useEventTypes } from '@/features/eventType/hooks';
import { TYPE_LABEL } from '@/features/label/constants/labelTypes';
import { useLabelMasters } from '@/features/label/hooks/useLabelMasters';

import { presetSchema, type PresetFormValues } from '../schemas';
import type { Preset, PresetForm } from '../types';

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
  labelIds: [],
  eventTypeCd: '',
};

/**
 * 프리셋 편집 모달 — 라벨은 마스터 목록에서 선택(단일 진실원).
 *
 * - 이름 / 설명 / 매핑 이벤트 타입(V15 — 1:1 매핑, 빈 값 = 미매핑)
 * - 라벨: `useLabelMasters` 로 활성 마스터를 불러와 체크박스 멀티셀렉트. 형태는 마스터 소유이므로
 *   읽기 전용으로 표시(사용자 토글 불가)한다. 제출 시 선택한 labelId 배열만 전송한다.
 * - 편집 대상에 미연결(linked=false) 코드가 있으면 경고 배너로 재선택을 유도한다.
 */
export function PresetEditModal({
  open,
  onClose,
  initial,
  onSubmit,
  submitting,
}: PresetEditModalProps) {
  const isEdit = !!initial;
  // 매핑 이벤트 옵션 — 이벤트유형 마스터 기반 (value=이벤트유형코드, 표시=이벤트명).
  const { data: eventTypes } = useEventTypes();
  // 라벨 마스터 목록 — 프리셋 라벨의 단일 진실원.
  const { data: masters, isLoading: mastersLoading } = useLabelMasters();

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

  const selectedIds = watch('labelIds') ?? [];

  // 활성 마스터만 정렬 노출 (useYn='Y', sortNo 오름차순).
  const activeMasters = useMemo(
    () =>
      (masters ?? [])
        .filter((m) => m.useYn === 'Y')
        .slice()
        .sort((a, b) => a.sortNo - b.sortNo),
    [masters],
  );

  // 편집 대상의 미연결 코드(재선택 필요) — legacy 라벨명 안내용.
  const unlinkedNames = useMemo(
    () => (initial?.codes ?? []).filter((c) => !c.linked).map((c) => c.labelName),
    [initial],
  );

  useEffect(() => {
    if (!open) return;
    if (initial) {
      const linkedIds = initial.codes
        .filter((c): c is typeof c & { labelId: number } => c.linked && c.labelId != null)
        .map((c) => c.labelId);
      reset({
        name: initial.name,
        description: initial.description ?? '',
        labelIds: linkedIds,
        eventTypeCd: initial.eventTypeCd ?? '',
      });
    } else {
      reset(EMPTY_FORM);
    }
  }, [open, initial, reset]);

  // 이벤트 옵션은 비동기 로드되므로, 옵션 준비 후 initial 의 매핑값(이벤트유형코드)을 select 에 재반영한다.
  useEffect(() => {
    if (open && initial && eventTypes) {
      setValue('eventTypeCd', initial.eventTypeCd ?? '');
    }
  }, [open, initial, eventTypes, setValue]);

  const toggleLabel = (labelId: number) => {
    const next = selectedIds.includes(labelId)
      ? selectedIds.filter((id) => id !== labelId)
      : [...selectedIds, labelId];
    setValue('labelIds', next, { shouldValidate: true, shouldDirty: true });
  };

  const submit = handleSubmit((form) => {
    onSubmit({
      name: form.name,
      description: form.description ?? '',
      labelIds: form.labelIds,
      eventTypeCd: form.eventTypeCd ?? '',
    });
  });

  const saveDisabled = !!submitting || selectedIds.length === 0;
  const eventTypeCd = watch('eventTypeCd') ?? '';

  return (
    <Modal
      open={open}
      onClose={onClose}
      size="lg"
      title={isEdit ? '프리셋 편집' : '새 프리셋 만들기'}
      footer={
        <>
          <Button type="button" variant="secondary" onClick={onClose} disabled={submitting}>
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
        <Field className="gap-1.5">
          <FieldLabel className="block text-label font-medium text-gray-700" htmlFor="preset-name">
            프리셋 이름 <span className="text-danger">*</span>
          </FieldLabel>
          <Input
            id="preset-name"
            type="text"
            placeholder="예: 교통사고 표준 프리셋"
            {...register('name')}
          />
          <FieldError>{errors.name?.message}</FieldError>
        </Field>

        {/* Description */}
        <Field className="gap-1.5">
          <FieldLabel className="block text-label font-medium text-gray-700" htmlFor="preset-desc">
            설명
          </FieldLabel>
          <Textarea
            id="preset-desc"
            placeholder="프리셋에 대한 설명을 입력하세요."
            className="min-h-[72px] resize-none"
            {...register('description')}
          />
          <FieldError>{errors.description?.message}</FieldError>
        </Field>

        {/* Event type mapping (V15 — 1:1) */}
        <Field className="gap-1.5">
          <FieldLabel className="block text-label font-medium text-gray-700" htmlFor="preset-event">
            매핑 이벤트 타입
            <span className="ml-1 font-normal text-gray-400">
              (오토라벨 시 이 이벤트의 영상에 본 프리셋 적용)
            </span>
          </FieldLabel>
          <Select
            value={eventTypeCd}
            onValueChange={(v) => setValue('eventTypeCd', v, { shouldDirty: true })}
          >
            <SelectTrigger id="preset-event">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {/* '선택 안 함'(빈 값 = 미매핑) + 이벤트유형 마스터 카테고리. */}
              <SelectItem value="">선택 안 함 (-)</SelectItem>
              {(eventTypes ?? []).map((o) => (
                <SelectItem key={o.categoryKey} value={o.categoryKey}>
                  {o.label}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
          <FieldError>{errors.eventTypeCd?.message}</FieldError>
          {/* 이 안내는 오류 여부와 무관하게 항상 노출한다(교체 전 동작 유지) — FieldDescription 은
              오류가 있으면 aria-describedby 대상에서 밀리므로 일반 문단으로 둔다. */}
          <p className="text-caption text-gray-400">
            동일 이벤트는 1개 프리셋에만 매핑됩니다. 이미 다른 프리셋이 매핑된 경우 저장 시
            안내됩니다.
          </p>
        </Field>

        {/* Label master selection */}
        <div className="space-y-3">
          <label className="block text-label font-medium text-gray-700" id="preset-labels-label">
            라벨 항목 <span className="text-danger">*</span>
            <span className="ml-1 font-normal text-gray-400" data-testid="preset-labels-count">
              ({selectedIds.length}개 선택)
            </span>
          </label>
          <p className="text-caption text-gray-400">
            라벨은 라벨 마스터에서 선택합니다. 형태는 라벨 마스터에서 정한 값이며 여기서는 변경할 수
            없습니다.
          </p>

          {/* 미연결 경고 (편집 시) */}
          {unlinkedNames.length > 0 && (
            <div
              className="flex items-start gap-2 rounded-lg border border-amber-300 bg-amber-50 px-3 py-2 text-caption text-amber-800"
              role="alert"
              data-testid="preset-unlinked-warning"
            >
              <AlertTriangle className="mt-0.5 h-3.5 w-3.5 shrink-0" aria-hidden="true" />
              <span>
                더 이상 라벨 마스터에 없는 항목({unlinkedNames.join(', ')})이 있습니다. 저장 시
                제외되니 필요한 라벨을 아래에서 다시 선택하세요.
              </span>
            </div>
          )}

          {mastersLoading ? (
            <p className="text-caption text-gray-400" role="status">
              라벨 마스터를 불러오는 중…
            </p>
          ) : activeMasters.length === 0 ? (
            <p className="text-caption text-gray-500" data-testid="preset-no-masters">
              선택할 라벨 마스터가 없습니다. 먼저 라벨 관리에서 라벨을 등록하세요.
            </p>
          ) : (
            <ul
              className="grid grid-cols-1 gap-1.5 md:grid-cols-2"
              data-testid="preset-master-list"
              aria-labelledby="preset-labels-label"
            >
              {activeMasters.map((m) => {
                const checkboxId = `preset-label-${m.labelId}`;
                const checked = selectedIds.includes(m.labelId);
                return (
                  <li key={m.labelId}>
                    <label
                      htmlFor={checkboxId}
                      className={[
                        'flex cursor-pointer items-center gap-2 rounded-lg border px-3 py-2 text-label transition-colors',
                        checked
                          ? 'border-primary-300 bg-primary-50'
                          : 'border-gray-200 bg-white hover:border-primary-200',
                      ].join(' ')}
                    >
                      <input
                        id={checkboxId}
                        type="checkbox"
                        checked={checked}
                        onChange={() => toggleLabel(m.labelId)}
                        className="h-4 w-4 rounded border-gray-300 text-primary-500 focus:ring-primary-400"
                      />
                      <span
                        className="inline-block h-3 w-3 shrink-0 rounded-full"
                        style={{ backgroundColor: m.color }}
                        aria-hidden="true"
                      />
                      <span className="min-w-0 flex-1 truncate font-medium text-gray-800">
                        {m.name}
                      </span>
                      <span className="shrink-0 rounded bg-gray-100 px-1.5 py-0.5 text-[10px] text-gray-500">
                        {TYPE_LABEL[m.type]}
                      </span>
                    </label>
                  </li>
                );
              })}
            </ul>
          )}

          {errors.labelIds?.message && (
            <p className="flex items-center gap-1 text-caption text-danger" role="alert">
              <AlertCircle className="h-3.5 w-3.5 shrink-0" aria-hidden="true" />
              {errors.labelIds.message}
            </p>
          )}
        </div>
      </form>
    </Modal>
  );
}
