import { forwardRef, useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Layer, Stage } from 'react-konva';
import type Konva from 'konva';

import { Spinner } from '@/components/common/Spinner';
import {
  useIsEditBlocked,
  useLabelStore,
  MIN_ZOOM,
  MAX_ZOOM,
  clampPan,
} from '@/stores/useLabelStore';

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
   * 로드된 프레임 이미지의 실측 네이티브 픽셀 크기 통지. 상위(LabelingPage)가 이 값을
   * 수치 좌표 편집(ObjectAttributePanel)·붙여넣기 clamp 등 형제 경로에 배선해 좌표 기준을
   * 캔버스 geometry 와 동일한 실측 크기로 통일한다. 이미지 미로드 시 미호출(상위는 undefined 유지).
   */
  onImageSize?: (width: number, height: number) => void;
  /**
   * "즉시 그리기" 토글 — AI 분할(SAM_SEGMENT) 클릭마다 즉시 미리보기를 그린다.
   * OverlayLayer 의 immediateSegment 로 그대로 중계한다. 기본 OFF(false).
   */
  immediateSegment?: boolean;
  /**
   * (Phase 2 FE) AI 분할 경계 세밀함 — 이 값이 있으면 모든 분할 요청 payload 에 simplifyTolerance 를
   * 주입한다(사용자 조절 값). undefined 면 미주입 → BE 가 시스템 설정 기본값을 사용한다(무회귀).
   */
  segmentSimplifyTolerance?: number;
}

// 상위(LabelingPage)가 키보드 단축키(F/Q)로 폴리곤 편집을 명령할 수 있도록 OverlayLayer 의
// imperative handle 을 그대로 재-노출한다. 편집 state 는 OverlayLayer 내부에 캡슐화된 채 유지된다.
export type { OverlayLayerHandle } from './layers/OverlayLayer';

// 휠 줌 한계는 스토어(useLabelStore)의 MIN_ZOOM/MAX_ZOOM 을 그대로 재사용 —
// 상수 중복 정의로 인한 정책 드리프트 방지(fit=1.0 바닥, 8 상한).
// 휠 한 틱당 줌 배율.
const WHEEL_ZOOM_FACTOR = 1.1;

/** Space/Enter 가 **기본 활성화**를 일으키는 태그. 여기서 팬 홀드가 키를 삼키면 안 된다. */
const KEYBOARD_ACTIVATABLE_TAGS = new Set(['BUTTON', 'INPUT', 'TEXTAREA', 'SELECT', 'SUMMARY']);
/** 위와 같은 기대를 갖는 ARIA 위젯 역할(APG — Space 로 조작되는 컨트롤). */
const KEYBOARD_ACTIVATABLE_ROLES = new Set([
  'button',
  'link',
  'checkbox',
  'radio',
  'switch',
  'menuitem',
  'menuitemcheckbox',
  'menuitemradio',
  'option',
  'tab',
]);

/**
 * 이벤트 대상이 **키보드 기본 활성화(Space/Enter)를 가진 컨트롤**인가.
 *
 * ⚠ Space 팬 홀드는 window keydown 을 preventDefault 하는데, 버튼의 Space 활성화는 **keyup** 에
 *   일어난다 — keydown 을 막으면 그 활성화가 통째로 사라져, 포커스된 버튼이 Space 로 눌리지 않는다
 *   (진행 오버레이의 '작업 취소' 버튼이 ESC 로만 눌리던 결함 NF-2). 텍스트 입력 보존과 같은 이유로
 *   이 요소들 위에서는 팬 홀드를 시작하지 않는다(팬은 캔버스/본문 포커스에서 그대로 동작).
 */
function isKeyboardActivatableTarget(target: EventTarget | null): boolean {
  const el = target as HTMLElement | null;
  const tag = el?.tagName;
  if (typeof tag !== 'string') return false; // window/document 등 — 캔버스 조작으로 본다
  if (el?.isContentEditable) return true;
  if (KEYBOARD_ACTIVATABLE_TAGS.has(tag)) return true;
  if (tag === 'A' && el?.hasAttribute('href')) return true;
  const role = el?.getAttribute('role');
  return role !== null && role !== undefined && KEYBOARD_ACTIVATABLE_ROLES.has(role);
}

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
    onImageSize,
    immediateSegment = false,
    segmentSimplifyTolerance,
  }: CanvasShellProps,
  ref,
) {
  const stageRef = useRef<Konva.Stage | null>(null);
  // 편집 차단 단일 판정원 — 장시간 작업(저장/불러오기/AI) 진행 중이면 캔버스 입력을 막는다.
  // 두 라벨링 화면(내부·포털 업로드)이 모두 이 컴포넌트를 거치므로 배선이 한 곳에서 끝난다.
  const editBlocked = useIsEditBlocked(frame.srcSn);
  const zoom = useLabelStore((s) => s.zoom);
  const panX = useLabelStore((s) => s.panX);
  const panY = useLabelStore((s) => s.panY);
  const activeTool = useLabelStore((s) => s.activeTool);
  const setZoom = useLabelStore((s) => s.setZoom);
  const setPan = useLabelStore((s) => s.setPan);
  // Phase 2c — 이미지 조절(밝기/대비 필터 + 레이어 투명도). 세션 전용 상태.
  const imageAdjust = useLabelStore((s) => s.imageAdjust);

  const [imageEl, setImageEl] = useState<HTMLImageElement | null>(null);
  // 캔버스 이미지 로드 진행 상태(R7) — imageUrl 존재 & 로드 완료/실패 전이면 true → 중앙 스피너.
  const [imageLoading, setImageLoading] = useState(false);
  // SAM2 클릭/박스 분할 — 진행 중 무시 + 프레임 전환 stale 폐기 가드 포함.
  // isSegmenting: 요청 in-flight 진행 인디케이터(R7)용.
  const { segment: rawSegment, isSegmenting } = useSam2Segment(frame.srcSn);
  // 사용자가 조절한 경계 세밀함이 있으면 모든 분할 요청 payload 에 주입(미조절이면 그대로 전달 → BE 기본값).
  const segment = useCallback(
    (payload: Parameters<typeof rawSegment>[0]) =>
      segmentSimplifyTolerance !== undefined
        ? rawSegment({ ...payload, simplifyTolerance: segmentSimplifyTolerance })
        : rawSegment(payload),
    [rawSegment, segmentSimplifyTolerance],
  );
  const [segNotice, setSegNotice] = useState<string | null>(null);

  // R2 — 뷰 팬(스페이스+드래그 주 / 중클릭 드래그 부). 확대(zoom>1) 상태에서만 동작.
  // 스페이스 눌림 중(또는 드래그 중)에는 드로잉/선택 레이어의 listening 을 꺼 기존 pointer
  // 이벤트와 충돌을 원천 차단하고, 해제 시 복귀시킨다.
  const spaceDownRef = useRef(false);
  const [spaceDown, setSpaceDown] = useState(false);
  const [panning, setPanning] = useState(false);
  // 드래그 시작 시점의 포인터(캔버스 좌표)와 pan 스냅샷 — delta 누적 기준.
  const panDragRef = useRef<{ startX: number; startY: number; panX: number; panY: number } | null>(
    null,
  );
  // rAF 배칭 — 팬 드래그 중 매 mousemove 마다 setPan(store write/redraw) 하지 않고 프레임당 1회
  // 마지막 좌표만 커밋한다. 예약된 rAF 는 종료/언마운트/blur 정리 시 취소한다.
  const panRafRef = useRef<number | null>(null);
  const pendingPanRef = useRef<{ x: number; y: number } | null>(null);

  useEffect(() => {
    const isSpaceKey = (e: KeyboardEvent) => e.code === 'Space' || e.key === ' ';
    // 스페이스 홀드/진행 팬을 모두 해제(커서·레이어 listening 복귀). window blur(Alt+Tab)/
    // 탭 숨김 시 spaceDown 이 true 로 고착돼 좌클릭 드로잉/선택이 먹통 되는 버그 방지에 공용 사용.
    const releaseSpaceAndPan = () => {
      spaceDownRef.current = false;
      setSpaceDown(false);
      if (panRafRef.current != null) {
        cancelAnimationFrame(panRafRef.current);
        panRafRef.current = null;
      }
      pendingPanRef.current = null;
      panDragRef.current = null;
      setPanning(false);
    };
    const onKeyDown = (e: KeyboardEvent) => {
      if (!isSpaceKey(e)) return;
      // 입력 필드/편집영역, 그리고 **Space 로 활성화되는 컨트롤**(버튼·링크 등) 위에서는
      // 팬/스크롤 억제를 하지 않는다 — 텍스트 입력과 기본 키보드 활성화를 보존한다.
      if (isKeyboardActivatableTarget(e.target)) return;
      spaceDownRef.current = true;
      setSpaceDown(true);
      e.preventDefault(); // 페이지 스페이스 스크롤 방지
    };
    const onKeyUp = (e: KeyboardEvent) => {
      if (!isSpaceKey(e)) return;
      releaseSpaceAndPan();
    };
    const onBlur = () => releaseSpaceAndPan();
    const onVisibility = () => {
      if (document.visibilityState === 'hidden') releaseSpaceAndPan();
    };
    window.addEventListener('keydown', onKeyDown);
    window.addEventListener('keyup', onKeyUp);
    window.addEventListener('blur', onBlur);
    document.addEventListener('visibilitychange', onVisibility);
    return () => {
      window.removeEventListener('keydown', onKeyDown);
      window.removeEventListener('keyup', onKeyUp);
      window.removeEventListener('blur', onBlur);
      document.removeEventListener('visibilitychange', onVisibility);
      // 언마운트 시 예약된 rAF 취소(누수/콜백 후 setState 방지).
      if (panRafRef.current != null) cancelAnimationFrame(panRafRef.current);
    };
  }, []);

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
      setImageLoading(false); // 빈 URL — placeholder 만 노출(스피너 없음).
      return;
    }
    let cancelled = false;
    setImageLoading(true); // 로드 시작 → 중앙 스피너 표시.
    const img = new Image();
    img.crossOrigin = 'anonymous';
    img.onload = () => {
      if (!cancelled) {
        setImageEl(img);
        setImageLoading(false);
      }
    };
    img.onerror = () => {
      if (!cancelled) {
        setImageEl(null);
        setImageLoading(false); // 실패 시에도 스피너 해제(무한 로딩 방지).
      }
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

  // 팬 시작 — 스페이스 눌림 또는 중클릭(button===1)일 때만. fit(zoom<=MIN_ZOOM)에선 no-op.
  function handleMouseDown(e: Konva.KonvaEventObject<MouseEvent>) {
    if (zoom <= MIN_ZOOM) return;
    const isMiddle = e.evt.button === 1;
    if (!spaceDownRef.current && !isMiddle) return;
    e.evt.preventDefault();
    const pointer = stageRef.current?.getPointerPosition();
    if (!pointer) return;
    panDragRef.current = { startX: pointer.x, startY: pointer.y, panX, panY };
    setPanning(true);
  }

  function handleMouseMove() {
    const drag = panDragRef.current;
    if (!drag || !imageSize) return;
    const pointer = stageRef.current?.getPointerPosition();
    if (!pointer) return;
    const nextPanX = drag.panX + (pointer.x - drag.startX);
    const nextPanY = drag.panY + (pointer.y - drag.startY);
    // 이미지가 뷰포트 밖으로 완전 이탈하지 않게 클램프(store clampPan — 렌더 geometry 와 동일 규칙).
    const clamped = clampPan(
      nextPanX,
      nextPanY,
      zoom,
      { w: width, h: height },
      { w: imageSize.width, h: imageSize.height },
    );
    // rAF 배칭 — 마지막 좌표만 보관하고 프레임당 1회 setPan. 매 mousemove store write/redraw 방지.
    pendingPanRef.current = clamped;
    if (panRafRef.current == null) {
      panRafRef.current = requestAnimationFrame(() => {
        panRafRef.current = null;
        const p = pendingPanRef.current;
        pendingPanRef.current = null;
        if (p) setPan(p.x, p.y);
      });
    }
  }

  function endPan() {
    if (!panDragRef.current) return;
    panDragRef.current = null;
    setPanning(false);
    // 예약된 rAF 를 취소하고 마지막 좌표를 즉시 커밋(마지막 이동 드롭 방지).
    if (panRafRef.current != null) {
      cancelAnimationFrame(panRafRef.current);
      panRafRef.current = null;
    }
    const p = pendingPanRef.current;
    pendingPanRef.current = null;
    if (p) setPan(p.x, p.y);
  }

  // 스페이스 눌림/드래그 중에는 드로잉·선택 pointer 이벤트를 억제(레이어 listening off) →
  // 팬은 Stage 핸들러로만 처리되고, 해제 시 즉시 복귀한다.
  const interactionSuppressed = spaceDown || panning;
  const cursor = panning ? 'grabbing' : spaceDown ? 'grab' : undefined;

  return (
    <div
      className="relative"
      data-testid="canvas-shell"
      // 캔버스는 DOM 으로 상태를 드러내지 않아 차단 여부를 밖에서 확인할 방법이 없다 —
      // 회귀 가드가 관측할 수 있도록 두 차단축을 각각 노출한다(내부 식별자·경로는 담지 않는다).
      data-edit-blocked={editBlocked ? 'true' : 'false'}
      data-read-only={readOnly ? 'true' : 'false'}
      aria-busy={editBlocked}
      style={cursor ? { cursor } : undefined}
    >
      <Stage
        ref={stageRef}
        width={width}
        height={height}
        className="bg-bgLight"
        onWheel={handleWheel}
        onMouseDown={handleMouseDown}
        onMouseMove={handleMouseMove}
        onMouseUp={endPan}
        onMouseLeave={endPan}
      >
        <Layer listening={false} name="image-layer">
          {geometry && imageEl && (
            <ImageLayer image={imageEl} geometry={geometry} adjust={imageAdjust} />
          )}
        </Layer>
        <Layer
          name="labels-layer"
          opacity={imageAdjust.labelOpacity}
          // 차단 구간에는 라벨 레이어의 pointer 이벤트를 통째로 끈다 — 선택·이동·꼭짓점 편집이
          // 시작될 수 있는 입구를 하나도 남기지 않는다(핸들러 가드는 그 다음 방어선).
          listening={!interactionSuppressed && !editBlocked}
        >
          {/* 타이밍 가드 — geometry(실측 크기) 확정 전에는 라벨을 그리지 않는다. */}
          {geometry && (
            <LabelsLayer
              labels={labels}
              geometry={geometry}
              readOnly={readOnly}
              editBlocked={editBlocked}
            />
          )}
        </Layer>
        <Layer name="overlay-layer" opacity={imageAdjust.activeOpacity} listening={!interactionSuppressed}>
          {geometry && (
            <OverlayLayer
              ref={ref}
              geometry={geometry}
              activeTool={activeTool}
              onLabelAdd={onLabelAdd}
              stageRef={stageRef}
              segment={segment}
              /* 프레임 동일성 판정 키 — segment 참조는 세밀함 슬라이더로도 바뀌므로 쓸 수 없다. */
              srcSn={frame.srcSn}
              onMockWarning={handleMockWarning}
              onLowConfidence={handleLowConfidence}
              onCommitError={handleCommitError}
              onKeypointPlacingChange={onKeypointPlacingChange}
              immediateSegment={immediateSegment}
              isSegmenting={isSegmenting}
            />
          )}
        </Layer>
      </Stage>
      {/* R7 — 캔버스 이미지 로드 중 중앙 스피너 오버레이(로드 완료/실패 시 해제). */}
      {imageLoading && (
        <div
          data-testid="canvas-image-spinner"
          role="status"
          className="absolute inset-0 z-10 flex items-center justify-center bg-black/20"
        >
          <Spinner size="lg" label="이미지 로딩 중" />
        </div>
      )}
      {/* R7 — SAM2 분할 요청 in-flight 진행 인디케이터(완료/실패 시 해제). */}
      {isSegmenting && (
        <div
          data-testid="sam2-progress"
          role="status"
          className="absolute top-2 left-2 z-10 flex items-center gap-2 rounded bg-black/70 px-3 py-1 text-xs text-white"
        >
          <Spinner size="sm" label="AI 분할 처리 중" />
          <span>AI 분할 처리 중…</span>
        </div>
      )}
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
