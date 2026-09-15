// 포털 라벨링 — 이미지 조절(밝기 · 대비 · 라벨 투명도 · 작업 투명도).
//
// <h3>흐름은 원본 그대로</h3>
// 원본 이미지 조절 판(`ImageAdjustPanel`)과 같은 값을 같은 스토어(`imageAdjust`)에 넣는다 — 캔버스가
// 그 값을 구독해 곧바로 그린다. 범위 · 한 칸 크기도 원본 그대로다(밝기 −1~1 · 대비 −100~100 ·
// 투명도 0~1). 세션 값이라 저장하지 않는다.
//
// <h3>모양 — 포털 저작도구 화면이 정본</h3>
// 포털 화면(KLID_Portal `AuthoringLabelingView` 의 이미지 조절 판)과 같다 — 층 4 도구 판, 이름 줄 끝에
// 「초기화」, 막대 넷, 맨 아래 딸린 안내.

import { Button } from 'krds-react';

import { RangeSlider } from '@portal/pages/workspace/authoring/RangeSlider';
import { ToolPanel } from '@portal/pages/workspace/authoring/ToolPanel';
import { useLabelStore } from '@/stores/useLabelStore';

export function PortalImageAdjust() {
  const imageAdjust = useLabelStore((s) => s.imageAdjust);
  const setImageAdjust = useLabelStore((s) => s.setImageAdjust);
  const resetImageAdjust = useLabelStore((s) => s.resetImageAdjust);

  return (
    <ToolPanel
      level={4}
      title="이미지 조절"
      aside={
        <Button size="small" variant="text" onClick={resetImageAdjust}>
          초기화
        </Button>
      }
      note="기본값은 밝기·대비 0, 투명도 1(원본)입니다. 조절값은 이번 작업에서만 쓰고 저장하지 않습니다."
    >
      <RangeSlider
        label="밝기"
        valueText={String(imageAdjust.brightness)}
        min={-1}
        max={1}
        step={0.05}
        value={imageAdjust.brightness}
        onChange={(v) => setImageAdjust({ brightness: v })}
      />
      <RangeSlider
        label="대비"
        valueText={String(imageAdjust.contrast)}
        min={-100}
        max={100}
        step={5}
        value={imageAdjust.contrast}
        onChange={(v) => setImageAdjust({ contrast: v })}
      />
      <RangeSlider
        label="라벨 투명도"
        valueText={String(imageAdjust.labelOpacity)}
        min={0}
        max={1}
        step={0.05}
        value={imageAdjust.labelOpacity}
        onChange={(v) => setImageAdjust({ labelOpacity: v })}
      />
      <RangeSlider
        label="작업 투명도"
        valueText={String(imageAdjust.activeOpacity)}
        min={0}
        max={1}
        step={0.05}
        value={imageAdjust.activeOpacity}
        onChange={(v) => setImageAdjust({ activeOpacity: v })}
      />
    </ToolPanel>
  );
}
