import { forwardRef, useEffect, useMemo, useRef, useState } from 'react';
import { Layer, Stage } from 'react-konva';
import type Konva from 'konva';

import { useLabelStore } from '@/stores/useLabelStore';

import type { FrameSummary, Label } from '../types';
import { useSam2Segment } from '../hooks/useSam2Segment';
import type { Sam2SegmentResponse } from '../api';

import { ImageLayer } from './layers/ImageLayer';
import { LabelsLayer } from './layers/LabelsLayer';
import { OverlayLayer, type OverlayLayerHandle } from './layers/OverlayLayer';
import { buildGeometry } from './utils/canvasGeometry';
import type { Geometry } from './utils/coordinateTransformer';
import { zoomToPoint } from './utils/zoomToPoint';

export interface CanvasShellProps {
  frame: FrameSummary;
  width: number;
  height: number;
  labels: Label[];
  onLabelAdd?: (label: Label) => void;
  /** 편집 잠금 (작업락/포털 읽기 제약 등) — LabelsLayer 이동/리사이즈 비활성. */
  readOnly?: boolean;
  /**
   * KEYPOINT 순차 배치 진행 인덱스(0~16) 변경 보고 — 미진행/완료 시 null.
   * 가이드는 좌측 라벨 패널에서 렌더하므로 CanvasShell 은 OverlayLayer 의 보고를
   * 상위(LabelingPage)로 그대로 전달만 한다(state 리프팅).
   */
  onKeypointPlacingChange?: (placingIndex: number | null) => void;
}

// 상위(LabelingPage)가 키보드 단축키(F/Q)로 폴리곤 편집을 명령할 수 있도록 OverlayLayer 의
// imperative handle 을 그대로 재-노출한다. 편집 state 는 OverlayLayer 내부에 캡슐화된 채 유지된다.
export type { OverlayLayerHandle } from './layers/OverlayLayer';

// 스토어 clampZoom 과 동일 한계 — 휠 줌도 같은 범위로 제한.
const MIN_ZOOM = 0.1;
const MAX_ZOOM = 8;
// 휠 한 틱당 줌 배율.
const WHEEL_ZOOM_FACTOR = 1.1;

/**
 * react-konva Stage 컨테이너.
 * Layer 분리 원칙: ImageLayer는 imageUrl 변경 시에만 재렌더 (라벨 변경 시 X).
 */
export const CanvasShell = forwardRef<OverlayLayerHandle, CanvasShellProps>(function CanvasShell(
  {
    frame,
    width,
    height,
    labels,
    onLabelAdd,
    readOnly = false,
    onKeypointPlacingChange,
  }: CanvasShellProps,
  ref,
) {
  const stageRef = useRef<Konva.Stage | null>(null);
  const zoom = useLabelStore((s) => s.zoom);
  const panX = useLabelStore((s) => s.panX);
  const panY = useLabelStore((s) => s.panY);
  const activeTool = useLabelStore((s) => s.activeTool);
  const setZoom = useLabelStore((s) => s.setZoom);
  const setPan = useLabelStore((s) => s.setPan);
  // Phase 2c — 이미지 조절(밝기/대비 필터 + 레이어 투명도). 세션 전용 상태.
  const imageAdjust = useLabelStore((s) => s.imageAdjust);

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
  // 폴리곤 커밋 실패(라벨 마스터 미로딩 등) — 그린 점은 유지되며 사용자에게 원인 안내.
  function handleCommitError(message: string) {
    setSegNotice(message);
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

  // R17 이슈6: 마우스 휠 줌 — 커서 위치 중심(zoom-to-point). 페이지 스크롤 방지.
  function handleWheel(e: Konva.KonvaEventObject<WheelEvent>) {
    e.evt.preventDefault();
    const pointer = e.target.getStage()?.getPointerPosition();
    if (!pointer) return;
    const factor = e.evt.deltaY < 0 ? WHEEL_ZOOM_FACTOR : 1 / WHEEL_ZOOM_FACTOR;
    const next = zoomToPoint(geometry, pointer, factor, MIN_ZOOM, MAX_ZOOM);
    setZoom(next.zoom);
    setPan(next.panX, next.panY);
  }

  return (
    <div className="relative" data-testid="canvas-shell">
      <Stage ref={stageRef} width={width} height={height} className="bg-bgLight" onWheel={handleWheel}>
        <Layer listening={false} name="image-layer">
          {imageEl && <ImageLayer image={imageEl} geometry={geometry} adjust={imageAdjust} />}
        </Layer>
        <Layer name="labels-layer" opacity={imageAdjust.labelOpacity}>
          <LabelsLayer labels={labels} geometry={geometry} readOnly={readOnly} />
        </Layer>
        <Layer name="overlay-layer" opacity={imageAdjust.activeOpacity}>
          <OverlayLayer
            ref={ref}
            geometry={geometry}
            activeTool={activeTool}
            onLabelAdd={onLabelAdd}
            stageRef={stageRef}
            segment={segment}
            onMockWarning={handleMockWarning}
            onLowConfidence={handleLowConfidence}
            onCommitError={handleCommitError}
            onKeypointPlacingChange={onKeypointPlacingChange}
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
});
