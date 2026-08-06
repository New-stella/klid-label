import { Button } from '@/components/common/Button';
import { Input } from '@/components/common/Input';
import { Modal } from '@/components/common/Modal';
import { Select, type SelectOption } from '@/components/common/Select';
import {
  LABEL_MASTER_TYPES,
  type LabelMaster,
  type LabelMasterType,
} from '@/features/label/api/labelMaster';
import { COCO_CLASSES } from '@/features/label/constants/cocoClasses';
import { TYPE_LABEL } from '@/features/label/constants/labelTypes';
import { KRDS_FOCUS } from '@/lib/focusRing';

/**
 * 라벨 마스터 생성/수정 폼 모달 — LabelMasterManagePage 에서 추출(component.md 400줄 분리).
 *
 * 상태(폼 값/에러/제출)는 부모가 소유하고, 이 컴포넌트는 표현 + 입력 위임만 담당한다.
 * 접근성·마크업(role=dialog, label htmlFor, role=alert)은 추출 전과 동일하게 유지한다.
 * 형태 표기(TYPE_LABEL)는 label 피처 상수로 이동해 화면 간 공유한다.
 */

const HEX_COLOR_RE = /^#[0-9A-Fa-f]{6}$/;
const DEFAULT_COLOR = '#3B82F6';

export interface LabelMasterForm {
  name: string;
  type: LabelMasterType;
  color: string;
  sortNo: number;
  /** AI(COCO) 검출 클래스 매핑 — 미지정(미매핑)이면 null. BE `dtctTypeCd`(DTCT_TYPE_CD). */
  dtctTypeCd: string | null;
}

/** COCO 미지정(미매핑) select 값 — 빈 문자열을 sentinel 로 쓰고 저장 시 null 로 환원. */
const NO_COCO = '';

const TYPE_OPTIONS: SelectOption[] = LABEL_MASTER_TYPES.map((t) => ({
  value: t,
  label: TYPE_LABEL[t],
}));

const COCO_OPTIONS: SelectOption[] = [
  { value: NO_COCO, label: '미지정 (AI 탐지 미사용)' },
  ...COCO_CLASSES.map((c) => ({ value: c.id, label: c.label })),
];

export interface FormErrors {
  name?: string;
  color?: string;
  sortNo?: string;
}

export function emptyForm(sortNo: number): LabelMasterForm {
  return { name: '', type: 'BBOX', color: DEFAULT_COLOR, sortNo, dtctTypeCd: null };
}

export function toForm(m: LabelMaster): LabelMasterForm {
  return {
    name: m.name,
    type: m.type,
    color: m.color,
    sortNo: m.sortNo,
    dtctTypeCd: m.dtctTypeCd ?? null,
  };
}

/** 클라이언트 사전검증 — name 필수, color HEX 형식, sortNo 정수. */
export function validate(form: LabelMasterForm): FormErrors {
  const errors: FormErrors = {};
  if (!form.name.trim()) errors.name = '라벨명을 입력하세요.';
  else if (form.name.length > 50) errors.name = '라벨명은 50자 이하로 입력하세요.';
  if (!HEX_COLOR_RE.test(form.color)) errors.color = '색상은 #RRGGBB 형식으로 입력하세요.';
  if (!Number.isInteger(form.sortNo) || form.sortNo < 0) {
    errors.sortNo = '정렬 순서는 0 이상의 정수여야 합니다.';
  }
  return errors;
}

interface LabelMasterFormModalProps {
  open: boolean;
  isEditing: boolean;
  form: LabelMasterForm;
  errors: FormErrors;
  submitError: string | null;
  submitting: boolean;
  onClose: () => void;
  onSubmit: () => void;
  onPatch: (patch: Partial<LabelMasterForm>) => void;
}

export function LabelMasterFormModal({
  open,
  isEditing,
  form,
  errors,
  submitError,
  submitting,
  onClose,
  onSubmit,
  onPatch,
}: LabelMasterFormModalProps) {
  return (
    <Modal
      open={open}
      onClose={onClose}
      title={isEditing ? '라벨 수정' : '라벨 추가'}
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
          label="라벨명"
          value={form.name}
          onChange={(e) => onPatch({ name: e.target.value })}
          error={errors.name}
          maxLength={50}
          placeholder="예: 사람, 차량"
        />

        <Select
          id="label-master-type"
          label="형태"
          value={form.type}
          onChange={(e) => onPatch({ type: e.target.value as LabelMasterType })}
          options={TYPE_OPTIONS}
        />

        <Select
          id="label-master-coco"
          label="AI 탐지 클래스 (선택)"
          value={form.dtctTypeCd ?? NO_COCO}
          onChange={(e) =>
            onPatch({ dtctTypeCd: e.target.value === NO_COCO ? null : e.target.value })
          }
          options={COCO_OPTIONS}
          hint="AI 탐지 결과를 이 라벨로 자동 연결합니다. 한 클래스는 하나의 라벨에만 매핑됩니다."
        />

        <div className="flex flex-col gap-1">
          <label htmlFor="label-master-color" className="text-body font-medium text-gray-700">
            색상
          </label>
          <div className="flex items-start gap-2">
            {/* 색상 피커(type="color")는 공통 Input 이 표현하지 못하는 네이티브 컨트롤이라 유지한다. */}
            <input
              id="label-master-color-picker"
              type="color"
              aria-label="색상 선택기"
              value={HEX_COLOR_RE.test(form.color) ? form.color : DEFAULT_COLOR}
              onChange={(e) => onPatch({ color: e.target.value.toUpperCase() })}
              className={`h-11 w-12 shrink-0 rounded-lg border border-gray-300 ${KRDS_FOCUS}`}
            />
            <div className="min-w-0 flex-1">
              <Input
                id="label-master-color"
                type="text"
                aria-label="색상"
                value={form.color}
                onChange={(e) => onPatch({ color: e.target.value })}
                placeholder="#RRGGBB"
                error={errors.color}
                className="tabular-nums"
              />
            </div>
          </div>
        </div>

        <Input
          id="label-master-sort"
          label="정렬 순서"
          type="number"
          min={0}
          value={form.sortNo}
          onChange={(e) => onPatch({ sortNo: Number(e.target.value) })}
          error={errors.sortNo}
          className="tabular-nums"
        />

        {submitError && (
          <p role="alert" className="rounded-md bg-danger/10 px-3 py-2 text-body-md text-danger">
            {submitError}
          </p>
        )}
      </div>
    </Modal>
  );
}
