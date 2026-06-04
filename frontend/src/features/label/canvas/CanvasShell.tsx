import { useEffect, useMemo, useRef, useState } from 'react';
import { Layer, Stage } from 'react-konva';
import type Konva from 'konva';

import { useLabelStore } from '@/stores/useLabelStore';

import type { FrameSummary, Label } from '../types';
import { ImageLayer } from './layers/ImageLayer';
import { LabelsLayer } from './layers/LabelsLayer';
import { OverlayLayer } from './layers/OverlayLayer';
import { useSam2Segment } from '../hooks/useSam2Segment';
import type { Sam2SegmentResponse } from '../api';
import { buildGeometry } from './utils/canvasGeometry';
import type { Geometry } from './utils/coordinateTransformer';

export interface CanvasShellProps {
  frame: FrameSummary;
  width: number;
  height: number;
  labels: Label[];
  onLabelAdd?: (label: Label) => void;
}

/**
 * react-konva Stage 컨테이너.
 * Layer 분리 원칙: ImageLayer는 imageUrl 변경 시에만 재렌더 (라벨 변경 시 X).
 */
export function CanvasShell({ frame, width, height, labels, onLabelAdd }: CanvasShellProps) {
  const stageRef = useRef<Konva.Stage | null>(null);
  const zoom = useLabelStore((s) => s.zoom);
  const panX = useLabelStore((s) => s.panX);
  const panY = useLabelStore((s) => s.panY);
  const activeTool = useLabelStore((s) => s.activeTool);

  const [imageEl, setImageEl] = useState<HTMLImageElement | null>(null);
  // SAM2 클릭/박스 분할 — 진행 중 무시 + 프레임 전환 stale 폐기 가드 포함.
  const { segment } = useSam2Segment(frame.srcSn);
  const [segNotice, setSegNotice] = useState<string | null>(null);

  function handleMockWarning(_res: Sam2SegmentResponse) {
    setSegNotice('AI 모델 미로드 — 결과 신뢰 불가. 자동 적용이 차단되었습니다.');
  }
  function handleLowConfidence(res: Sam2SegmentResponse) {
    setSegNotice(`낮은 신뢰도(${(res.score * 100).toFixed(0)}%) — 결과를 확인 후 적용하세요.`);
  }

  // 이미지 로드 (XSS: imageUrl은 BE 신뢰 도메인만).
  // 빈 imageUrl이면 로드 시도 생략 — placeholder 배경(bg-bgLight)만 노출.
  useEffect(() => {
    if (!frame.imageUrl) {
      setImageEl(null);
      return;
    }
    let cancelled = false;
    const img = new Image();
    img.crossOrigin = 'anonymous';
    img.onload = () => {
      if (!cancelled) setImageEl(img);
    };
    img.onerror = () => {
      if (!cancelled) setImageEl(null);
    };
    img.src = frame.imageUrl;
    return () => {
      cancelled = true;
    };
  }, [frame.imageUrl]);

  const geometry: Geometry = useMemo(
    () =>
      buildGeometry(
        { width: frame.imageWidth, height: frame.imageHeight },
        { width, height },
        zoom,
        panX,
        panY,
        0,
      ),
    [frame.imageWidth, frame.imageHeight, width, height, zoom, panX, panY],
  );

  return (
    <div className="relative" data-testid="canvas-shell">
      <Stage ref={stageRef} width={width} height={height} className="bg-bgLight">
        <Layer listening={false} name="image-layer">
          {imageEl && <ImageLayer image={imageEl} geometry={geometry} />}
        </Layer>
        <Layer name="labels-layer">
          <LabelsLayer labels={labels} geometry={geometry} />
        </Layer>
        <Layer name="overlay-layer">
          <OverlayLayer
            geometry={geometry}
            activeTool={activeTool}
            onLabelAdd={onLabelAdd}
            stageRef={stageRef}
            segment={segment}
            onMockWarning={handleMockWarning}
            onLowConfidence={handleLowConfidence}
          />
        </Layer>
      </Stage>
      {segNotice && (
        <div
          role="status"
          className="absolute bottom-2 left-2 z-10 rounded bg-warning/90 px-3 py-1 text-xs text-white"
        >
          {segNotice}
        </div>
      )}
    </div>
  );
}
