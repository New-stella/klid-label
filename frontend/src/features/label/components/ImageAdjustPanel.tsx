// SCR-LABEL-001 이미지 조절 패널 (Phase 2c).
// 밝기/대비 → konva 이미지 필터, 투명도 → 레이어 opacity. 세션 전용(영속 안 함).
// 포털 노출 대상 — 포털 게이팅 없음.
// 보안(XSS): 슬라이더/토글 값은 숫자로 스토어에만 반영, DOM innerHTML 렌더 없음(konva 캔버스).

import { useLabelStore } from '@/stores/useLabelStore';

interface AdjustSliderProps {
  label: string;
  value: number;
  min: number;
  max: number;
  step: number;
  onChange: (v: number) => void;
}

function AdjustSlider({ label, value, min, max, step, onChange }: AdjustSliderProps) {
  return (
    <label className="flex flex-col gap-1 text-xs text-gray-200">
      <span className="flex justify-between">
        <span>{label}</span>
        <span className="tabular-nums text-gray-400">{value}</span>
      </span>
      <input
        type="range"
        aria-label={label}
        min={min}
        max={max}
        step={step}
        value={value}
        onChange={(e) => onChange(Number(e.target.value))}
        className="w-full accent-primary-500"
      />
    </label>
  );
}

/**
 * 이미지 조절 패널 — 밝기/대비/라벨 투명도/작업 투명도 슬라이더 + 초기화.
 * 상태는 useLabelStore.imageAdjust(세션) 에 저장되고 캔버스 레이어가 이를 구독해 실시간 반영한다.
 */
export function ImageAdjustPanel() {
  const imageAdjust = useLabelStore((s) => s.imageAdjust);
  const setImageAdjust = useLabelStore((s) => s.setImageAdjust);
  const resetImageAdjust = useLabelStore((s) => s.resetImageAdjust);

  return (
    <section
      className="flex flex-col gap-3 rounded bg-gray-800 p-3"
      aria-label="이미지 조절"
    >
      <div className="flex items-center justify-between">
        <h3 className="text-sm font-semibold text-gray-100">이미지 조절</h3>
        <button
          type="button"
          onClick={resetImageAdjust}
          className="rounded bg-gray-700 px-2 py-0.5 text-xs text-gray-200 hover:bg-gray-600"
        >
          초기화
        </button>
      </div>

      <AdjustSlider
        label="밝기"
        value={imageAdjust.brightness}
        min={-1}
        max={1}
        step={0.05}
        onChange={(v) => setImageAdjust({ brightness: v })}
      />
      <AdjustSlider
        label="대비"
        value={imageAdjust.contrast}
        min={-100}
        max={100}
        step={5}
        onChange={(v) => setImageAdjust({ contrast: v })}
      />
      <AdjustSlider
        label="라벨 투명도"
        value={imageAdjust.labelOpacity}
        min={0}
        max={1}
        step={0.05}
        onChange={(v) => setImageAdjust({ labelOpacity: v })}
      />
      <AdjustSlider
        label="작업 투명도"
        value={imageAdjust.activeOpacity}
        min={0}
        max={1}
        step={0.05}
        onChange={(v) => setImageAdjust({ activeOpacity: v })}
      />

      <p className="text-[10px] leading-tight text-gray-500">
        기본값 밝기·대비 0, 투명도 1 (원본). 조절값은 세션 전용으로 저장되지 않습니다.
      </p>
    </section>
  );
}
