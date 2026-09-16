// SCR-LABEL-001 이미지 조절 패널 (Phase 2c).
// 밝기/대비 → konva 이미지 필터, 투명도 → 레이어 opacity. 세션 전용(영속 안 함).
// 포털 노출 대상 — 포털 게이팅 없음.
// 보안(XSS): 슬라이더/토글 값은 숫자로 스토어에만 반영, DOM innerHTML 렌더 없음(konva 캔버스).

import { Button as KrdsButton } from 'krds-react';

import { useLabelStore, type ImageAdjust } from '@/stores/useLabelStore';

// ★포털 채널 전용 — 부모 포털 시안이 쓰는 킷 부품. 관제 렌더 경로는 거치지 않는다.
import { RangeSlider, ToolPanel } from '@/components/portal/authoring';

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
    <label className="flex flex-col gap-1 text-label text-gray-700">
      <span className="flex justify-between">
        <span>{label}</span>
        <span className="tabular-nums text-gray-500">{value}</span>
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
 * 슬라이더 넷의 **공통 정의** — 두 채널이 같은 목록·같은 범위를 쓴다.
 *
 * ★범위·간격은 채널을 가리지 않는다 — 이 값들은 캔버스 필터 계약이다(밝기는 konva 가 −1~1 로
 *   받고 대비는 −100~100 이다). 시안이 밝기를 −100~100 으로 보여 준다고 여기서 눈금을 바꾸면
 *   <b>같은 손잡이 위치가 다른 보정 세기</b>가 된다. 갈리는 것은 <b>부품</b>뿐이다.
 */
function sliderDefs(adjust: ImageAdjust, set: (patch: Partial<ImageAdjust>) => void) {
  return [
    {
      key: 'brightness',
      label: '밝기',
      value: adjust.brightness,
      min: -1,
      max: 1,
      step: 0.05,
      onChange: (v: number) => set({ brightness: v }),
    },
    {
      key: 'contrast',
      label: '대비',
      value: adjust.contrast,
      min: -100,
      max: 100,
      step: 5,
      onChange: (v: number) => set({ contrast: v }),
    },
    {
      key: 'labelOpacity',
      label: '라벨 투명도',
      value: adjust.labelOpacity,
      min: 0,
      max: 1,
      step: 0.05,
      onChange: (v: number) => set({ labelOpacity: v }),
    },
    {
      key: 'activeOpacity',
      label: '작업 투명도',
      value: adjust.activeOpacity,
      min: 0,
      max: 1,
      step: 0.05,
      onChange: (v: number) => set({ activeOpacity: v }),
    },
  ];
}

/** 손잡이 값 글 — 0.05 칸이라 떠도는 소수(0.30000000000000004)가 그대로 보이면 안 된다. */
function valueText(v: number): string {
  return Number.isInteger(v) ? String(v) : String(Number(v.toFixed(2)));
}

const ADJUST_NOTE = '기본값 밝기·대비 0, 투명도 1 (원본). 조절값은 세션 전용으로 저장되지 않습니다.';

/**
 * 이미지 조절 패널 — 밝기/대비/라벨 투명도/작업 투명도 슬라이더 + 초기화.
 * 상태는 useLabelStore.imageAdjust(세션) 에 저장되고 캔버스 레이어가 이를 구독해 실시간 반영한다.
 *
 * ★생김새가 채널마다 갈린다 — 포털은 시안대로 킷 판(`ToolPanel`) + 킷 막대(`RangeSlider`)이고
 *   관제는 종전 네이티브 막대 그대로다. 종전에는 두 채널이 같은 날 막대를 써서, 포털 화면에서
 *   이 자리만 «되다 만» 것처럼 보였다(2026-09-16 사용자 지적).
 * ★<b>배선·범위·문구는 두 채널이 같다</b> — 갈리는 것은 부품뿐이다.
 */
export function ImageAdjustPanel({ portalMode = false }: { portalMode?: boolean }) {
  const imageAdjust = useLabelStore((s) => s.imageAdjust);
  const setImageAdjust = useLabelStore((s) => s.setImageAdjust);
  const resetImageAdjust = useLabelStore((s) => s.resetImageAdjust);

  const defs = sliderDefs(imageAdjust, setImageAdjust);

  if (portalMode) {
    return (
      <ToolPanel
        level={4}
        title="이미지 조절"
        aside={
          <KrdsButton size="small" variant="text" onClick={resetImageAdjust}>
            초기화
          </KrdsButton>
        }
        note={ADJUST_NOTE}
      >
        {defs.map((d) => (
          <RangeSlider
            key={d.key}
            label={d.label}
            valueText={valueText(d.value)}
            min={d.min}
            max={d.max}
            step={d.step}
            value={d.value}
            onChange={d.onChange}
          />
        ))}
      </ToolPanel>
    );
  }

  return (
    <section
      className="flex flex-col gap-3 rounded border border-gray-200 bg-white p-3"
      aria-label="이미지 조절"
    >
      <div className="flex items-center justify-between">
        <h3 className="text-title-sm font-semibold text-gray-900">이미지 조절</h3>
        <button
          type="button"
          onClick={resetImageAdjust}
          className="rounded border border-gray-300 bg-white px-2 py-0.5 text-caption text-gray-700 hover:bg-gray-50"
        >
          초기화
        </button>
      </div>

      {defs.map((d) => (
        <AdjustSlider
          key={d.key}
          label={d.label}
          value={d.value}
          min={d.min}
          max={d.max}
          step={d.step}
          onChange={d.onChange}
        />
      ))}

      <p className="text-[10px] leading-tight text-gray-500">{ADJUST_NOTE}</p>
    </section>
  );
}
