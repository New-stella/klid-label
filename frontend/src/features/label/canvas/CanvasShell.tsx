import { forwardRef, useEffect, useMemo, useRef, useState } from 'react';
import { Layer, Stage } from 'react-konva';
import type Konva from 'konva';

import { useLabelStore, MIN_ZOOM, MAX_ZOOM } from '@/stores/useLabelStore';

import type { FrameSummary, Label } from '../types';
import { useSam2Segment } from '../hooks/useSam2Segment';
import type { Sam2SegmentResponse } from '../api';

import { ImageLayer } from './layers/ImageLayer';
import { LabelsLayer } from './layers/LabelsLayer';
import { OverlayLayer, type OverlayLayerHandle } from './layers/OverlayLayer';
import { buildGeometry } from './utils/canvasGeometry';
import type { Geometry, Size } from './utils/coordinateTransformer';
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
  /**
   * Phase 9 — 포털 모드. SAM2 분할 요청을 포털 전용 /portal/frames/{id}/sam2-segment 경로로 보낸다
   * (persist 없이 좌표만). 내부 /frames/{id}/sam2-segment 는 PORTAL 채널 403 이므로 포털에서 호출 금지.
   */
  portalMode?: boolean;
  /**
   * 로드된 프레임 이미지의 실측 네이티브 픽셀 크기 통지. 상위(LabelingPage)가 이 값을
   * 수치 좌표 편집(ObjectAttributePanel)·붙여넣기 clamp 등 형제 경로에 배선해 좌표 기준을
   * 캔버스 geometry 와 동일한 실측 크기로 통일한다. 이미지 미로드 시 미호출(상위는 undefined 유지).
   */
  onImageSize?: (width: number, height: number) => void;
}

// 상위(LabelingPage)가 키보드 단축키(F/Q)로 폴리곤 편집을 명령할 수 있도록 OverlayLayer 의
// imperative handle 을 그대로 재-노출한다. 편집 state 는 OverlayLayer 내부에 캡슐화된 채 유지된다.
export type { OverlayLayerHandle } from './layers/OverlayLayer';

// 휠 줌 한계는 스토어(useLabelStore)의 MIN_ZOOM/MAX_ZOOM 을 그대로 재사용 —
// 상수 중복 정의로 인한 정책 드리프트 방지(fit=1.0 바닥, 8 상한).
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
    portalMode = false,
    onImageSize,
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
  const { segment } = useSam2Segment(frame.srcSn, portalMode);
  const [segNotice, setSegNotice] = useState<string | null>(null);

  function handleMockWarning(res: Sam2SegmentResponse) {
    // BE ApiResponse.message(고정 상수)를 우선 노출. 미제공 시 폴백 문구. (텍스트 렌더 — XSS 무관)
    setSegNotice(res.message ?? 'AI 모델 미로드 — 결과 신뢰 불가. 자동 적용이 차단되었습니다.');
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

  // 캔버스 geometry 의 이미지 기준 크기 = 로드된 프레임 이미지의 실측 네이티브 픽셀(naturalWidth/Height).
  // YOLO 오토라벨·수동 드로잉 좌표 모두 프레임 JPEG 네이티브 픽셀 기준이므로, 렌더 기준을 실측
  // 네이티브로 맞춰야 좌표계가 일치하고 이미지 스트레치(왜곡)도 사라진다. (구: FrameSummary 의
  // 하드코딩 1920×1080 사용 → 실제 해상도가 다르면 라벨이 어긋나던 버그)
  const imageSize = useMemo<Size | null>(() => {
    if (!imageEl) return null;
    const w = imageEl.naturalWidth;
    const h = imageEl.naturalHeight;
    // 로드 완료 전/실패 시 naturalWidth/Height 는 0 → geometry 산출 보류(타이밍 가드).
    if (w <= 0 || h <= 0) return null;
    return { width: w, height: h };
  }, [imageEl]);

  // 실측 크기 미확정(이미지 미로드) 시 geometry=null → 라벨/오버레이 레이어 렌더를 보류해
  // 0-division·잘못된 초기 배치를 차단한다. 이미지 로드 완료 후 정상 산출.
  const geometry = useMemo<Geometry | null>(() => {
    if (!imageSize) return null;
    return buildGeometry(imageSize, { width, height }, zoom, panX, panY, 0);
  }, [imageSize, width, height, zoom, panX, panY]);

  // 실측 크기 확정 시 상위로 통지 → 수치 편집·붙여넣기 경로가 동일 실측 dims 를 공유.
  useEffect(() => {
    if (imageSize) onImageSize?.(imageSize.width, imageSize.height);
  }, [imageSize, onImageSize]);

  // R17 이슈6: 마우스 휠 줌 — 커서 위치 중심(zoom-to-point). 페이지 스크롤 방지.
  function handleWheel(e: Konva.KonvaEventObject<WheelEvent>) {
    e.evt.preventDefault();
    if (!geometry) return; // 이미지 미로드 시 줌 무시(geometry 미확정)
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
          {geometry && imageEl && (
            <ImageLayer image={imageEl} geometry={geometry} adjust={imageAdjust} />
          )}
        </Layer>
        <Layer name="labels-layer" opacity={imageAdjust.labelOpacity}>
          {/* 타이밍 가드 — geometry(실측 크기) 확정 전에는 라벨을 그리지 않는다. */}
          {geometry && <LabelsLayer labels={labels} geometry={geometry} readOnly={readOnly} />}
        </Layer>
        <Layer name="overlay-layer" opacity={imageAdjust.activeOpacity}>
          {geometry && (
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
          )}
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
