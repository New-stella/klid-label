import {
  RESOLUTION_PRESETS,
  RESOLUTION_PRESET_LABEL,
  type ResolutionPreset,
} from '@/features/video/types';

export interface TargetResolutionSelectProps {
  value: ResolutionPreset | null;
  onChange: (preset: ResolutionPreset) => void;
  disabled?: boolean;
}

/**
 * 해상도 변경 타겟 해상도 선택 UI — SCR-AUG-001 통합 화면(RESOLUTION 종류 전용).
 *
 * Phase 1 범위: 타겟 해상도(preset) 선택 상태만 관리한다. 실제 변환 실행/결과는
 * Phase 2 에서 부모(submit 분기)가 처리한다. preset 은 RESOLUTION_PRESETS allowlist
 * 로만 좁혀 임의 문자열 분기를 차단한다.
 */
export function TargetResolutionSelect({
  value,
  onChange,
  disabled,
}: TargetResolutionSelectProps) {
  const handleChange = (e: React.ChangeEvent<HTMLSelectElement>) => {
    const raw = e.target.value;
    // allowlist 검증 — raw string 으로 먼저 확인 후 캐스트 (임의 문자열 차단).
    if ((RESOLUTION_PRESETS as readonly string[]).includes(raw)) {
      onChange(raw as ResolutionPreset);
    }
  };

  return (
    <div
      className="rounded-lg border border-gray-200 bg-white p-4 shadow-sm"
      data-testid="target-resolution-select"
    >
      <label
        htmlFor="target-resolution"
        className="mb-1 block text-xs font-medium text-gray-500"
      >
        목표 해상도 선택
      </label>
      <select
        id="target-resolution"
        value={value ?? ''}
        onChange={handleChange}
        disabled={disabled}
        className="max-w-md rounded-md border border-gray-300 bg-white px-3 py-2 text-sm text-gray-800 focus:outline-none focus:ring-2 focus:ring-primary-500"
      >
        <option value="" disabled>
          해상도를 선택하세요
        </option>
        {RESOLUTION_PRESETS.map((p) => (
          <option key={p} value={p}>
            {RESOLUTION_PRESET_LABEL[p]}
          </option>
        ))}
      </select>
      <p className="mt-2 text-xs text-gray-400">
        라벨 좌표는 제공되지 않으며 새 영상은 생성되지 않습니다(SFR-06-03).
      </p>
    </div>
  );
}
