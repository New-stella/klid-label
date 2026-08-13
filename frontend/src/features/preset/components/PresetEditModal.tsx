import { zodResolver } from '@hookform/resolvers/zod';
import { AlertCircle, Info } from 'lucide-react';
import { useEffect, useMemo } from 'react';
import { useForm } from 'react-hook-form';

import { Field, FieldError, FieldLabel, FieldTitle } from '@/components/common/Field';
import { Button } from '@/components/common/Button';
import { FieldCounter } from '@/components/common/FieldCounter';
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
import { cn } from '@/lib/cn';
import { KRDS_FOCUS } from '@/lib/focusRing';

import {
  DESCRIPTION_MAX_LENGTH,
  LABEL_IDS_MAX_COUNT,
  NAME_MAX_LENGTH,
  presetSchema,
  type PresetFormValues,
} from '../schemas';
import type { Preset, PresetForm } from '../types';

/** 필드 라벨 — DS-001 ladder `label`(14px/600). */
const FIELD_LABEL_CLASS = 'text-label font-semibold text-gray-900';
/**
 * 보조 도움말 — 흰 모달 표면 위 gray-600(6.30:1 AA).
 *
 * ⚠ gray-400/500 으로 내리지 말 것: 400 은 흰 배경에서도 AA 미달이고 500 은 경계(4.51)라
 *   모달이 회색 표면 위에 놓이는 변형이 생기면 곧바로 미달로 떨어진다.
 */
const FIELD_HELP_CLASS = 'text-caption text-gray-600';

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
  /** 글자수 카운터 표시용 — 입력값 길이만 읽고 제출 값에는 관여하지 않는다. */
  const descriptionLength = (watch('description') ?? '').length;

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
          <FieldLabel className={FIELD_LABEL_CLASS} htmlFor="preset-name" required>
            프리셋 이름
          </FieldLabel>
          <Input
            id="preset-name"
            type="text"
            placeholder="예: 교통사고 표준 프리셋"
            {...register('name')}
          />
          <FieldError>{errors.name?.message}</FieldError>
          <p className={FIELD_HELP_CLASS}>필수. 1~{NAME_MAX_LENGTH}자.</p>
        </Field>

        {/* Description */}
        <Field className="gap-1.5">
          {/* 글자수 카운터는 라벨 줄 오른쪽에 둔다 — 입력 아래에 두면 도움말·오류와 겹쳐 읽힌다. */}
          <div className="flex items-baseline justify-between gap-2">
            <FieldLabel className={FIELD_LABEL_CLASS} htmlFor="preset-desc">
              설명
            </FieldLabel>
            <FieldCounter current={descriptionLength} max={DESCRIPTION_MAX_LENGTH} />
          </div>
          <Textarea
            id="preset-desc"
            placeholder="프리셋에 대한 설명을 입력하세요."
            className="min-h-24 resize-y"
            {...register('description')}
          />
          <FieldError>{errors.description?.message}</FieldError>
          <p className={FIELD_HELP_CLASS}>선택. 0~{DESCRIPTION_MAX_LENGTH}자.</p>
        </Field>

        {/* Event type mapping (V15 — 1:1) */}
        <Field className="gap-1.5">
          <FieldLabel className={FIELD_LABEL_CLASS} htmlFor="preset-event">
            매핑 이벤트 타입
            <span className="ml-1 font-normal text-gray-600">
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
          <p className={FIELD_HELP_CLASS}>
            선택. 동일 이벤트는 1개 프리셋에만 매핑됩니다. 이미 다른 프리셋이 매핑된 경우 저장 시
            안내됩니다.
          </p>
        </Field>

        {/* Label master selection */}
        <div className="flex flex-col gap-2">
          <div className="flex items-baseline justify-between gap-2">
            {/* 특정 입력 하나가 아니라 목록 전체의 제목이라 label 이 아니라 FieldTitle(div) 이다 —
                아래 ul 이 aria-labelledby 로 이 id 를 가리킨다. */}
            <FieldTitle className={FIELD_LABEL_CLASS} id="preset-labels-label">
              라벨 항목
              <span aria-hidden="true" className="ml-0.5 text-danger-700">
                *
              </span>
              <span className="sr-only"> (필수)</span>
            </FieldTitle>
            {/* 상한이 있는 선택이라 고른 개수가 목록보다 먼저 읽혀야 한다 — 숫자를 굵게 강조한다. */}
            <span className={FIELD_HELP_CLASS} data-testid="preset-labels-count">
              <strong className="font-semibold tabular-nums text-gray-900">
                {selectedIds.length}
              </strong>{' '}
              / {LABEL_IDS_MAX_COUNT}개 선택
            </span>
          </div>
          <p className={FIELD_HELP_CLASS}>
            활성 라벨 마스터를 정렬순으로 나열했습니다. 형태는 라벨 마스터가 소유하는 읽기 전용
            값이라 여기서는 변경할 수 없습니다. 최소 1개~최대 {LABEL_IDS_MAX_COUNT}개 선택.
          </p>

          {/* 미연결 안내 (편집 시) — 오류가 아니라 "저장 시 자동 제외"를 알리는 정보 배너다. */}
          {unlinkedNames.length > 0 && (
            <div
              className="flex items-start gap-2 rounded-md border border-info-200 bg-info-50 px-4 py-3 text-body-sm text-info-700"
              role="alert"
              data-testid="preset-unlinked-warning"
            >
              <Info className="mt-1 h-4 w-4 shrink-0" aria-hidden="true" />
              <div className="flex min-w-0 flex-col gap-1">
                <p className="font-semibold">더 이상 라벨 마스터에 없는 항목이 있습니다</p>
                <ul className="list-disc pl-5">
                  {unlinkedNames.map((name) => (
                    <li key={name}>{name} · 미연결</li>
                  ))}
                </ul>
                <p>저장 시 이 항목은 자동으로 제외됩니다. 필요한 라벨을 아래에서 다시 선택하세요.</p>
              </div>
            </div>
          )}

          {mastersLoading ? (
            <p className={FIELD_HELP_CLASS} role="status">
              라벨 마스터를 불러오는 중…
            </p>
          ) : activeMasters.length === 0 ? (
            <p
              className="rounded-md border border-gray-200 bg-gray-50 px-4 py-3 text-body-sm text-gray-600"
              data-testid="preset-no-masters"
            >
              선택할 라벨 마스터가 없습니다. 먼저 라벨 관리에서 라벨을 등록하세요.
            </p>
          ) : (
            // 스크롤 높이를 260px 로 묶는다 — 마스터가 수십 건이어도 모달이 밀리지 않고,
            // "스크롤해서 고른다"는 실제 사용 감각이 유지된다(사양 SCREEN-026 디자인).
            <div className="max-h-[260px] overflow-y-auto rounded-md border border-gray-200">
              <ul
                className="divide-y divide-gray-100"
                data-testid="preset-master-list"
                aria-labelledby="preset-labels-label"
              >
                {activeMasters.map((m) => {
                  const checkboxId = `preset-label-${m.labelId}`;
                  const checked = selectedIds.includes(m.labelId);
                  return (
                    <li key={m.labelId}>
                      {/* 44px 터치 타깃은 행 라벨이 만든다(KRDS) — 체크박스만으로는 좁다. */}
                      <label
                        htmlFor={checkboxId}
                        className={[
                          'flex min-h-11 cursor-pointer select-none items-center gap-2 px-3 py-2 text-body-sm transition-colors duration-100',
                          checked ? 'bg-primary-50' : 'bg-white hover:bg-gray-50',
                        ].join(' ')}
                      >
                        <input
                          id={checkboxId}
                          type="checkbox"
                          checked={checked}
                          onChange={() => toggleLabel(m.labelId)}
                          // 네이티브 체크박스의 체크 표면은 `accent-*`(accent-color)로 칠한다 —
                          // 이 저장소엔 @tailwindcss/forms 가 없어 `text-*` 는 체크박스에 아무
                          // 효과가 없고, 그래서 그동안 브라우저 기본색이 그대로 나왔다.
                          className={cn(
                            'h-5 w-5 shrink-0 rounded border-gray-400 accent-primary-600',
                            KRDS_FOCUS,
                          )}
                        />
                        <span
                          className="inline-block h-3 w-3 shrink-0 rounded-full"
                          style={{ backgroundColor: m.color }}
                          aria-hidden="true"
                        />
                        <span className="min-w-0 flex-1 truncate text-gray-900">{m.name}</span>
                        <span className="shrink-0 rounded-sm border border-gray-200 bg-white px-1.5 text-caption font-semibold text-gray-600">
                          {TYPE_LABEL[m.type]}
                        </span>
                      </label>
                    </li>
                  );
                })}
              </ul>
            </div>
          )}

          {errors.labelIds?.message && (
            <p className="flex items-center gap-1 text-caption text-danger-700" role="alert">
              <AlertCircle className="h-3.5 w-3.5 shrink-0" aria-hidden="true" />
              {errors.labelIds.message}
            </p>
          )}
        </div>
      </form>
    </Modal>
  );
}
