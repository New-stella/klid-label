// Phase 2 [FE] — 라벨링 화면 AI 정밀도 조절 슬라이더 (공용).
//
// 시스템 설정 "라벨링 정밀도" 카드(PrecisionConfigCard)의 문구·눈금·설명을 그대로 재사용해
// AI 탐지 모달(AiToolModal)과 AI 분할 도구(ObjectAttributePanel) 두 소비처에서 공유한다.
//
// 용어 정책(MED #13): 사용자 노출 문구에 모델명(YOLO/SAM/SAM2) 금지 — "인식 민감도"/"경계 세밀함"만 사용.
// 보안: 값은 range input 의 min/max/step 으로 1차 클램프(FE 방어), BE @Valid 로 2차 검증.
// 접근성: aria-label 로 슬라이더 이름 부여(role=slider).

/** 인식 민감도(conf) 범위 — BE confThreshold @DecimalMin/@DecimalMax 와 정합(0.25~0.80). */
export const SENSITIVITY_MIN = 0.25;
export const SENSITIVITY_MAX = 0.8;
export const SENSITIVITY_STEP = 0.01;
export const SENSITIVITY_DEFAULT = 0.25;

/** 경계 세밀함(simplify tolerance) 범위 — BE simplifyTolerance @DecimalMin/@DecimalMax 와 정합(0~50px). */
export const TOLERANCE_MIN = 0;
export const TOLERANCE_MAX = 50;
export const TOLERANCE_STEP = 0.5;
export const TOLERANCE_DEFAULT = 1;

/** 범위 밖 값 클램프(FE 1차 방어). */
function clamp(v: number, min: number, max: number): number {
  if (Number.isNaN(v)) return min;
  return Math.min(max, Math.max(min, v));
}

interface SliderProps {
  id: string;
  /** 현재 값(controlled). */
  value: number;
  onChange: (value: number) => void;
  /** 조절 차단(장시간 작업 진행 중 등) — 슬라이더를 비활성화한다. */
  disabled?: boolean;
}

/**
 * 인식 민감도 슬라이더 (conf 0.25~0.80).
 * 문구·눈금은 PrecisionConfigCard 와 동일. 값은 0~1 실수를 그대로 다룬다.
 */
export function SensitivitySlider({
  id,
  value,
  onChange,
  disabled = false,
}: SliderProps) {
  return (
    <div className="space-y-1.5">
      <label className="flex items-center justify-between text-label" htmlFor={id}>
        <span className="font-medium text-gray-700">인식 민감도</span>
        <span className="font-semibold tabular-nums text-primary-600">{value.toFixed(2)}</span>
      </label>
      <input
        id={id}
        type="range"
        aria-label="인식 민감도"
        min={SENSITIVITY_MIN}
        max={SENSITIVITY_MAX}
        step={SENSITIVITY_STEP}
        value={value}
        disabled={disabled}
        onChange={(e) => onChange(clamp(Number(e.target.value), SENSITIVITY_MIN, SENSITIVITY_MAX))}
        className="h-2 w-full cursor-pointer appearance-none rounded-full bg-gray-300 accent-primary-600 disabled:cursor-not-allowed disabled:opacity-50"
      />
      <div className="flex justify-between text-caption text-gray-500">
        <span>낮음 (0.25)</span>
        <span>높음 (0.80)</span>
      </div>
      <p className="text-caption text-gray-500">
        값이 높을수록 확신도가 높은 객체만 인식해 오탐이 줄지만 놓치는 객체가 늘 수 있습니다.
      </p>
    </div>
  );
}

/**
 * 경계 세밀함 슬라이더 (simplify tolerance 0~50px).
 * 문구·눈금은 PrecisionConfigCard 와 동일. 폴리곤 경계 단순화 정도(Douglas-Peucker epsilon).
 */
export function ToleranceSlider({
  id,
  value,
  onChange,
  disabled = false,
}: SliderProps) {
  return (
    <div className="space-y-1.5">
      <label className="flex items-center justify-between text-label" htmlFor={id}>
        <span className="font-medium text-gray-700">경계 세밀함</span>
        <span className="font-semibold tabular-nums text-primary-600">{value.toFixed(1)}px</span>
      </label>
      <input
        id={id}
        type="range"
        aria-label="경계 세밀함"
        min={TOLERANCE_MIN}
        max={TOLERANCE_MAX}
        step={TOLERANCE_STEP}
        value={value}
        disabled={disabled}
        onChange={(e) => onChange(clamp(Number(e.target.value), TOLERANCE_MIN, TOLERANCE_MAX))}
        className="h-2 w-full cursor-pointer appearance-none rounded-full bg-gray-300 accent-primary-600 disabled:cursor-not-allowed disabled:opacity-50"
      />
      <div className="flex justify-between text-caption text-gray-500">
        <span>세밀 (0.0)</span>
        <span>거침 (50.0)</span>
      </div>
      <p className="text-caption text-gray-500">
        값이 작을수록 폴리곤 경계가 원본에 가깝게 세밀해지고(점 수 증가), 클수록 경계가 단순해져 점 수가
        줄어듭니다. (0~50px)
      </p>
    </div>
  );
}
