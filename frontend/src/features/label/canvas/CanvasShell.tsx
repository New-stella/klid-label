import { forwardRef, useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Layer, Line, Rect, Stage } from 'react-konva';
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
import { buildGeometry, clampPan as clampPanByScale } from './utils/canvasGeometry';
import type { Geometry, Size } from './utils/coordinateTransformer';
import { zoomToPoint, type ZoomState } from './utils/zoomToPoint';

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
  /**
   * 화면 표시용 회전각(0/90/180/270°, 시계방향) — **보기 전용**이다.
   *
   * ★ 저장되는 라벨 좌표는 회전의 영향을 받지 않는다. 회전은 이미지·라벨을 함께 돌려 **보여주기만**
   *   하며 `labels` 는 물론 스토어의 어떤 좌표도 건드리지 않는다. 회전은 이미지 좌표계가 아니라
   *   **캔버스(뷰) 좌표계**에 걸리므로 geometry 의 이미지↔캔버스 환산식 자체가 그대로 유지된다.
   *
   * ★ 회전 중에는 편집 핸들을 붙이지 않는다(사양). 회전이 0 이 아니면 라벨·오버레이 레이어의 포인터
   *   이벤트를 끄고 라벨 레이어를 읽기 전용으로 내린다 — 아래 `rotated` 주석 참조.
   *
   * 규격 밖 값(90 의 배수가 아니거나 유한하지 않은 수)은 0(무회전)으로 떨어뜨린다. 보기 전용 값이라
   * 예외로 화면을 죽이는 것보다 회전을 포기하는 쪽이 안전하다.
   */
  rotation?: number;
  /** 격자 오버레이 표시(보기 전용) — 히트테스트에 관여하지 않는 순수 오버레이다. 기본 OFF. */
  showGrid?: boolean;
  /**
   * 영역 확대 모드(보기 전용) — 드래그한 사각형이 화면을 채우도록 줌/팬을 옮긴다. 기본 OFF.
   *
   * ★**편집이 아니라 보기 조작이다.** 이 모드에서 그린 사각형은 라벨을 만들지 않는다 — 켜져 있는
   *   동안 라벨·오버레이 레이어의 포인터 이벤트를 끊어 드로잉 경로 자체를 봉인한다(회전과 동일 축).
   *   봉인이 없으면 같은 좌클릭 드래그가 확대와 도형 생성 양쪽으로 발화해 **의도치 않은 라벨이
   *   저장된다**(데이터 오염).
   *
   * ★회전 중에도 동작한다 — 보기 조작은 회전 중에도 남긴다는 방침이고, 포인터는 `toViewPoint` 로
   *   회전 이전 뷰 좌표로 되돌린 뒤 계산하므로 회전각과 무관하게 같은 결과가 나온다.
   */
  zoomAreaMode?: boolean;
}

/** 사양이 정한 화면 표시용 회전 단계(시계방향). */
export type CanvasRotation = 0 | 90 | 180 | 270;

/**
 * 임의 입력을 사양의 4단계로 정규화한다.
 * 90 의 배수가 아니거나 유한하지 않으면 **0(무회전)** — 보기 전용 값이라 fail-safe 로 처리한다.
 */
export function normalizeCanvasRotation(value: number | undefined): CanvasRotation {
  if (value === undefined || !Number.isFinite(value) || value % 90 !== 0) return 0;
  const normalized = ((value % 360) + 360) % 360;
  return normalized as CanvasRotation;
}

/**
 * 화면(Stage) 좌표의 벡터를 시계방향 `deg` 만큼 회전한다.
 *
 * 90 의 배수만 다루므로 삼각함수 대신 정수 성분 교환으로 계산한다 — `Math.cos(Math.PI/2)` 가 정확히
 * 0 이 아니라 생기는 부동소수 찌꺼기가 좌표에 섞이지 않게 한다.
 * (화면 좌표계는 y 가 아래로 증가하므로 +90° = 시계방향 → (x, y) → (−y, x))
 */
export function rotateVectorClockwise(x: number, y: number, deg: number): [number, number] {
  // 부호 반전은 0 을 −0 으로 만든다. 값은 같지만 비교·로그에서 혼란을 주므로 0 으로 접어 둔다.
  const nz = (v: number) => (v === 0 ? 0 : v);
  switch (((deg % 360) + 360) % 360) {
    case 90:
      return [nz(-y), nz(x)];
    case 180:
      return [nz(-x), nz(-y)];
    case 270:
      return [nz(y), nz(-x)];
    default:
      return [nz(x), nz(y)];
  }
}

/**
 * Stage 포인터 좌표 → **회전 이전의 뷰 좌표**(geometry 가 쓰는 좌표계).
 *
 * 회전은 레이어 변환으로 걸리므로 `stage.getPointerPosition()` 은 여전히 회전된 화면 좌표를 준다.
 * 줌/팬처럼 포인터를 geometry 와 맞춰야 하는 계산은 이 역변환을 반드시 거쳐야 한다.
 *
 * 규칙: Stage 중심을 원점으로 옮겨 −rotation 만큼 되돌린 뒤, 뷰(회전 전 캔버스) 중심으로 옮긴다.
 * 90/270° 에서는 뷰의 가로·세로가 Stage 와 뒤바뀌어 있다(`view` 인자).
 */
export function toViewPoint(
  pointer: { x: number; y: number },
  rotation: CanvasRotation,
  stage: { width: number; height: number },
  view: { width: number; height: number },
): { x: number; y: number } {
  if (rotation === 0) return pointer;
  const [x, y] = rotateVectorClockwise(
    pointer.x - stage.width / 2,
    pointer.y - stage.height / 2,
    -rotation,
  );
  return { x: x + view.width / 2, y: y + view.height / 2 };
}

/**
 * 영역 확대 드래그로 인정하는 **최소 변 길이**(뷰 픽셀). 이보다 작으면 오클릭으로 보고 무시한다.
 *
 * 없으면 클릭 한 번(가로세로 0~2px)이 배율 상한까지 튀어 화면이 통째로 바뀌고, 사용자는 자기가
 * 어디를 보고 있었는지 잃는다. 두 변 **모두** 이 값 이상일 때만 확대한다 — 한 축만 재면 얇은 띠
 * 드래그(사실상 선긋기)가 통과한다.
 */
export const ZOOM_AREA_MIN_DRAG_PX = 12;
/**
 * 선택 사각형 색 — KRDS 기본 강조색(#256EF4). 어두운 CCTV 프레임·밝은 프레임 양쪽에서 읽히도록
 * 테두리는 불투명에 가깝게, 채움은 아래 화면이 비치도록 낮은 알파로 둔다.
 */
const ZOOM_AREA_STROKE = 'rgba(37, 110, 244, 0.9)';
const ZOOM_AREA_FILL = 'rgba(37, 110, 244, 0.15)';

/**
 * 선택 사각형(뷰 좌표) → 그 영역이 화면을 채우는 새 zoom/pan. 순수 함수.
 *
 * - 배율 한계는 **기존 줌 정책 그대로**다(호출부가 MIN_ZOOM/MAX_ZOOM 을 넘긴다). 아주 작은 영역을
 *   드래그해도 `zoomToPoint` 의 clamp 가 상한에서 잘라내므로 배율이 발산하지 않는다.
 * - 팬 클램프도 렌더 geometry 와 **같은 규칙**(canvasGeometry.clampPan)을 쓴다 — 별도 규칙을 두면
 *   스토어 pan 과 실제 배치가 어긋난다.
 * - `zoomToPoint` 는 지정한 점을 **있던 자리**에 고정하므로, 그다음 영역 중심을 뷰 중심으로 한 번 더
 *   평행이동해야 선택한 영역이 화면 가운데에 온다.
 *
 * 임계 미만 드래그·geometry 미확정이면 `null` — 호출부는 **아무 일도 하지 않는다**.
 */
export function zoomToArea(
  geom: Geometry,
  rect: { x: number; y: number; width: number; height: number },
  minZoom: number,
  maxZoom: number,
): ZoomState | null {
  if (geom.scale <= 0 || geom.image.width <= 0 || geom.image.height <= 0) return null;
  if (rect.width < ZOOM_AREA_MIN_DRAG_PX || rect.height < ZOOM_AREA_MIN_DRAG_PX) return null;
  const factor = Math.min(geom.canvas.width / rect.width, geom.canvas.height / rect.height);
  const center = { x: rect.x + rect.width / 2, y: rect.y + rect.height / 2 };
  const zoomed = zoomToPoint(geom, center, factor, minZoom, maxZoom);
  const fitScale = Math.min(
    geom.canvas.width / geom.image.width,
    geom.canvas.height / geom.image.height,
  );
  const { panX, panY } = clampPanByScale(
    geom.image,
    geom.canvas,
    fitScale * zoomed.zoom,
    zoomed.panX + (geom.canvas.width / 2 - center.x),
    zoomed.panY + (geom.canvas.height / 2 - center.y),
  );
  return { zoom: zoomed.zoom, panX, panY };
}

/** 드래그 두 점 → 정규화된 사각형(음수 폭 없음). */
function rectBetween(
  a: { x: number; y: number },
  b: { x: number; y: number },
): { x: number; y: number; width: number; height: number } {
  return {
    x: Math.min(a.x, b.x),
    y: Math.min(a.y, b.y),
    width: Math.abs(b.x - a.x),
    height: Math.abs(b.y - a.y),
  };
}

/** 격자 한 칸의 **이미지 픽셀** 크기 — 확대/축소하면 화면상 간격도 함께 변한다(이미지 기준 격자). */
const GRID_CELL_IMAGE_PX = 100;
/** 한 축당 격자선 상한 — 초대형 프레임에서 선이 수천 개 생겨 렌더가 멎는 것을 막는다(CWE-770). */
const GRID_MAX_LINES_PER_AXIS = 200;
/**
 * 격자선 색 — 어두운 CCTV 프레임과 밝은 프레임 **양쪽에서 모두** 읽히도록 중간 회색을 반투명으로 쓴다.
 * (흰색은 밝은 프레임에서, 검정은 야간 프레임에서 각각 사라진다.)
 */
const GRID_STROKE = 'rgba(128, 128, 128, 0.6)';

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
 *
 * 보기 전용 축(회전·격자)은 **표시에만** 관여한다 — 라벨 좌표를 읽지도 쓰지도 않는다.
 * 회전은 레이어 변환으로 걸리므로 geometry(이미지↔캔버스 환산)는 회전 이전 좌표계 그대로다.
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
    rotation: rotationProp,
    showGrid = false,
    zoomAreaMode = false,
  }: CanvasShellProps,
  ref,
) {
  // 보기 전용 회전 — 규격 밖 값은 0 으로 떨어진다(위 normalizeCanvasRotation 주석).
  const rotation = normalizeCanvasRotation(rotationProp);
  /**
   * ★회전 중에는 편집을 붙이지 않는다 (사양 + 좌표 오염 방지).
   *
   * 이 캔버스의 입력 경로(OverlayLayer 드로잉·LabelsLayer 이동/리사이즈)는 전부
   * `stage.getPointerPosition()` 이 준 **회전되지 않은 화면 좌표**를 geometry 로 환산해 이미지 좌표를
   * 만든다. 회전은 레이어 변환으로 걸리므로 그 전제가 깨지고, 그대로 두면 사용자가 찍은 위치와 다른
   * 좌표가 **저장된다**(데이터 오염). 회전 중에는 포인터 이벤트를 아예 끊어 그 경로를 봉인한다.
   */
  const rotated = rotation !== 0;
  // 90/270° 에서는 회전 뒤 화면을 채우도록 뷰(회전 전 캔버스)의 가로·세로가 뒤바뀐다.
  // geometry·pan clamp 는 모두 이 뷰 크기를 기준으로 계산한다.
  const quarterTurned = rotation === 90 || rotation === 270;
  const viewWidth = quarterTurned ? height : width;
  const viewHeight = quarterTurned ? width : height;
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

  // 영역 확대(보기 조작) — 드래그 시작점과 진행 중 사각형(둘 다 **뷰 좌표**, 회전 역변환 이후).
  // 적용 판정은 ref 를 읽는다(핸들러가 같은 틱에 연달아 불려도 최신 값을 본다). state 는 렌더용.
  const zoomAreaStartRef = useRef<{ x: number; y: number } | null>(null);
  const zoomAreaRectRef = useRef<{ x: number; y: number; width: number; height: number } | null>(
    null,
  );
  const [zoomAreaRect, setZoomAreaRect] = useState<{
    x: number;
    y: number;
    width: number;
    height: number;
  } | null>(null);

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
    // ⚠ geometry.angle 은 계속 0 이다 — 회전은 **레이어 변환**으로 걸고 이미지↔캔버스 환산식은
    //   회전 이전 좌표계 그대로 둔다. 여기에 각도를 넣으면 라벨·오버레이가 각자 점을 회전시켜
    //   축정렬 도형(BBox)의 렌더·드래그 계산이 어긋난다.
    return buildGeometry(imageSize, { width: viewWidth, height: viewHeight }, zoom, panX, panY, 0);
  }, [imageSize, viewWidth, viewHeight, zoom, panX, panY]);

  /**
   * 회전 뷰 변환 — 레이어(Konva Container)에 그대로 얹는다.
   *
   * 뷰 중심(viewWidth/2, viewHeight/2)을 Stage 중심에 고정하고 그 점을 축으로 회전한다.
   * rotation === 0 이면 **props 자체를 붙이지 않아** 회전 도입 전과 렌더 트리가 동일하다(무회귀).
   */
  const viewTransform = rotated
    ? {
        x: width / 2,
        y: height / 2,
        offsetX: viewWidth / 2,
        offsetY: viewHeight / 2,
        rotation,
      }
    : undefined;

  /**
   * 격자 오버레이 선분(캔버스 좌표) — **순수 표시**다.
   *
   * 자기 레이어에 그리고 레이어·도형 모두 listening=false 라 히트테스트(라벨 선택·드로잉)에 어떤
   * 방식으로도 관여하지 않는다. 좌표는 이미지 격자(GRID_CELL_IMAGE_PX)를 캔버스로 환산한 값이라
   * 줌/팬을 그대로 따라간다.
   */
  const gridLines = useMemo<number[][] | null>(() => {
    if (!showGrid || !geometry || geometry.scale <= 0) return null;
    const { image, scale, left, top } = geometry;
    const right = left + image.width * scale;
    const bottom = top + image.height * scale;
    // 초대형 프레임에서는 칸을 넓혀 선 개수를 상한 이하로 유지한다.
    const stepX = Math.max(GRID_CELL_IMAGE_PX, Math.ceil(image.width / GRID_MAX_LINES_PER_AXIS));
    const stepY = Math.max(GRID_CELL_IMAGE_PX, Math.ceil(image.height / GRID_MAX_LINES_PER_AXIS));
    const lines: number[][] = [];
    for (let x = 0; x <= image.width; x += stepX) {
      const cx = left + x * scale;
      lines.push([cx, top, cx, bottom]);
    }
    for (let y = 0; y <= image.height; y += stepY) {
      const cy = top + y * scale;
      lines.push([left, cy, right, cy]);
    }
    return lines;
  }, [showGrid, geometry]);

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
    // 회전 중이면 포인터를 회전 이전 뷰 좌표로 되돌려야 커서 아래 지점이 그대로 유지된다.
    const viewPointer = toViewPoint(
      pointer,
      rotation,
      { width, height },
      { width: viewWidth, height: viewHeight },
    );
    const next = zoomToPoint(geometry, viewPointer, factor, MIN_ZOOM, MAX_ZOOM);
    setZoom(next.zoom);
    setPan(next.panX, next.panY);
  }

  /** Stage 포인터 → **회전 이전 뷰 좌표**. 회전 중에도 geometry 와 같은 좌표계에서 계산하기 위함. */
  function pointerInView(): { x: number; y: number } | null {
    const pointer = stageRef.current?.getPointerPosition();
    if (!pointer) return null;
    return toViewPoint(
      pointer,
      rotation,
      { width, height },
      { width: viewWidth, height: viewHeight },
    );
  }

  /**
   * 진행 중인 영역 확대 드래그를 끝낸다. `apply=false` 면 확대하지 않고 버린다(취소).
   * 드래그 중이 아니었으면 `false` 를 돌려 호출부가 기존 팬 종료 경로로 넘어가게 한다.
   */
  function endZoomArea(apply: boolean): boolean {
    if (!zoomAreaStartRef.current) return false;
    const rect = zoomAreaRectRef.current;
    zoomAreaStartRef.current = null;
    zoomAreaRectRef.current = null;
    setZoomAreaRect(null);
    if (!apply || !rect || !geometry) return true;
    // 임계 미만(오클릭)이면 null — 아무 일도 일어나지 않는다. 배율 상·하한은 기존 줌 정책을 그대로 쓴다.
    const next = zoomToArea(geometry, rect, MIN_ZOOM, MAX_ZOOM);
    if (!next) return true;
    setZoom(next.zoom);
    setPan(next.panX, next.panY);
    return true;
  }

  // 모드를 끄면 진행 중이던 드래그도 함께 버린다(끈 뒤 마우스를 떼서 확대되는 유령 조작 방지).
  useEffect(() => {
    if (zoomAreaMode) return;
    zoomAreaStartRef.current = null;
    zoomAreaRectRef.current = null;
    setZoomAreaRect(null);
  }, [zoomAreaMode]);

  // 팬 시작 — 스페이스 눌림 또는 중클릭(button===1)일 때만. fit(zoom<=MIN_ZOOM)에선 no-op.
  function handleMouseDown(e: Konva.KonvaEventObject<MouseEvent>) {
    // 영역 확대는 좌클릭 드래그로 시작한다. 팬(스페이스 홀드·중클릭)이 우선이라 두 조작이
    // 같은 입력을 다투지 않는다 — 확대 중에도 스페이스를 누르면 평소대로 화면을 끌 수 있다.
    if (zoomAreaMode && e.evt.button === 0 && !spaceDownRef.current) {
      const start = pointerInView();
      if (!start) return;
      e.evt.preventDefault();
      zoomAreaStartRef.current = start;
      zoomAreaRectRef.current = null;
      setZoomAreaRect({ x: start.x, y: start.y, width: 0, height: 0 });
      return;
    }
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
    const zoomAreaStart = zoomAreaStartRef.current;
    if (zoomAreaStart) {
      const current = pointerInView();
      if (!current) return;
      const rect = rectBetween(zoomAreaStart, current);
      zoomAreaRectRef.current = rect;
      setZoomAreaRect(rect);
      return;
    }
    const drag = panDragRef.current;
    if (!drag || !imageSize) return;
    const pointer = stageRef.current?.getPointerPosition();
    if (!pointer) return;
    // 팬 값은 회전 이전 뷰 좌표계의 값이므로, 화면에서 끈 거리(delta)를 −rotation 만큼 되돌린다.
    // (되돌리지 않으면 90° 회전 상태에서 오른쪽으로 끌 때 이미지가 아래로 움직인다.)
    const [dragX, dragY] = rotateVectorClockwise(
      pointer.x - drag.startX,
      pointer.y - drag.startY,
      -rotation,
    );
    const nextPanX = drag.panX + dragX;
    const nextPanY = drag.panY + dragY;
    // 이미지가 뷰포트 밖으로 완전 이탈하지 않게 클램프(store clampPan — 렌더 geometry 와 동일 규칙).
    const clamped = clampPan(
      nextPanX,
      nextPanY,
      zoom,
      { w: viewWidth, h: viewHeight },
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

  /** 마우스를 뗄 때: 영역 확대 드래그가 있으면 그것을 적용하고, 아니면 기존 팬 종료 경로. */
  function handleMouseUp() {
    if (endZoomArea(true)) return;
    endPan();
  }

  /**
   * 캔버스 밖으로 나가면 영역 확대 드래그는 **적용하지 않고 버린다**.
   * 끝점을 알 수 없는 드래그로 화면을 통째로 바꾸느니 아무 일도 일어나지 않는 편이 안전하다.
   */
  function handleMouseLeave() {
    if (endZoomArea(false)) return;
    endPan();
  }

  // 스페이스 눌림/드래그 중에는 드로잉·선택 pointer 이벤트를 억제(레이어 listening off) →
  // 팬은 Stage 핸들러로만 처리되고, 해제 시 즉시 복귀한다.
  // 회전 중(rotated)도 같은 축으로 억제한다 — 포인터 좌표와 geometry 좌표계가 어긋나므로
  // 편집 입력을 받으면 사용자가 찍은 위치와 다른 좌표가 저장된다(위 rotated 주석).
  // 영역 확대(zoomAreaMode)도 같은 축이다 — 같은 좌클릭 드래그가 확대와 도형 생성 양쪽으로
  // 발화하면 보기 조작이 **라벨을 만들어 저장한다**(데이터 오염).
  const interactionSuppressed = spaceDown || panning || rotated || zoomAreaMode;
  const cursor = panning
    ? 'grabbing'
    : spaceDown
      ? 'grab'
      : zoomAreaMode
        ? 'crosshair'
        : undefined;

  return (
    <div
      className="relative"
      data-testid="canvas-shell"
      // 캔버스는 DOM 으로 상태를 드러내지 않아 차단 여부를 밖에서 확인할 방법이 없다 —
      // 회귀 가드가 관측할 수 있도록 두 차단축을 각각 노출한다(내부 식별자·경로는 담지 않는다).
      data-edit-blocked={editBlocked ? 'true' : 'false'}
      data-read-only={readOnly ? 'true' : 'false'}
      // 보기 전용 축(회전·격자)도 같은 이유로 노출한다 — 회전은 "편집이 왜 안 먹는가"의 근거이고,
      // 격자는 순수 오버레이라 다른 관측 수단이 없다.
      data-rotation={String(rotation)}
      data-show-grid={showGrid ? 'true' : 'false'}
      data-zoom-area={zoomAreaMode ? 'true' : 'false'}
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
        onMouseUp={handleMouseUp}
        onMouseLeave={handleMouseLeave}
      >
        <Layer {...viewTransform} listening={false} name="image-layer">
          {geometry && imageEl && (
            <ImageLayer image={imageEl} geometry={geometry} adjust={imageAdjust} />
          )}
        </Layer>
        {/* 격자 오버레이 — 이미지 위·라벨 아래. 레이어와 각 선분 모두 listening=false 라
            라벨 선택/드로잉 히트테스트 경로에 전혀 참여하지 않는다. */}
        {gridLines && (
          <Layer {...viewTransform} name="grid-layer" listening={false}>
            {gridLines.map((points, i) => (
              <Line
                key={i}
                points={points}
                stroke={GRID_STROKE}
                strokeWidth={1}
                listening={false}
                perfectDrawEnabled={false}
              />
            ))}
          </Layer>
        )}
        <Layer
          {...viewTransform}
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
              /* 회전·영역 확대 중에는 편집 핸들(이동·리사이즈)을 붙이지 않는다 — listening 차단의
                 두 번째 방어선. 보기 조작이 라벨을 옮기는 일이 없어야 한다. */
              readOnly={readOnly || rotated || zoomAreaMode}
              editBlocked={editBlocked}
            />
          )}
        </Layer>
        <Layer
          {...viewTransform}
          name="overlay-layer"
          opacity={imageAdjust.activeOpacity}
          listening={!interactionSuppressed}
        >
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
        {/* 영역 확대 선택 사각형 — 라벨보다 위에 그리되 히트테스트에는 참여하지 않는다(순수 표시).
            좌표는 **뷰 좌표**라 회전 변환(viewTransform)을 그대로 얹으면 포인터 아래에 정확히 붙는다. */}
        {zoomAreaRect && (
          <Layer {...viewTransform} name="zoom-area-layer" listening={false}>
            <Rect
              x={zoomAreaRect.x}
              y={zoomAreaRect.y}
              width={zoomAreaRect.width}
              height={zoomAreaRect.height}
              stroke={ZOOM_AREA_STROKE}
              strokeWidth={1}
              dash={[4, 4]}
              fill={ZOOM_AREA_FILL}
              listening={false}
              perfectDrawEnabled={false}
            />
          </Layer>
        )}
      </Stage>
      {/* 회전 보기 안내 — 편집이 왜 반응하지 않는지 화면에서 바로 알 수 있어야 한다.
          (조작 없이 도구만 눌러보다 "먹통"으로 오인하는 동선을 없앤다.) */}
      {rotated && (
        <div
          data-testid="canvas-rotation-notice"
          role="status"
          className="absolute top-2 right-2 z-10 rounded bg-black/70 px-3 py-1 text-caption text-white"
        >
          회전 보기 {rotation}° — 편집이 잠깁니다
        </div>
      )}
      {/* 영역 확대 안내 — 이 구간에는 그리기가 반응하지 않으므로 그 이유와 조작법을 함께 알린다.
          (되돌아갈 때는 도구바의 '화면 맞춤'.) */}
      {zoomAreaMode && (
        <div
          data-testid="canvas-zoom-area-notice"
          role="status"
          className="absolute top-2 left-1/2 z-10 -translate-x-1/2 rounded bg-black/70 px-3 py-1 text-caption text-white"
        >
          영역 확대 — 확대할 영역을 드래그하세요 (되돌리기: 화면 맞춤)
        </div>
      )}
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
          className="absolute top-2 left-2 z-10 flex items-center gap-2 rounded bg-black/70 px-3 py-1 text-caption text-white"
        >
          <Spinner size="sm" label="AI 분할 처리 중" />
          <span>AI 분할 처리 중…</span>
        </div>
      )}
      {segNotice && (
        <div
          role="status"
          className="absolute bottom-2 left-2 z-10 rounded bg-warning/90 px-3 py-1 text-caption text-white"
        >
          {segNotice}
        </div>
      )}
    </div>
  );
});
