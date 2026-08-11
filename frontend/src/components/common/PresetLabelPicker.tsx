import type { LabelMasterType } from '@/features/label/api/labelMaster';
import { cn } from '@/lib/cn';

import { Badge } from './Badge';
import { Checkbox } from './Checkbox';
import { Field, FieldLabel } from './Field';

/**
 * 프리셋 라벨 다중 선택 목록 (UI-114).
 *
 * 프리셋 편집 모달(UI-091 PresetEditModal)에서 라벨 마스터를 다중 선택하는 체크박스 목록.
 * 행 = 체크박스 + 라벨명 + 형태 읽기전용 배지이고, 목록은 260px 에서 스크롤된다.
 *
 * ⚠ **형태(BBOX/POLYGON…)는 읽기 전용이다** — 형태의 소유자는 라벨 마스터(`LS_LABEL.LBL_TYPE_CD`)이고
 *   프리셋은 `labelId` 로 마스터를 참조할 뿐이다. 여기에 형태 토글을 되살리지 말 것(구
 *   `BBOX_ENABLED`/`POLYGON_ENABLED` 컬럼은 폐기됐다).
 *
 * ⚠ `labelId` 는 **숫자**다(마스터 PK). 프리셋 저장 요청이 `labelIds: number[]` 를 보내므로
 *   문자열로 다루면 서버에서 거부된다.
 *
 * 접근성: 행마다 Field(orientation='horizontal') + FieldLabel 을 써서 라벨과 체크박스를
 * `htmlFor`↔`id` 로 잇고 44px 터치 타깃을 라벨이 만든다(Checkbox 규약).
 */
export interface PresetLabelPickerItem {
  /** 라벨 마스터 PK. */
  labelId: number;
  labelName: string;
  /** 마스터가 소유한 형태. 미연결·형태 없음이면 생략한다. */
  shapeType?: LabelMasterType | null;
  checked: boolean;
}

export interface PresetLabelPickerProps {
  /** 라벨 마스터 활성 목록 + 선택 상태. */
  items: PresetLabelPickerItem[];
  onChange?: (labelId: number, checked: boolean) => void;
  /** 목록 0건일 때 표시할 안내 문구. */
  emptyMessage?: string;
  className?: string;
}

export function PresetLabelPicker({
  items,
  onChange,
  emptyMessage = '선택할 수 있는 라벨이 없습니다.',
  className,
}: PresetLabelPickerProps) {
  const selectedCount = items.filter((i) => i.checked).length;

  return (
    <div className={cn('flex flex-col gap-1', className)}>
      {/* 선택 개수는 굵게 강조한다(UI-114 variants.default) — 한도가 있는 선택이라
          몇 개를 골랐는지가 목록보다 먼저 읽혀야 한다. */}
      <p aria-live="polite" className="text-caption text-gray-600">
        선택 <strong className="font-semibold text-gray-900">{selectedCount}</strong>개 / 전체{' '}
        {items.length}개
      </p>

      {items.length === 0 ? (
        <p
          role="status"
          className="rounded-md border border-border bg-gray-50 px-4 py-3 text-body-sm text-gray-600"
        >
          {emptyMessage}
        </p>
      ) : (
        // 260px 고정 상한 + 스크롤. 라벨 마스터가 수십 건이어도 모달 높이가 밀리지 않는다.
        <div className="max-h-[260px] overflow-y-auto rounded-md border border-border">
          <ul className="divide-y divide-gray-100">
            {items.map((item) => (
              <li key={item.labelId} className="px-3">
                <Field orientation="horizontal">
                  <Checkbox
                    checked={item.checked}
                    onCheckedChange={(next) => onChange?.(item.labelId, next === true)}
                  />
                  <FieldLabel className="min-w-0 flex-1 gap-2">
                    <span className="min-w-0 flex-1 truncate">{item.labelName}</span>
                    {item.shapeType && (
                      <Badge variant="neutral" label={item.shapeType} className="shrink-0" />
                    )}
                  </FieldLabel>
                </Field>
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  );
}

export default PresetLabelPicker;
