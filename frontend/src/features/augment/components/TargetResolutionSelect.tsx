import { Field, FieldLabel } from '@/components/common/Field';
import { Checkbox } from '@/components/common/Checkbox';
import {
  RESOLUTION_PRESETS,
  RESOLUTION_PRESET_LABEL,
  type ResolutionPreset,
} from '@/features/video/types';

export interface TargetResolutionSelectProps {
  /** 생성할 해상도 프리셋 목록 (기본 전체 3종). */
  value: ResolutionPreset[];
  onChange: (presets: ResolutionPreset[]) => void;
  disabled?: boolean;
}

/**
 * 생성할 해상도 선택 UI — SCR-AUG-001 통합 화면(RESOLUTION 종류 전용).
 *
 * <p>SFR-06-03 은 "1080/720/480 3종 고정" 이므로 기본 UX 는 단일 선택이 아니라
 * 3종 파생영상 생성 트리거다. 프리셋은 다중 선택(체크박스)이며 기본 전체 선택,
 * 원본과 동일 해상도만 BE 가 스킵한다. 선택된 프리셋으로 목표 해상도별 새 파생영상을
 * 만들어 검수 파이프라인(검수 대기)에 넣는다.
 *
 * 보안: preset 은 RESOLUTION_PRESETS allowlist 로만 좁혀 임의 문자열 분기를 차단한다.
 */
export function TargetResolutionSelect({ value, onChange, disabled }: TargetResolutionSelectProps) {
  const toggle = (preset: ResolutionPreset, checked: boolean) => {
    // 불변성: 새 배열 생성 (mutation 금지).
    const next = checked ? [...value, preset] : value.filter((p) => p !== preset);
    // 화이트리스트 순서 유지 (RESOLUTION_PRESETS 기준 정렬).
    onChange(RESOLUTION_PRESETS.filter((p) => next.includes(p)));
  };

  return (
    <fieldset
      className="rounded-lg border border-gray-200 bg-white p-4 shadow-sm"
      data-testid="target-resolution-select"
      disabled={disabled}
    >
      <legend className="mb-2 px-1 text-label font-medium text-gray-500">
        생성할 해상도 선택 (기본 전체)
      </legend>
      <div className="flex flex-wrap gap-x-6 gap-y-2">
        {RESOLUTION_PRESETS.map((p) => {
          const checked = value.includes(p);
          return (
            <Field key={p} orientation="horizontal">
              <Checkbox
                id={`res-preset-${p}`}
                checked={checked}
                onCheckedChange={(v) => toggle(p, v === true)}
                disabled={disabled}
              />
              <FieldLabel>{RESOLUTION_PRESET_LABEL[p]}</FieldLabel>
            </Field>
          );
        })}
      </div>
      {value.length === 0 && (
        <p className="mt-2 text-caption text-warning">생성할 해상도를 하나 이상 선택하세요.</p>
      )}
      <p className="mt-2 text-caption text-gray-400">
        선택한 해상도별로 새 파생영상이 생성되어 검수 대기 상태로 들어갑니다. 라벨 좌표는 목표
        해상도 배율로 재계산되어 함께 적용되며, 원본과 동일 해상도는 자동 제외됩니다.
      </p>
    </fieldset>
  );
}
