import { KRDS_FOCUS } from '@/lib/focusRing';
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
export function TargetResolutionSelect({
  value,
  onChange,
  disabled,
}: TargetResolutionSelectProps) {
  const toggle = (preset: ResolutionPreset, checked: boolean) => {
    // 불변성: 새 배열 생성 (mutation 금지).
    const next = checked
      ? [...value, preset]
      : value.filter((p) => p !== preset);
    // 화이트리스트 순서 유지 (RESOLUTION_PRESETS 기준 정렬).
    onChange(RESOLUTION_PRESETS.filter((p) => next.includes(p)));
  };

  return (
    <fieldset
      className="rounded-lg border border-gray-200 bg-white p-4 shadow-sm"
      data-testid="target-resolution-select"
      disabled={disabled}
    >
      <legend className="mb-2 px-1 text-xs font-medium text-gray-500">
        생성할 해상도 선택 (기본 전체)
      </legend>
      <div className="flex flex-wrap gap-x-6 gap-y-2">
        {RESOLUTION_PRESETS.map((p) => {
          const checked = value.includes(p);
          return (
            <div key={p} className="flex items-center gap-2">
              <input
                id={`res-preset-${p}`}
                type="checkbox"
                checked={checked}
                onChange={(e) => toggle(p, e.target.checked)}
                disabled={disabled}
                className="h-4 w-4 rounded border-gray-300 text-primary-600 focus:ring-primary-500"
              />
              <label
                htmlFor={`res-preset-${p}`}
                className={`text-sm ${KRDS_FOCUS} text-gray-800`}
              >
                {RESOLUTION_PRESET_LABEL[p]}
              </label>
            </div>
          );
        })}
      </div>
      {value.length === 0 && (
        <p className="mt-2 text-xs text-warning">
          생성할 해상도를 하나 이상 선택하세요.
        </p>
      )}
      <p className="mt-2 text-xs text-gray-400">
        선택한 해상도별로 새 파생영상이 생성되어 검수 대기 상태로 들어갑니다. 라벨
        좌표는 복사되지 않으며 원본과 동일 해상도는 자동 제외됩니다(SFR-06-03).
      </p>
    </fieldset>
  );
}
