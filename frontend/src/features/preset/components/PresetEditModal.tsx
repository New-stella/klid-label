import { zodResolver } from '@hookform/resolvers/zod';
import { useEffect } from 'react';
import { useFieldArray, useForm } from 'react-hook-form';

import { Button } from '@/components/common/Button';
import { Input } from '@/components/common/Input';
import { Modal } from '@/components/common/Modal';
import { Select } from '@/components/common/Select';

import { presetSchema, type PresetForm } from '../schemas';
import type { Preset } from '../types';

const EVENT_OPTIONS = [
  { value: 'FIRE', label: '화재' },
  { value: 'FALL', label: '쓰러짐' },
  { value: 'INVASION', label: '침입' },
  { value: 'CROWD', label: '군집' },
  { value: 'VIOLENCE', label: '폭력' },
  { value: 'ABANDON', label: '유기/방치' },
];

const SHAPE_OPTIONS = [
  { value: 'BBOX', label: '바운딩박스' },
  { value: 'POLYGON', label: '폴리곤' },
  { value: 'SEGMENT', label: '세그멘테이션' },
  { value: 'TRACK', label: '트랙' },
];

const MAX_ITEMS = 6;

export interface PresetEditModalProps {
  open: boolean;
  onClose: () => void;
  initial?: Preset;
  onSubmit: (form: PresetForm) => void;
  submitting?: boolean;
}

const EMPTY_FORM: PresetForm = {
  name: '',
  eventTypeCd: 'FIRE',
  subType: '',
  items: [{ name: '항목1', shape: 'BBOX', color: '#ef4444' }],
};

export function PresetEditModal({
  open,
  onClose,
  initial,
  onSubmit,
  submitting,
}: PresetEditModalProps) {
  const {
    register,
    control,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<PresetForm>({
    resolver: zodResolver(presetSchema),
    defaultValues: EMPTY_FORM,
  });

  const { fields, append, remove } = useFieldArray({ control, name: 'items' });

  useEffect(() => {
    if (open) {
      reset(
        initial
          ? {
              name: initial.name,
              eventTypeCd: initial.eventTypeCd,
              subType: initial.subType ?? '',
              items: initial.items.map((it) => ({
                id: it.id,
                name: it.name,
                shape: it.shape,
                color: it.color,
                attributes: it.attributes,
              })),
            }
          : EMPTY_FORM,
      );
    }
  }, [open, initial, reset]);

  const submit = handleSubmit((form) => {
    onSubmit(form);
  });

  const canAdd = fields.length < MAX_ITEMS;

  return (
    <Modal
      open={open}
      onClose={onClose}
      size="lg"
      title={initial ? '프리셋 수정' : '프리셋 추가'}
      footer={
        <>
          <Button type="button" variant="outline" onClick={onClose} disabled={submitting}>
            취소
          </Button>
          <Button type="button" variant="primary" onClick={submit} loading={submitting}>
            저장
          </Button>
        </>
      }
    >
      <form
        className="flex flex-col gap-3"
        onSubmit={(e) => {
          e.preventDefault();
          submit();
        }}
      >
        <Input
          label="프리셋명"
          placeholder="예: 화재 표준 라벨"
          error={errors.name?.message}
          {...register('name')}
        />
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
          <Select
            label="이벤트 유형"
            options={EVENT_OPTIONS}
            {...register('eventTypeCd')}
          />
          <Input
            label="서브 유형 (선택)"
            placeholder="예: 산불"
            error={errors.subType?.message}
            {...register('subType')}
          />
        </div>

        <div className="flex items-center justify-between border-b border-border pb-2">
          <h3 className="text-section-title text-primary">라벨 항목</h3>
          <span
            className="text-sub text-neutral"
            data-testid="preset-items-count"
          >
            {fields.length} / {MAX_ITEMS}
          </span>
        </div>

        <ul className="flex flex-col gap-2" data-testid="preset-items-list">
          {fields.map((field, idx) => (
            <li
              key={field.id}
              className="grid grid-cols-12 items-end gap-2 rounded border border-border p-2"
            >
              <div className="col-span-4">
                <Input
                  label={`항목 ${idx + 1}`}
                  placeholder="라벨명"
                  error={errors.items?.[idx]?.name?.message}
                  {...register(`items.${idx}.name`)}
                />
              </div>
              <div className="col-span-3">
                <Select
                  label="형태"
                  options={SHAPE_OPTIONS}
                  {...register(`items.${idx}.shape`)}
                />
              </div>
              <div className="col-span-3">
                <Input
                  label="색상"
                  type="color"
                  className="h-10 p-1"
                  error={errors.items?.[idx]?.color?.message}
                  {...register(`items.${idx}.color`)}
                />
              </div>
              <div className="col-span-2 flex justify-end">
                <Button
                  type="button"
                  variant="ghost"
                  size="sm"
                  onClick={() => remove(idx)}
                  disabled={fields.length <= 1}
                >
                  삭제
                </Button>
              </div>
            </li>
          ))}
        </ul>

        {errors.items?.message && (
          <p className="text-sub text-danger" role="alert">
            {errors.items.message}
          </p>
        )}

        <div>
          <Button
            type="button"
            variant="outline"
            size="sm"
            disabled={!canAdd}
            onClick={() =>
              append({ name: `항목${fields.length + 1}`, shape: 'BBOX', color: '#3b82f6' })
            }
            data-testid="preset-add-item-btn"
          >
            + 라벨 항목 추가
          </Button>
          {!canAdd && (
            <p className="mt-1 text-sub text-neutral">최대 6개까지 등록할 수 있습니다</p>
          )}
        </div>
      </form>
    </Modal>
  );
}
